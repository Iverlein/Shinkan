package shinkan.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * What the device may do, as the parent set it (SPEC §4). Pure data: the app
 * module turns it into DevicePolicyManager calls and pages, this module only
 * decides.
 *
 * On the [Mode.CLOSED] device Shinkan is the home and the [Door]s are open as
 * the day [plans] say (§4.7); every other package is suspended. On the
 * [Mode.OPEN] device the launcher stays, the [closed] packages are suspended
 * outside their [windows], and the plans speak of [Door.DEVICE]. A policy with
 * no plan at all sets no time: every door is open. [volumes] are ceilings in
 * percent of the media stream (§4.6); [start] is the browser's start page.
 */
data class Policy(
    val mode: Mode = Mode.CLOSED,
    val plans: List<Plan> = emptyList(),
    val closed: Set<String> = emptySet(),
    val windows: List<Window> = emptyList(),
    val volumes: Map<Door, Int> = emptyMap(),
    val restrictions: Set<Restriction> = Restriction.DEFAULT,
    /** The browser door's start page (SPEC §7.6), http or https; null opens an empty page with the address bar. */
    val start: String? = null,
    /** What a package is refused whatever it asks (SPEC §4.5), by package. */
    val denied: Map<String, Set<Capability>> = emptyMap(),
) {
    init {
        val days = plans.flatMap { it.days }
        require(days.size == days.toSet().size) { "a day in two plans" }
        require(volumes.values.all { it in 0..100 }) { "a volume outside 0..100" }
    }

    /** The plan for [day]'s weekday, or null when the parent gave that day none. */
    fun planFor(day: LocalDate): Plan? = plans.firstOrNull { day.dayOfWeek in it.days }

    /** The doors open at [now] in [state]: all of them when there is no plan at all (SPEC §4.7). */
    fun openAt(now: LocalDateTime, state: PlanState = PlanState()): Set<Door> {
        if (plans.isEmpty()) return Door.entries.toSet()
        val s = resolve(now, state)
        val day = s.day ?: return emptySet()
        val plan = planFor(day) ?: return emptySet()
        val stage = plan.stages.getOrNull(s.stage) ?: return emptySet()
        return when (stage) {
            is Stage.Span -> {
                val t = plan.spanTimes(day).getValue(s.stage)
                if (now >= t.first && now < t.second) stage.doors - stopped(stage, s) else emptySet()
            }
            is Stage.Budget -> if (s.resting) emptySet() else stage.doors
        }
    }

    /**
     * [state] carried to [now] by the clock alone (SPEC §4.7): the plan day that
     * holds [now] (a new day starts fresh), a span that has started taking over
     * whatever ran, a span past its end giving way to the next stage, a rest in
     * minutes past its end doing the same, and inside a span a rest past its end
     * filling its budget again. Use is never counted here; that is [count].
     */
    fun resolve(now: LocalDateTime, state: PlanState): PlanState {
        val day = currentDay(now) ?: return PlanState()
        val plan = planFor(day)!!
        val spans = plan.spanTimes(day)
        var s = if (state.day == day) state.copy(stage = state.stage.coerceIn(0, plan.stages.size)) else PlanState(day)
        while (true) {
            // A later span that has started takes over whatever runs.
            val jump = spans.filter { (i, t) -> i > s.stage && t.first <= now }.keys.maxOrNull()
            if (jump != null) s = PlanState(day, jump)
            val stage = plan.stages.getOrNull(s.stage) ?: return s.copy(uses = emptyMap())
            when (stage) {
                is Stage.Span -> {
                    if (now >= spans.getValue(s.stage).second) { s = PlanState(day, s.stage + 1); continue }
                    return s.copy(spent = 0, restEnd = null, restParent = false, uses = settle(stage, s.uses, now))
                }
                is Stage.Budget -> {
                    if (s.restParent) return s
                    val end = s.restEnd
                    if (end != null) {
                        if (now >= end) { s = PlanState(day, s.stage + 1); continue }
                        return s
                    }
                    if (s.spent >= stage.seconds) { s = spend(s, stage, now); continue }
                    return s
                }
            }
        }
    }

    /**
     * [seconds] of use of [door] counted at [now] (SPEC §4.7): added to the
     * running budget when it names the door. A budget used up ends at the moment
     * it ran out: inside a span in its rest, or else its doors stay closed to the
     * span's end; as a stage of its own in its rest or in the next stage.
     */
    fun count(now: LocalDateTime, state: PlanState, door: Door, seconds: Long): PlanState {
        val s = resolve(now, state)
        val day = s.day ?: return s
        val plan = planFor(day) ?: return s
        val running = plan.stages.getOrNull(s.stage)
        if (running is Stage.Span) return countInSpan(plan, running, now, s, door, seconds)
        val stage = running as? Stage.Budget ?: return s
        if (s.resting || door !in stage.doors || seconds <= 0) return s
        val total = s.spent + seconds
        if (total < stage.seconds) return s.copy(spent = total)
        val ranOut = now.minusSeconds(total - stage.seconds)
        return resolve(now, spend(s.copy(spent = stage.seconds), stage, ranOut))
    }

    private fun countInSpan(plan: Plan, span: Stage.Span, now: LocalDateTime, s: PlanState, door: Door, seconds: Long): PlanState {
        val i = span.budgets.indexOfFirst { door in it.doors }
        if (i < 0 || seconds <= 0 || now < plan.spanTimes(s.day!!).getValue(s.stage).first) return s
        val budget = span.budgets[i]
        val use = s.uses[i] ?: Use()
        if (use.resting || use.spent >= budget.seconds) return s
        val total = use.spent + seconds
        if (total < budget.seconds) return s.copy(uses = s.uses + (i to use.copy(spent = total)))
        val ranOut = now.minusSeconds(total - budget.seconds)
        return resolve(now, s.copy(uses = s.uses + (i to spent(budget, ranOut))))
    }

    /** The parent's *Open now* during a rest (SPEC §8.2): inside a span the rests end and their budgets fill again; a budget stage's rest gives way to the next stage. */
    fun openNow(now: LocalDateTime, state: PlanState): PlanState {
        val s = resolve(now, state)
        val day = s.day ?: return s
        if (!s.resting) return s
        if (s.uses.values.any { it.resting }) return resolve(now, s.copy(uses = s.uses.filterValues { !it.resting }))
        return resolve(now, PlanState(day, s.stage + 1))
    }

    /** The budgets of what runs at [now]: a budget stage's own, or those of a span under way (SPEC §4.7). */
    fun budgets(now: LocalDateTime, state: PlanState): List<BudgetNow> {
        if (plans.isEmpty()) return emptyList()
        val s = resolve(now, state)
        val day = s.day ?: return emptyList()
        val plan = planFor(day) ?: return emptyList()
        return when (val stage = plan.stages.getOrNull(s.stage)) {
            is Stage.Budget -> listOf(BudgetNow(stage, (stage.seconds - s.spent).takeUnless { s.resting }, s.restEnd, s.restParent))
            is Stage.Span -> {
                if (now < plan.spanTimes(day).getValue(s.stage).first) return emptyList()
                stage.budgets.mapIndexed { i, budget ->
                    val use = s.uses[i] ?: Use()
                    BudgetNow(budget, (budget.seconds - use.spent).takeIf { it > 0 && !use.resting }, use.restEnd, use.restParent)
                }
            }
            null -> emptyList()
        }
    }

    /** The budget that counts [door] at [now], or null when the door runs free or nothing runs. */
    fun budgetOf(door: Door, now: LocalDateTime, state: PlanState): BudgetNow? =
        budgets(now, state).firstOrNull { door in it.budget.doors }

    /** The seconds left at [now] of the budget that counts [door], or of the one nearest its end; null when none runs down. */
    fun budgetLeft(now: LocalDateTime, state: PlanState, door: Door? = null): Long? =
        budgets(now, state).filter { door == null || door in it.budget.doors }.mapNotNull { it.left }.minOrNull()

    /**
     * The next moment after [now] at which [openAt] or [closedPackagesAt] can
     * change, or null when nothing is timed: the next span's start or the
     * current one's end, a rest's end, [now] plus each running budget's
     * remainder, the end of the plan day or the start of the next, and on the
     * open device the closed apps' window edges (SPEC §4.2).
     */
    fun nextChange(now: LocalDateTime, state: PlanState = PlanState()): LocalDateTime? =
        nextChange(now, state, withBudget = true)

    private fun nextChange(now: LocalDateTime, state: PlanState, withBudget: Boolean): LocalDateTime? {
        val edges = mutableListOf<LocalDateTime>()
        if (mode == Mode.OPEN) windows.filter { it.pkg in closed }.forEach { edges += it.edgesAfter(now) }
        if (plans.isNotEmpty()) {
            val s = resolve(now, state)
            val day = s.day
            if (day != null) {
                val plan = planFor(day)!!
                plan.spanTimes(day).forEach { (i, t) ->
                    if (i >= s.stage) { edges += t.first; edges += t.second }
                }
                budgets(now, s).forEach { b ->
                    b.restEnd?.let { edges += it }
                    if (withBudget) b.left?.let { edges += now.plusSeconds(it) }
                }
                edges += dayEnd(day)
            }
            // The start of the next plan day, within the coming week.
            (0L..8L).map { now.toLocalDate().plusDays(it) }
                .firstOrNull { planFor(it) != null && dayStart(it) > now }
                ?.let { edges += dayStart(it) }
        }
        return edges.filter { it > now }.minOrNull()
    }

    /**
     * When [door] next opens after [now] by the clock alone, with no use counted,
     * or null when not within a week; [now] itself when it is open. What the home
     * shows on a greyed door (SPEC §8.1).
     */
    fun nextOpening(door: Door, now: LocalDateTime, state: PlanState = PlanState()): LocalDateTime? {
        var t = now
        var s = resolve(now, state)
        val limit = now.plusDays(8)
        while (t < limit) {
            if (door in openAt(t, s)) return t
            t = nextChange(t, s, withBudget = false) ?: return null
            s = resolve(t, s)
        }
        return null
    }

    /** On the open device, the closed packages outside every window of theirs at [now] (SPEC §4.2). */
    fun closedPackagesAt(now: LocalDateTime): Set<String> =
        closed.filter { pkg -> windows.none { it.pkg == pkg && it.contains(now) } }.toSet()

    /**
     * The doors usable at [now]: on the closed device those the plan opens; on the
     * open device, whose plan speaks of the whole screen, both of them but in a rest,
     * which covers everything (SPEC §4.1, §4.7).
     */
    fun doorsAt(now: LocalDateTime, state: PlanState = PlanState()): Set<Door> = when (mode) {
        Mode.CLOSED -> openAt(now, state)
        Mode.OPEN -> if (resolve(now, state).resting) emptySet() else setOf(Door.BROWSER, Door.PLAYER)
    }

    /** Whether Shinkan holds the screen in lock task at [now]: always on the closed device, on the open one in a rest (SPEC §4.1, §4.7). */
    fun pinnedAt(now: LocalDateTime, state: PlanState = PlanState()): Boolean =
        mode == Mode.CLOSED || resolve(now, state).resting

    /** This policy with [capability] of [pkg] refused or not; a package left with nothing is dropped. */
    fun denying(pkg: String, capability: Capability, deny: Boolean): Policy {
        val now = denied[pkg].orEmpty().let { if (deny) it + capability else it - capability }
        return copy(denied = if (now.isEmpty()) denied - pkg else denied + (pkg to now))
    }

    /** The ceiling of the whole device in percent (SPEC §4.6): the open device's alone, a `volume device` line means nothing on the closed one. */
    val deviceCeiling: Int? get() = volumes[Door.DEVICE].takeIf { mode == Mode.OPEN }

    /** The apps closed at [now], sorted, each with when it next opens: what the open device's home lists (SPEC §8.1). */
    fun closedApps(now: LocalDateTime): List<ClosedApp> =
        closedPackagesAt(now).sorted().map { pkg ->
            ClosedApp(pkg, windows.filter { it.pkg == pkg }.mapNotNull { it.openingAfter(now) }.minOrNull())
        }

    /** The plan day holding [now]: its own date, or the day before while a span of that day crosses midnight. */
    private fun currentDay(now: LocalDateTime): LocalDate? {
        val today = now.toLocalDate()
        for (day in listOf(today, today.minusDays(1))) {
            if (planFor(day) != null && now >= dayStart(day) && now < dayEnd(day)) return day
        }
        return null
    }

    /** A plan day starts at midnight, or later when the day before still runs a span past it. */
    private fun dayStart(day: LocalDate): LocalDateTime {
        val midnight = day.atStartOfDay()
        val before = day.minusDays(1)
        if (planFor(before) == null) return midnight
        return maxOf(midnight, dayEnd(before))
    }

    /** A plan day ends at the next midnight, or at the end of its last span when that is later. */
    private fun dayEnd(day: LocalDate): LocalDateTime {
        val midnight = day.plusDays(1).atStartOfDay()
        val last = planFor(day)?.spanTimes(day)?.values?.maxOfOrNull { it.second } ?: return midnight
        return maxOf(midnight, last)
    }

    /** The doors of [span] stopped in [s]: their budget rests, or is used up with no rest to fill it. */
    private fun stopped(span: Stage.Span, s: PlanState): Set<Door> =
        span.budgets.filterIndexed { i, budget -> s.uses[i]?.let { it.resting || it.spent >= budget.seconds } == true }
            .flatMap { it.doors }.toSet()

    /** The uses of [span]'s budgets carried to [now]: a rest over fills its budget again, a budget used up rests. */
    private fun settle(span: Stage.Span, uses: Map<Int, Use>, now: LocalDateTime): Map<Int, Use> = buildMap {
        uses.forEach { (i, use) ->
            val budget = span.budgets.getOrNull(i) ?: return@forEach
            val end = use.restEnd
            when {
                use.restParent -> put(i, use)
                end != null -> if (now < end) put(i, use)
                use.spent >= budget.seconds -> put(i, spent(budget, now))
                else -> put(i, use)
            }
        }
    }

    /** A budget of a span used up at [ranOut]: into its rest, or stopped until the span ends. */
    private fun spent(budget: Stage.Budget, ranOut: LocalDateTime): Use = when (val rest = budget.rest) {
        null -> Use(budget.seconds)
        is Rest.Minutes -> Use(budget.seconds, ranOut.plusMinutes(rest.minutes.toLong()))
        Rest.Parent -> Use(budget.seconds, restParent = true)
    }

    private fun spend(s: PlanState, stage: Stage.Budget, ranOut: LocalDateTime): PlanState =
        when (val rest = stage.rest) {
            null -> PlanState(s.day, s.stage + 1)
            is Rest.Minutes -> s.copy(spent = stage.seconds, restEnd = ranOut.plusMinutes(rest.minutes.toLong()))
            Rest.Parent -> s.copy(spent = stage.seconds, restParent = true)
        }
}

