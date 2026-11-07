package shinkan.core

import java.time.DayOfWeek
import java.time.Duration
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
        val span = spanAt(now, s) ?: return emptySet()
        return span.doors - stopped(span, s)
    }

    /**
     * [state] carried to [now] by the clock alone (SPEC §4.7): the plan day that
     * holds [now], the span under way or else the next one, a later span taking
     * over at its start, and of the running span's budgets what still holds: a
     * rest past its end fills its budget again. A span that starts where the one
     * before it ends, or earlier, takes over that one's use at the seam ([carry]);
     * a gap between spans, or a new plan day, leaves all of it behind. Use is
     * never counted here; that is [count].
     */
    fun resolve(now: LocalDateTime, state: PlanState): PlanState {
        val day = currentDay(now) ?: return PlanState()
        val plan = planFor(day)!!
        val times = plan.spanTimes(day)
        val started = times.indexOfLast { it.first <= now }
        val running = started >= 0 && now < times[started].second
        val at = if (running) started else started + 1
        if (!running || state.day != day || state.span > at) return PlanState(day, at)
        var span = state.span
        var uses = state.uses
        while (span < at) {
            val seam = times[span + 1].first
            // Use runs on only from a span still under way at the next one's start.
            if (uses.isEmpty() || seam > times[span].second) return PlanState(day, at)
            uses = carry(plan.spans[span], settle(plan.spans[span], uses, seam), plan.spans[span + 1], seam)
            span++
        }
        return PlanState(day, at, settle(plan.spans[at], uses, now))
    }

    /**
     * This policy put in place of [old] at [now], with what [state] holds under
     * [old] carried over (SPEC §8.2): the budgets of the span now under way take
     * the use of the old span's budgets that counted the same doors, measured by
     * the new minutes and rests ([carry]). A span that no longer holds [now], or
     * another plan day, starts afresh.
     */
    fun takeOver(old: Policy, now: LocalDateTime, state: PlanState): PlanState {
        val fresh = resolve(now, PlanState())
        val before = old.resolve(now, state)
        if (before.day == null || before.day != fresh.day) return fresh
        val from = old.spanAt(now, before) ?: return fresh
        val to = spanAt(now, fresh) ?: return fresh
        return fresh.copy(uses = carry(from, before.uses, to, now))
    }

    /**
     * [seconds] of use of [door] up to [now], [state] being as it stood when they
     * began (SPEC §4.7): added to the budget of the span that counts the door, the
     * seconds before an edge of the plan to the span of that moment, so none
     * passes from one span into the next. A budget used up ends at the moment it
     * ran out, in its rest, or else its doors stay shut to the span's end.
     */
    fun count(now: LocalDateTime, state: PlanState, door: Door, seconds: Long): PlanState {
        if (seconds <= 0 || plans.isEmpty()) return resolve(now, state)
        var t = now.minusSeconds(seconds)
        // A state of a later span than the seconds' start cannot be walked back: all of them go to now.
        val start = resolve(t, state)
        if (start.day == state.day && start.span < state.span) return countIn(now, state, door, seconds)
        var s = state
        var left = seconds
        while (true) {
            val edge = nextChange(t, s, withBudget = false)?.takeIf { it < now } ?: return countIn(now, s, door, left)
            val part = Duration.between(t, edge).seconds.coerceIn(0, left)
            // The seconds up to an edge belong to the moment before it: at the edge itself the next span holds.
            if (part > 0) s = countIn(edge.minusNanos(1), s, door, part)
            left -= part
            s = resolve(edge, s)
            t = edge
        }
    }

    /** [seconds] of use of [door] ending at [now], all of them in the span of [now]. */
    private fun countIn(now: LocalDateTime, state: PlanState, door: Door, seconds: Long): PlanState {
        val s = resolve(now, state)
        val span = spanAt(now, s) ?: return s
        val i = span.budgets.indexOfFirst { door in it.doors }
        if (i < 0 || seconds <= 0) return s
        val budget = span.budgets[i]
        val use = s.uses[i] ?: Use()
        if (use.resting || use.spent >= budget.seconds) return s
        val total = use.spent + seconds
        if (total < budget.seconds) return s.copy(uses = s.uses + (i to use.copy(spent = total)))
        val ranOut = now.minusSeconds(total - budget.seconds)
        return resolve(now, s.copy(uses = s.uses + (i to spent(budget, ranOut))))
    }

    /** The parent's *Open now* during a rest (SPEC §8.2): the rests running end and their budgets fill again. */
    fun openNow(now: LocalDateTime, state: PlanState): PlanState {
        val s = resolve(now, state)
        return s.copy(uses = s.uses.filterValues { !it.resting })
    }

    /** The budgets of the span under way at [now], each as it stands (SPEC §4.7). */
    fun budgets(now: LocalDateTime, state: PlanState): List<BudgetNow> {
        if (plans.isEmpty()) return emptyList()
        val s = resolve(now, state)
        val span = spanAt(now, s) ?: return emptyList()
        return span.budgets.mapIndexed { i, budget ->
            val use = s.uses[i] ?: Use()
            BudgetNow(budget, (budget.seconds - use.spent).takeIf { it > 0 && !use.resting }, use.restEnd, use.restParent)
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
                planFor(day)!!.spanTimes(day).forEachIndexed { i, t ->
                    if (i >= s.span) { edges += t.first; edges += t.second }
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
     * open device, whose plan speaks of the whole screen, both of them but in a rest
     * or with the budget used up, which cover everything (SPEC §4.1, §4.7).
     */
    fun doorsAt(now: LocalDateTime, state: PlanState = PlanState()): Set<Door> = when (mode) {
        Mode.CLOSED -> openAt(now, state)
        Mode.OPEN -> if (resolve(now, state).resting || depletedAt(now, state)) emptySet() else setOf(Door.BROWSER, Door.PLAYER)
    }

    /**
     * Whether the open device's budget is used up at [now] with no rest to fill it:
     * its apps are greyed to the span's end, the screen not pinned (SPEC §4.7).
     */
    fun depletedAt(now: LocalDateTime, state: PlanState = PlanState()): Boolean =
        mode == Mode.OPEN && budgetOf(Door.DEVICE, now, state)?.depleted == true

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
        val last = planFor(day)?.spanTimes(day)?.maxOfOrNull { it.second } ?: return midnight
        return maxOf(midnight, last)
    }

    /**
     * The use of [from]'s budgets as [to]'s, at [at] (SPEC §4.7, §8.2): each budget
     * of [to] takes the use of the budgets of [from] that counted any of its doors,
     * the most restrictive of them when there are several. Minutes used stay used:
     * short of the new budget, its doors are open with the rest of it; at or past
     * it, the budget is spent, and a rest under way runs on with the new rest's
     * length from its own start, or else the new rest starts at [at]. A door free
     * in [from] starts its budget whole; a door free in [to] runs free.
     */
    private fun carry(from: Span, uses: Map<Int, Use>, to: Span, at: LocalDateTime): Map<Int, Use> {
        val carried = buildMap {
            to.budgets.forEachIndexed { j, budget ->
                val taken = from.budgets.mapIndexedNotNull { i, old ->
                    val use = uses[i] ?: return@mapIndexedNotNull null
                    if (old.doors.none { it in budget.doors }) null else carried(use, old, budget, at)
                }
                taken.maxWithOrNull(compareBy<Use>({ it.restParent }, { it.restEnd ?: LocalDateTime.MIN }, { it.spent }))
                    ?.let { put(j, it) }
            }
        }
        return settle(to, carried, at)
    }

    /** One budget's [use] under [old] as it stands under [new] at [at]; null when nothing of it is left. */
    private fun carried(use: Use, old: Budget, new: Budget, at: LocalDateTime): Use? {
        if (use.spent < new.seconds) return Use(use.spent).takeIf { it.spent > 0 }
        // Spent by the new minutes and not resting yet: settle starts the new rest at [at].
        if (!use.resting) return Use(use.spent)
        return when (val rest = new.rest) {
            null -> Use(new.seconds)
            Rest.Parent -> Use(new.seconds, restParent = true)
            is Rest.Minutes -> {
                val oldEnd = use.restEnd
                val oldMinutes = (old.rest as? Rest.Minutes)?.minutes
                val start = if (oldEnd != null && oldMinutes != null) oldEnd.minusMinutes(oldMinutes.toLong()) else at
                val end = start.plusMinutes(rest.minutes.toLong())
                // A rest the new length has already ended fills the budget.
                if (end <= at) null else Use(new.seconds, end)
            }
        }
    }

    /** The span under way at [now] in [s], which is resolved: null before it starts and between spans. */
    private fun spanAt(now: LocalDateTime, s: PlanState): Span? {
        val day = s.day ?: return null
        val plan = planFor(day) ?: return null
        val span = plan.spans.getOrNull(s.span) ?: return null
        return span.takeIf { now >= plan.spanTimes(day)[s.span].first }
    }

    /** The doors of [span] stopped in [s]: their budget rests, or is used up with no rest to fill it. */
    private fun stopped(span: Span, s: PlanState): Set<Door> =
        span.budgets.filterIndexed { i, budget -> s.uses[i]?.let { it.resting || it.spent >= budget.seconds } == true }
            .flatMap { it.doors }.toSet()

    /** The uses of [span]'s budgets carried to [now]: a rest over fills its budget again, a budget used up rests. */
    private fun settle(span: Span, uses: Map<Int, Use>, now: LocalDateTime): Map<Int, Use> = buildMap {
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

    /** A budget used up at [ranOut]: into its rest, or stopped until the span ends. */
    private fun spent(budget: Budget, ranOut: LocalDateTime): Use = when (val rest = budget.rest) {
        null -> Use(budget.seconds)
        is Rest.Minutes -> Use(budget.seconds, ranOut.plusMinutes(rest.minutes.toLong()))
        Rest.Parent -> Use(budget.seconds, restParent = true)
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

/** One day plan: the weekdays it holds for and its spans, in order (SPEC §4.7). */
data class Plan(val days: Set<DayOfWeek>, val spans: List<Span>) {
    init {
        require(days.isNotEmpty()) { "plan without days" }
    }

    /**
     * The start and end of each span on [day], in order. A span starting before
     * the one ahead of it starts on the next day; one ending at or before its
     * start crosses midnight. Both belong to [day] (SPEC §4.4).
     */
    internal fun spanTimes(day: LocalDate): List<Pair<LocalDateTime, LocalDateTime>> {
        var date = day
        var previous: LocalTime? = null
        return spans.map { span ->
            val before = previous
            if (before != null && span.from < before) date = date.plusDays(1)
            previous = span.from
            val end = if (span.to > span.from) date.atTime(span.to) else date.plusDays(1).atTime(span.to)
            date.atTime(span.from) to end
        }
    }
}

/**
 * A clock period of a plan with the [doors] open in it (SPEC §4.7), each free to
 * the span's end or counted by one of its own [budgets].
 */
data class Span(
    val from: LocalTime,
    val to: LocalTime,
    val doors: Set<Door>,
    val budgets: List<Budget> = emptyList(),
) {
    init {
        require(from != to) { "span of zero length" }
        val counted = budgets.flatMap { it.doors }
        require(counted.all { it in doors }) { "a budget on a door the span keeps closed" }
        require(counted.size == counted.toSet().size) { "a door in two budgets" }
    }
}

/** Minutes of use of the [doors] inside a span, shared between them, and what follows when they are spent. */
data class Budget(val doors: Set<Door>, val minutes: Int, val rest: Rest? = null) {
    init {
        require(doors.isNotEmpty()) { "budget without a door" }
        require(minutes > 0) { "budget of no minutes" }
    }

    val seconds: Long get() = minutes * 60L
}

/**
 * A budget as it stands now (SPEC §4.7): the seconds [left] of it, null when it is
 * not running down; its rest's end on the wall clock, or [restParent] until a
 * parent opens. With neither and nothing left it is [depleted], shut to its span's end.
 */
data class BudgetNow(
    val budget: Budget,
    val left: Long?,
    val restEnd: LocalDateTime?,
    val restParent: Boolean,
) {
    val depleted: Boolean get() = left == null && restEnd == null && !restParent
}

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
 * (SPEC §4.7): the plan [day], the index of the [span] under way or next, and in
 * [uses] the seconds spent of each of its budgets touched and their rests, by
 * index. Nothing else is kept (§2.5).
 */
data class PlanState(
    val day: LocalDate? = null,
    val span: Int = 0,
    val uses: Map<Int, Use> = emptyMap(),
) {
    val resting: Boolean get() = uses.values.any { it.resting }
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
