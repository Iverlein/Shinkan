package shinkan.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The policy as text, one statement per line (SPEC §4.4): what the app stores
 * and what a parent may read or paste. No library, so the format is this file.
 *
 *     mode closed
 *     plan mon,tue,wed,thu,fri
 *       06:00-10:00 browser player
 *       player 30 rest 90
 *       browser 120
 *     close com.example.game
 *     window com.example.game sat,sun 10:00-12:00
 *     permission com.example.game deny camera
 *     volume player 40
 *     start https://www.wikipedia.org
 *     restrict factory_reset
 *
 * A `plan` line takes the indented lines under it as its stages: a span
 * (`HH:MM-HH:MM` and the doors open in it) or a budget (the doors, the minutes,
 * and `rest` with minutes or `parent`). A `window` closes its package too. No
 * `restrict` line means the default set, `restrict none` an empty one. Blank
 * lines and lines starting with # are skipped. Unknown statements and
 * malformed lines raise [PolicyText.Malformed] with the line number.
 */
object PolicyText {
    class Malformed(val line: Int, val text: String, val problem: TextProblem, val detail: String = "") :
        IllegalArgumentException("line $line: $problem $detail: $text")

    /**
     * Why [policy] cannot be stored, or null when it can: its text must read back
     * as itself, so a policy the parent's editor built (SPEC §8.2) is checked by
     * the same parser as a pasted one. A plan left without stages is the usual
     * case.
     */
    fun problem(policy: Policy): Malformed? = try {
        parse(render(policy))
        null
    } catch (e: Malformed) {
        e
    }

    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")
    private val dayNames = DayOfWeek.entries.associateBy { it.name.take(3).lowercase() }
    private val span = Regex("""^(\d\d:\d\d)-(\d\d:\d\d)$""")