/** What the parent may refuse a usable app (SPEC §4.5), with the runtime permissions it stands for. */
enum class Capability(val key: String, val permissions: List<String>) {
    CAMERA("camera", listOf("android.permission.CAMERA")),
    MICROPHONE("microphone", listOf("android.permission.RECORD_AUDIO")),
    LOCATION(
        "location",
        listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
        ),
    ),
    ;

    companion object {
        fun byKey(key: String): Capability? = entries.firstOrNull { it.key == key }
    }
}

/** A package closed now, and the next start of a window of its within a week, or null when it has none. */
data class ClosedApp(val pkg: String, val opens: LocalDateTime?)

/** The two forms of the hold (SPEC §4.1). */
enum class Mode { CLOSED, OPEN }

/** Shinkan's two doors on the closed device, and the whole screen of the open one (SPEC §4.4). */
enum class Door { BROWSER, PLAYER, DEVICE }

/** One day plan: the weekdays it holds for and its stages, in order (SPEC §4.7). */
data class Plan(val days: Set<DayOfWeek>, val stages: List<Stage>) {
    init {
        require(days.isNotEmpty()) { "plan without days" }
    }

    /**
     * The start and end of each span on [day], by stage index. A span starting
     * before the one ahead of it starts on the next day; one ending at or before
     * its start crosses midnight. Both belong to [day] (SPEC §4.4).
     */
    internal fun spanTimes(day: LocalDate): Map<Int, Pair<LocalDateTime, LocalDateTime>> {
        val times = linkedMapOf<Int, Pair<LocalDateTime, LocalDateTime>>()
        var date = day
        var previous: LocalTime? = null
        stages.forEachIndexed { i, stage ->
            if (stage !is Stage.Span) return@forEachIndexed
            val before = previous
            if (before != null && stage.from < before) date = date.plusDays(1)
            val start = date.atTime(stage.from)
            val end = if (stage.to > stage.from) date.atTime(stage.to) else date.plusDays(1).atTime(stage.to)
            times[i] = start to end
            previous = stage.from
        }
        return times
    }
}

/**
 * A stage of a plan (SPEC §4.7): a clock span with the doors open in it, each
 * free or counted by one of the span's own [Span.budgets]; or, as 1.0 wrote it,
 * a budget of minutes of use standing as a stage of its own.
 */
sealed interface Stage {
    val doors: Set<Door>

    data class Span(
        val from: LocalTime,
        val to: LocalTime,
        override val doors: Set<Door>,
        val budgets: List<Budget> = emptyList(),
    ) : Stage {
        init {
            require(from != to) { "span of zero length" }
            val counted = budgets.flatMap { it.doors }
            require(counted.all { it in doors }) { "a budget on a door the span keeps closed" }
            require(counted.size == counted.toSet().size) { "a door in two budgets" }
        }
    }

    data class Budget(override val doors: Set<Door>, val minutes: Int, val rest: Rest? = null) : Stage {
        init {
            require(doors.isNotEmpty()) { "budget without a door" }
            require(minutes > 0) { "budget of no minutes" }
        }

        val seconds: Long get() = minutes * 60L
    }
}

/**
 * A budget as it stands now (SPEC §4.7): the seconds [left] of it, null when it is
 * not running down; its rest's end on the wall clock, or [restParent] until a
 * parent opens. A budget with neither and nothing left stays shut to its span's end.
 */