    fun parse(text: String): Policy {
        var mode = Mode.CLOSED
        val plans = mutableListOf<Plan>()
        val closed = linkedSetOf<String>()
        val windows = mutableListOf<Window>()
        val volumes = linkedMapOf<Door, Int>()
        val restrictions = linkedSetOf<Restriction>()
        var restrictionsGiven = false
        var start: String? = null
        val denied = linkedMapOf<String, Set<Capability>>()
        // The plan being read: its line, its days and the stages so far.
        var planLine = 0
        var planDays: Set<DayOfWeek>? = null
        val stages = mutableListOf<Stage>()
        val seenDays = mutableSetOf<DayOfWeek>()

        fun closePlan() {
            val days = planDays ?: return
            if (stages.isEmpty()) throw Malformed(planLine, "plan ${days(days)}", TextProblem.PLAN_WITHOUT_STAGES)
            plans += Plan(days, stages.toList())
            planDays = null
            stages.clear()
        }

        text.lines().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val n = i + 1
            val parts = line.split(Regex("\\s+"))
            if (raw.first().isWhitespace()) {
                if (planDays == null) throw Malformed(n, line, TextProblem.STAGE_OUTSIDE_PLAN)
                stages += stage(n, line, parts)
                return@forEachIndexed
            }
            closePlan()
            when (parts[0]) {
                "mode" -> {
                    if (parts.size != 2) throw Malformed(n, line, TextProblem.MODE)
                    mode = Mode.entries.firstOrNull { it.name.equals(parts[1], ignoreCase = true) }
                        ?: throw Malformed(n, line, TextProblem.MODE)
                }
                "plan" -> {
                    if (parts.size != 2) throw Malformed(n, line, TextProblem.PLAN_DAYS)
                    val days = days(n, line, parts[1])
                    days.firstOrNull { it in seenDays }?.let { throw Malformed(n, line, TextProblem.DAY_REPEATED, dayName(it)) }
                    seenDays += days
                    planDays = days
                    planLine = n
                }
                "close" -> {
                    if (parts.size != 2) throw Malformed(n, line, TextProblem.CLOSE)
                    closed += parts[1]
                }
                "window" -> {
                    if (parts.size != 4) throw Malformed(n, line, TextProblem.WINDOW)
                    val (from, to) = hours(n, line, parts[3])
                    windows += Window(parts[1], days(n, line, parts[2]), from, to)
                    closed += parts[1]
                }
                "permission" -> {
                    if (parts.size != 4 || parts[2] != "deny") throw Malformed(n, line, TextProblem.PERMISSION_SHAPE)
                    val capability = Capability.byKey(parts[3]) ?: throw Malformed(n, line, TextProblem.PERMISSION_KIND)
                    denied[parts[1]] = denied[parts[1]].orEmpty() + capability
                }
                "volume" -> {
                    if (parts.size != 3) throw Malformed(n, line, TextProblem.VOLUME_SHAPE)
                    val door = door(parts[1]) ?: throw Malformed(n, line, TextProblem.UNKNOWN_DOOR, parts[1])
                    val percent = parts[2].removeSuffix("%").toIntOrNull()?.takeIf { it in 0..100 }
                        ?: throw Malformed(n, line, TextProblem.VOLUME_RANGE)
                    volumes[door] = percent
                }
                "start" -> {
                    if (parts.size != 2 || !shinkan.core.net.Address.allowed(parts[1])) {
                        throw Malformed(n, line, TextProblem.START)
                    }
                    start = parts[1]
                }
                "restrict" -> {
                    if (parts.size != 2) throw Malformed(n, line, TextProblem.RESTRICT)
                    restrictionsGiven = true
                    if (parts[1] != "none") {
                        restrictions += Restriction.byName(parts[1]) ?: throw Malformed(n, line, TextProblem.UNKNOWN_RESTRICTION)
                    }
                }
                else -> throw Malformed(n, line, TextProblem.UNKNOWN_STATEMENT)
            }
        }
        closePlan()
        return Policy(mode, plans, closed, windows, volumes, if (restrictionsGiven) restrictions else Restriction.DEFAULT, start, denied)
    }

    private fun stage(n: Int, line: String, parts: List<String>): Stage {
        span.matchEntire(parts[0])?.let {
            val (from, to) = hours(n, line, parts[0])
            val doors = parts.drop(1).map { door(it) ?: throw Malformed(n, line, TextProblem.UNKNOWN_DOOR, it) }.toSet()
            return Stage.Span(from, to, doors)
        }
        val doors = parts.takeWhile { it.toIntOrNull() == null && it != "rest" }
        if (doors.isEmpty()) throw Malformed(n, line, TextProblem.STAGE_SHAPE)
        val doorSet = doors.map { door(it) ?: throw Malformed(n, line, TextProblem.UNKNOWN_DOOR, it) }.toSet()
        val rest = parts.drop(doors.size)
        val minutes = rest.firstOrNull()?.toIntOrNull()?.takeIf { it > 0 }
            ?: throw Malformed(n, line, TextProblem.BUDGET_MINUTES)
        val after = rest.drop(1)
        val restOf = when {
            after.isEmpty() -> null
            after.size == 2 && after[0] == "rest" && after[1] == "parent" -> Rest.Parent
            after.size == 2 && after[0] == "rest" -> Rest.Minutes(
                after[1].toIntOrNull()?.takeIf { it > 0 } ?: throw Malformed(n, line, TextProblem.REST),
            )
            else -> throw Malformed(n, line, TextProblem.AFTER_MINUTES)
        }
        return Stage.Budget(doorSet, minutes, restOf)
    }

    private fun days(n: Int, line: String, text: String): Set<DayOfWeek> =
        text.split(",").map { dayNames[it.lowercase()] ?: throw Malformed(n, line, TextProblem.UNKNOWN_DAY, it) }.toSet()

    private fun hours(n: Int, line: String, text: String): Pair<LocalTime, LocalTime> {
        val parts = text.split("-")
        if (parts.size != 2) throw Malformed(n, line, TextProblem.HOURS)
        val (from, to) = try {
            LocalTime.parse(parts[0], hhmm) to LocalTime.parse(parts[1], hhmm)
        } catch (e: Exception) {
            throw Malformed(n, line, TextProblem.HOURS)
        }
        if (from == to) throw Malformed(n, line, TextProblem.ZERO_HOURS)
        return from to to
    }

    /**
     * A time as a parent types it on the door (SPEC §8.2): `HH:MM`, `H:MM` or
     * `HHMM`, from 00:00 to 23:59; null for anything else.
     */
    fun time(text: String): LocalTime? {
        val m = Regex("""^(\d{1,2}):?(\d{2})$""").matchEntire(text.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (m.groupValues[1].length == 1 && ':' !in text) return null
        return if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
    }

    /** A time as the text writes it, `HH:MM`. */
    fun time(time: LocalTime): String = time.format(hhmm)

    private fun door(name: String): Door? = Door.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun dayName(day: DayOfWeek) = day.name.take(3).lowercase()

    private fun days(days: Set<DayOfWeek>) = DayOfWeek.entries.filter { it in days }.joinToString(",") { dayName(it) }

    private fun doors(doors: Set<Door>) = Door.entries.filter { it in doors }.joinToString(" ") { it.name.lowercase() }

    fun render(policy: Policy): String = buildString {
        appendLine("mode ${policy.mode.name.lowercase()}")
        policy.plans.forEach { plan ->
            appendLine("plan ${days(plan.days)}")
            plan.stages.forEach { appendLine("  ${render(it)}") }
        }
        policy.closed.forEach { pkg ->
            appendLine("close $pkg")
            policy.windows.filter { it.pkg == pkg }.forEach { w ->
                appendLine("window ${w.pkg} ${days(w.days)} ${w.from.format(hhmm)}-${w.to.format(hhmm)}")
            }
        }
        policy.denied.keys.sorted().forEach { pkg ->
            Capability.entries.filter { it in policy.denied.getValue(pkg) }.forEach { appendLine("permission $pkg deny ${it.key}") }
        }
        Door.entries.forEach { door -> policy.volumes[door]?.let { appendLine("volume ${door.name.lowercase()} $it") } }
        policy.start?.let { appendLine("start $it") }
        when {
            policy.restrictions == Restriction.DEFAULT -> Unit
            policy.restrictions.isEmpty() -> appendLine("restrict none")
            else -> policy.restrictions.sortedBy { it.ordinal }.forEach { appendLine("restrict ${it.name.lowercase()}") }
        }
    }

    /**
     * The plan state as the app stores it (SPEC §4.7), one line of `key=value`:
     * `day=2026-11-02 stage=1 spent=1800 rest=2026-11-02T12:00`, with
     * `rest=parent` for a rest a parent ends. Nothing but these is kept.
     */
    fun renderState(state: PlanState): String = buildList {
        state.day?.let { add("day=$it") }
        add("stage=${state.stage}")
        add("spent=${state.spent}")
        when {
            state.restParent -> add("rest=parent")
            state.restEnd != null -> add("rest=${state.restEnd}")
        }
    }.joinToString(" ")

    /** The stored plan state; what cannot be read is a fresh state, which the plan then carries to now. */
    fun parseState(text: String): PlanState {
        val fields = text.trim().split(Regex("\\s+")).filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        return try {
            val rest = fields["rest"]
            PlanState(
                day = fields["day"]?.let(LocalDate::parse),
                stage = fields["stage"]?.toInt() ?: 0,
                spent = fields["spent"]?.toLong() ?: 0,
                restEnd = rest?.takeIf { it != "parent" }?.let(LocalDateTime::parse),
                restParent = rest == "parent",
            )
        } catch (e: Exception) {
            PlanState()
        }
    }

    /** One stage as its indented line shows it, without the indent. */
    fun render(stage: Stage): String = when (stage) {
        is Stage.Span -> listOf("${stage.from.format(hhmm)}-${stage.to.format(hhmm)}", doors(stage.doors))
            .filter { it.isNotEmpty() }.joinToString(" ")
        is Stage.Budget -> buildString {
            append("${doors(stage.doors)} ${stage.minutes}")
            when (val r = stage.rest) {
                null -> Unit
                is Rest.Minutes -> append(" rest ${r.minutes}")
                Rest.Parent -> append(" rest parent")
            }
        }
    }
}