data class BudgetNow(
    val budget: Stage.Budget,
    val left: Long?,
    val restEnd: LocalDateTime?,
    val restParent: Boolean,
)

/** What follows a spent budget: minutes on the wall clock, or until a parent opens the door. */
sealed interface Rest {
    data class Minutes(val minutes: Int) : Rest {
        init {
            require(minutes > 0) { "rest of no minutes" }
        }
    }

    data object Parent : Rest
}

/**
 * Where the plan stands, as the app keeps it in device-protected storage
 * (SPEC §4.7): the plan [day], the [stage] index in it, the seconds [spent] of
 * a budget stage, and a running rest: its [restEnd] on the wall clock, or
 * [restParent] until a parent opens; for a span, [uses] holds the same of each
 * of its budgets, by index, and only of those touched. Nothing else is kept (§2.5).
 */
data class PlanState(
    val day: LocalDate? = null,
    val stage: Int = 0,
    val spent: Long = 0,
    val restEnd: LocalDateTime? = null,
    val restParent: Boolean = false,
    val uses: Map<Int, Use> = emptyMap(),
) {
    val resting: Boolean get() = restParent || restEnd != null || uses.values.any { it.resting }
}

/** One budget of the running span: the seconds [spent] since it last filled, and its rest. */
data class Use(
    val spent: Long = 0,
    val restEnd: LocalDateTime? = null,
    val restParent: Boolean = false,
) {
    val resting: Boolean get() = restParent || restEnd != null
}

/** One span of hours on given weekdays in which the closed package [pkg] is usable. A span may cross midnight. */
data class Window(
    val pkg: String,
    val days: Set<DayOfWeek>,
    val from: LocalTime,
    val to: LocalTime,
) {
    init {
        require(pkg.isNotBlank()) { "window without a package" }
        require(days.isNotEmpty()) { "window without days" }
        require(from != to) { "window of zero length" }
    }

    private val overnight get() = to < from

    fun contains(now: LocalDateTime): Boolean {
        val t = now.toLocalTime()
        return if (!overnight) {
            now.dayOfWeek in days && t >= from && t < to
        } else {
            (now.dayOfWeek in days && t >= from) || (now.dayOfWeek.minus(1) in days && t < to)
        }
    }

    /** The window's next start after [now], within a week. */
    internal fun openingAfter(now: LocalDateTime): LocalDateTime? =
        (0L..7L).map { now.toLocalDate().plusDays(it) }.filter { it.dayOfWeek in days }.map { it.atTime(from) }.firstOrNull { it > now }

    /** The window's opening and closing moments in the week after [now]. */
    internal fun edgesAfter(now: LocalDateTime): List<LocalDateTime> {
        val edges = mutableListOf<LocalDateTime>()
        for (offset in -1L..7L) {
            val day = now.toLocalDate().plusDays(offset)
            if (day.dayOfWeek !in days) continue
            edges += day.atTime(from)
            edges += if (overnight) day.plusDays(1).atTime(to) else day.atTime(to)
        }
        return edges.filter { it > now }.sorted()
    }
}

/**
 * A user restriction the device owner sets (SPEC §4.3), by the constant's
 * name in android.os.UserManager so the app module needs no table of its own.
 */
enum class Restriction(val key: String) {
    FACTORY_RESET("no_factory_reset"),
    SAFE_BOOT("no_safe_boot"),
    ADD_USER("no_add_user"),
    INSTALL_APPS("no_install_apps"),
    INSTALL_UNKNOWN_SOURCES("no_install_unknown_sources"),
    UNINSTALL_APPS("no_uninstall_apps"),
    MODIFY_ACCOUNTS("no_modify_accounts"),
    CONFIG_DATE_TIME("no_config_date_time"),
    DEBUGGING_FEATURES("no_debugging_features"),
    USB_FILE_TRANSFER("no_usb_file_transfer"),
    CONFIG_CREDENTIALS("no_config_credentials"),
    CONFIG_PRIVATE_DNS("disallow_config_private_dns"),
    CONFIG_VPN("no_config_vpn"),
    ;

    companion object {
        /** What a freshly held device gets: everything a child could use to slip the hold, nothing a parent needs for daily use. */
        val DEFAULT: Set<Restriction> = setOf(
            FACTORY_RESET, SAFE_BOOT, ADD_USER, INSTALL_APPS, INSTALL_UNKNOWN_SOURCES,
            UNINSTALL_APPS, MODIFY_ACCOUNTS, CONFIG_DATE_TIME, DEBUGGING_FEATURES, CONFIG_PRIVATE_DNS, CONFIG_VPN,
        )

        fun byName(name: String): Restriction? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
