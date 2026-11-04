package shinkan.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.LocalDateTime
import java.time.LocalTime

/** The policy text of SPEC §4.4 and the open device's closed apps. */
class PolicyTest {
    /** The first example of SPEC §4.4, as it is written there. */
    private val closedExample = """
        mode closed
        plan mon,tue,wed,thu,fri
          06:00-09:00 player
          10:00-19:00 browser player 60 rest 30
          19:00-21:00 browser,player 45
        plan sat,sun
          08:00-12:00 browser player
          14:00-20:00 browser player 60 rest parent
        volume browser 60
        volume player 40
        restrict factory_reset
    """.trimIndent() + "\n"

    /** The second example of SPEC §4.4. */
    private val openExample = """
        mode open
        plan mon,tue,wed,thu,fri
          07:00-09:00 device
          16:00-21:00 device 60 rest 30
        close com.example.game
        window com.example.game sat,sun 10:00-12:00
        close com.zhiliaoapp.musically
        volume device 70
    """.trimIndent() + "\n"

    // 2026-11-02 is a Monday.
    private fun at(day: Int, time: String) = LocalDateTime.of(2026, 11, day, 0, 0).with(LocalTime.parse(time))

    @Test fun `both examples of the spec render back identically`() {
        assertEquals(closedExample, PolicyText.render(PolicyText.parse(closedExample)))
        assertEquals(openExample, PolicyText.render(PolicyText.parse(openExample)))
    }

    @Test fun `the closed example reads as the spec says`() {
        val p = PolicyText.parse(closedExample)
        assertEquals(Mode.CLOSED, p.mode)
        assertEquals(2, p.plans.size)
        assertEquals(
            listOf(
                Span(LocalTime.of(6, 0), LocalTime.of(9, 0), setOf(Door.PLAYER)),
                Span(
                    LocalTime.of(10, 0), LocalTime.of(19, 0), setOf(Door.BROWSER, Door.PLAYER),
                    listOf(Budget(setOf(Door.PLAYER), 60, Rest.Minutes(30))),
                ),
                Span(
                    LocalTime.of(19, 0), LocalTime.of(21, 0), setOf(Door.BROWSER, Door.PLAYER),
                    listOf(Budget(setOf(Door.BROWSER, Door.PLAYER), 45)),
                ),
            ),
            p.plans[0].spans,
        )
        assertEquals(Rest.Parent, p.plans[1].spans[1].budgets[0].rest)
        assertEquals(mapOf(Door.BROWSER to 60, Door.PLAYER to 40), p.volumes)
        assertEquals(setOf(Restriction.FACTORY_RESET), p.restrictions)
    }

    @Test fun `a span's budgets are written back as read`() {
        val text = "mode closed\nplan mon\n  21:00-22:00 browser 10 rest parent player 20\n"
        val p = PolicyText.parse(text)
        assertEquals(
            listOf(Budget(setOf(Door.BROWSER), 10, Rest.Parent), Budget(setOf(Door.PLAYER), 20)),
            p.plans[0].spans[0].budgets,
        )
        assertEquals(text, PolicyText.render(p))
    }

    private fun lines(text: String) = PolicyText.render(PolicyText.parse(text)).lines().filter { it.startsWith("  ") }.map { it.trim() }

    @Test fun `a budget with no hours becomes the span it ran in`() {
        assertEquals(
            listOf("06:00-10:00 browser player", "10:00-00:00 player 30 browser 120"),
            lines("plan mon\n  06:00-10:00 browser player\n  player 30 rest 90\n  browser 120"),
        )
        assertEquals(listOf("00:00-23:59 player 60 rest parent"), lines("plan sat\n  player 60 rest parent"))
        assertEquals(
            listOf("00:00-16:00 player 30", "16:00-18:00 browser"),
            lines("plan mon\n  player 30 rest 5\n  16:00-18:00 browser"),
        )
    }

    @Test fun `a budget with no hours reaches the next day's span across midnight`() {
        assertEquals(
            listOf("18:00-20:00 player", "20:00-06:00 browser 30", "06:00-07:00 player"),
            lines("plan mon\n  18:00-20:00 player\n  browser 30\n  06:00-07:00 player"),
        )
    }

    @Test fun `what never ran is not opened by the conversion`() {
        // A span across midnight ended the plan day: what stood after it never ran.
        assertEquals(listOf("22:00-01:00 player"), lines("plan mon\n  22:00-01:00 player\n  browser 30"))
        // No time was left between two spans.
        assertEquals(
            listOf("06:00-10:00 player", "10:00-12:00 player"),
            lines("plan mon\n  06:00-10:00 player\n  browser 30\n  10:00-12:00 player"),
        )
    }

    @Test fun `a door in two budgets with no hours stays in the first`() {
        assertEquals(listOf("00:00-23:59 player 30 browser 60"), lines("plan mon\n  player 30\n  browser player 60"))
        assertEquals(listOf("00:00-23:59 player 30"), lines("plan mon\n  player 30\n  player 60"))
    }

    @Test fun `what the conversion writes reads back as itself`() {
        val once = PolicyText.render(PolicyText.parse("plan mon\n  06:00-10:00 browser\n  player 30 rest parent"))
        assertEquals(once, PolicyText.render(PolicyText.parse(once)))
    }

    @Test fun `a span's budget refuses what it cannot mean`() {
        fun problem(line: String) = assertThrows(PolicyText.Malformed::class.java) { PolicyText.parse("plan mon\n  $line") }.problem
        assertEquals(TextProblem.DOOR_REPEATED, problem("10:00-12:00 player player 60"))
        assertEquals(TextProblem.DOOR_REPEATED, problem("10:00-12:00 player 60 browser,player 30"))
        assertEquals(TextProblem.BUDGET_MINUTES, problem("10:00-12:00 player 0"))
        assertEquals(TextProblem.BUDGET_MINUTES, problem("10:00-12:00 player rest 30"))
        assertEquals(TextProblem.BUDGET_MINUTES, problem("10:00-12:00 browser,player"))
        assertEquals(TextProblem.REST, problem("10:00-12:00 player 60 rest"))
        assertEquals(TextProblem.REST, problem("10:00-12:00 player 60 rest soon"))
        assertEquals(TextProblem.UNKNOWN_DOOR, problem("10:00-12:00 player 60 tv"))
    }

    @Test fun `restrictions default when none are written, none is none`() {
        assertEquals(Restriction.DEFAULT, PolicyText.parse("mode closed").restrictions)
        assertEquals(emptySet<Restriction>(), PolicyText.parse("restrict none").restrictions)
        assertEquals("mode closed\nrestrict none\n", PolicyText.render(Policy(restrictions = emptySet())))
        assertEquals("mode closed\n", PolicyText.render(Policy()))
    }

    @Test fun `a window closes its package`() {
        val p = PolicyText.parse("mode open\nwindow com.example.radio fri 22:00-01:00")
        assertEquals(setOf("com.example.radio"), p.closed)
        assertEquals("mode open\nclose com.example.radio\nwindow com.example.radio fri 22:00-01:00\n", PolicyText.render(p))
    }

    @Test fun `an open policy closes a package outside its windows and never a door`() {
        val p = PolicyText.parse(openExample)
        assertEquals(setOf("com.example.game", "com.zhiliaoapp.musically"), p.closedPackagesAt(at(2, "10:30")))
        assertEquals(setOf("com.zhiliaoapp.musically"), p.closedPackagesAt(at(7, "10:30")))
        assertEquals(setOf("com.example.game", "com.zhiliaoapp.musically"), p.closedPackagesAt(at(7, "12:00")))
        assertEquals(setOf(Door.DEVICE), p.openAt(at(2, "08:00")))
        val noPlan = PolicyText.parse("mode open\nclose com.example.game")
        assertEquals(Door.entries.toSet(), noPlan.openAt(at(2, "03:00")))
        assertEquals(setOf("com.example.game"), noPlan.closedPackagesAt(at(2, "03:00")))
    }

    @Test fun `the window edges are the open device's next changes`() {
        val p = PolicyText.parse("mode open\nwindow com.example.game sat,sun 10:00-12:00")
        assertEquals(at(7, "10:00"), p.nextChange(at(2, "12:00")))
        assertEquals(at(7, "12:00"), p.nextChange(at(7, "10:00")))
        assertEquals(null, PolicyText.parse("mode open").nextChange(at(2, "12:00")))
        // On the closed device the closed apps play no part.
        assertEquals(null, PolicyText.parse("mode closed\nwindow com.example.game sat 10:00-12:00").nextChange(at(2, "12:00")))
    }

    @Test fun `an overnight window belongs to the day it opens`() {
        val w = Window("com.example.radio", setOf(FRIDAY), LocalTime.of(22, 0), LocalTime.of(1, 0))
        assertTrue(w.contains(at(6, "23:00")))
        assertTrue(w.contains(at(7, "00:30")))
        assertEquals(false, w.contains(at(7, "01:00")))
        assertEquals(false, w.contains(at(5, "23:00")))
        assertEquals(listOf(at(6, "22:00"), at(7, "01:00")), w.edgesAfter(at(2, "12:00")).take(2))
        assertEquals(at(7, "01:00"), w.edgesAfter(at(7, "00:30")).first())
    }

    @Test fun `malformed lines name their line`() {
        fun line(text: String) = assertThrows(PolicyText.Malformed::class.java) { PolicyText.parse(text) }.line
        assertEquals(2, line("mode closed\nwindow a.b mon 25:00-26:00"))
        assertEquals(1, line("allow a.b"))
        assertEquals(1, line("restrict everything"))
        assertEquals(1, line("mode shut"))
        assertEquals(2, line("mode closed\n  player 30"))
        assertEquals(2, line("plan mon\n  player"))
        assertEquals(2, line("plan mon\n  player 30 rest"))
        assertEquals(2, line("plan mon\n  player 30 nap 5"))
        assertEquals(2, line("plan mon\n  tv 30"))
        assertEquals(2, line("plan mon\n  10:00-10:00 player"))
        assertEquals(3, line("plan mon\n  player 30\nplan sun,mon\n  browser 30"))
        assertEquals(1, line("plan mon\nmode open"))
        assertEquals(1, line("volume player 120"))
        assertEquals(1, line("volume radio 20"))
    }

    @Test fun `days of a plan are read in any order and written in the week's`() {
        val p = PolicyText.parse("plan sun,sat\n  10:00-12:00 player 60")
        assertEquals(setOf(SATURDAY, SUNDAY), p.plans[0].days)
        assertEquals("mode closed\nplan sat,sun\n  10:00-12:00 player 60\n", PolicyText.render(p))
    }

    @Test fun `a policy built in the editor is checked by its own text`() {
        val good = Policy(
            plans = listOf(Plan(setOf(DayOfWeek.MONDAY), listOf(whole(Budget(setOf(Door.PLAYER), 30, Rest.Parent))))),
            volumes = mapOf(Door.PLAYER to 40),
        )
        assertEquals(null, PolicyText.problem(good))
        val empty = good.copy(plans = listOf(Plan(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), emptyList())))
        val problem = PolicyText.problem(empty)!!
        assertEquals(2, problem.line)
        assertEquals(TextProblem.PLAN_WITHOUT_STAGES, problem.problem)
        assertEquals("plan mon,fri", problem.text)
    }

    @Test fun `a typed time is HH colon MM, H colon MM or HHMM`() {
        assertEquals(LocalTime.of(6, 0), PolicyText.time("06:00"))
        assertEquals(LocalTime.of(6, 0), PolicyText.time("6:00"))
        assertEquals(LocalTime.of(6, 0), PolicyText.time("0600"))
        assertEquals(LocalTime.of(23, 59), PolicyText.time(" 23:59 "))
        assertEquals(null, PolicyText.time("24:00"))
        assertEquals(null, PolicyText.time("12:60"))
        assertEquals(null, PolicyText.time("600"))
        assertEquals(null, PolicyText.time("noon"))
        assertEquals("06:00", PolicyText.time(LocalTime.of(6, 0)))
    }

    @Test fun `the browser's start page is one http or https address`() {
        val text = "mode closed\nvolume browser 60\nstart https://www.wikipedia.org\n"
        val policy = PolicyText.parse(text)
        assertEquals("https://www.wikipedia.org", policy.start)
        assertEquals(text, PolicyText.render(policy))
        assertThrows(PolicyText.Malformed::class.java) { PolicyText.parse("start file:///sdcard/x.html") }
        assertThrows(PolicyText.Malformed::class.java) { PolicyText.parse("start") }
    }

    private val game = "com.example.game"

    /** [budget] in a span of the whole day. */
    private fun whole(budget: Budget) = Span(LocalTime.MIDNIGHT, LocalTime.of(23, 59), budget.doors, listOf(budget))

    private fun openWith(vararg windows: Window) =
        Policy(mode = Mode.OPEN, closed = setOf(game, "com.example.other"), windows = windows.toList())

    @Test fun `a closed app says when its next window starts`() {
        val policy = openWith(
            Window(game, setOf(DayOfWeek.SATURDAY), LocalTime.of(10, 0), LocalTime.of(12, 0)),
            Window(game, setOf(DayOfWeek.WEDNESDAY), LocalTime.of(16, 0), LocalTime.of(17, 0)),
        )
        // 2026-11-04 is a Wednesday.
        val apps = policy.closedApps(LocalDateTime.of(2026, 11, 4, 15, 0))
        assertEquals(
            listOf(ClosedApp(game, LocalDateTime.of(2026, 11, 4, 16, 0)), ClosedApp("com.example.other", null)),
            apps,
        )
    }

    @Test fun `past today's window the next one is another day's`() {
        val policy = openWith(Window(game, setOf(DayOfWeek.WEDNESDAY), LocalTime.of(16, 0), LocalTime.of(17, 0)))
        assertEquals(
            LocalDateTime.of(2026, 11, 11, 16, 0),
            policy.closedApps(LocalDateTime.of(2026, 11, 4, 18, 0)).first { it.pkg == game }.opens,
        )
    }

    @Test fun `an app inside its window is not listed`() {
        val policy = openWith(Window(game, setOf(DayOfWeek.WEDNESDAY), LocalTime.of(22, 0), LocalTime.of(6, 0)))
        // Thursday before six: the window that crossed midnight still holds.
        assertEquals(listOf("com.example.other"), policy.closedApps(LocalDateTime.of(2026, 11, 5, 5, 0)).map { it.pkg })
    }

    @Test fun `the doors of the open device stand open but in a rest`() {
        val plan = Plan(DayOfWeek.entries.toSet(), listOf(whole(Budget(setOf(Door.DEVICE), 1, Rest.Minutes(5)))))
        val policy = Policy(mode = Mode.OPEN, plans = listOf(plan))
        val now = LocalDateTime.of(2026, 11, 4, 15, 0)
        val doors = setOf(Door.BROWSER, Door.PLAYER)
        assertEquals(doors, policy.doorsAt(now))
        val resting = policy.count(now, PlanState(), Door.DEVICE, 60)
        assertEquals(emptySet<Door>(), policy.doorsAt(now, resting))
        assertEquals(doors, policy.doorsAt(now.plusMinutes(5), resting))
    }

    @Test fun `the open device is pinned for its rest alone`() {
        val plan = Plan(DayOfWeek.entries.toSet(), listOf(whole(Budget(setOf(Door.DEVICE), 1, Rest.Minutes(5)))))
        val policy = Policy(mode = Mode.OPEN, plans = listOf(plan))
        val now = LocalDateTime.of(2026, 11, 4, 15, 0)
        assertFalse(policy.pinnedAt(now))
        val resting = policy.count(now, PlanState(), Door.DEVICE, 60)
        assertTrue(policy.pinnedAt(now, resting))
        assertTrue(policy.pinnedAt(now.plusMinutes(4), resting))
        assertFalse(policy.pinnedAt(now.plusMinutes(5), resting))
        assertFalse(policy.pinnedAt(now, policy.openNow(now, resting)))
    }

    @Test fun `a rest the parent ends stays pinned`() {
        val plan = Plan(DayOfWeek.entries.toSet(), listOf(whole(Budget(setOf(Door.DEVICE), 1, Rest.Parent))))
        val policy = Policy(mode = Mode.OPEN, plans = listOf(plan))
        val now = LocalDateTime.of(2026, 11, 4, 15, 0)
        val resting = policy.count(now, PlanState(), Door.DEVICE, 60)
        assertTrue(policy.pinnedAt(now.plusHours(3), resting))
    }

    @Test fun `the device ceiling is the open device's alone`() {
        val volumes = mapOf(Door.DEVICE to 30, Door.PLAYER to 20)
        assertEquals(30, Policy(mode = Mode.OPEN, volumes = volumes).deviceCeiling)
        assertEquals(null, Policy(mode = Mode.CLOSED, volumes = volumes).deviceCeiling)
        assertEquals(null, Policy(mode = Mode.OPEN).deviceCeiling)
    }

    @Test fun `permission lines read back as they were written`() {
        val text = "mode open\nclose com.b\npermission com.a deny camera\npermission com.a deny location\npermission com.b deny microphone\nvolume device 70\n"
        val policy = PolicyText.parse(text)
        assertEquals(mapOf("com.a" to setOf(Capability.CAMERA, Capability.LOCATION), "com.b" to setOf(Capability.MICROPHONE)), policy.denied)
        assertEquals(text, PolicyText.render(policy))
    }

    @Test fun `a word the parser does not know comes back as it was typed`() {
        val door = assertThrows(PolicyText.Malformed::class.java) { PolicyText.parse("volume attic 40") }
        assertEquals(TextProblem.UNKNOWN_DOOR, door.problem)
        assertEquals("attic", door.detail)
        val day = assertThrows(PolicyText.Malformed::class.java) { PolicyText.parse("plan someday") }
        assertEquals(TextProblem.UNKNOWN_DAY, day.problem)
        assertEquals("someday", day.detail)
    }

    @Test fun `a wrong permission line names its line`() {
        for (line in listOf("permission com.a deny contacts", "permission com.a allow camera", "permission com.a deny", "permission")) {
            val e = org.junit.jupiter.api.Assertions.assertThrows(PolicyText.Malformed::class.java) { PolicyText.parse("mode open\n$line") }
            assertEquals(2, e.line)
        }
    }

    @Test fun `a denial is added and taken off one at a time`() {
        val one = Policy().denying("com.a", Capability.CAMERA, true)
        val two = one.denying("com.a", Capability.LOCATION, true)
        assertEquals(setOf(Capability.CAMERA, Capability.LOCATION), two.denied["com.a"])
        assertEquals(one, two.denying("com.a", Capability.LOCATION, false))
        assertEquals(emptyMap<String, Set<Capability>>(), one.denying("com.a", Capability.CAMERA, false).denied)
    }

    @Test fun `location stands for all its permissions`() {
        assertEquals(3, Capability.LOCATION.permissions.size)
        assertTrue("android.permission.ACCESS_BACKGROUND_LOCATION" in Capability.LOCATION.permissions)
    }

    @Test fun `the open device's budget used up with no rest greys its doors and pins nothing`() {
        val policy = PolicyText.parse("mode open\nplan mon\n  10:00-19:00 device 60\n  20:00-21:00 device")
        val start = at(2, "10:00")
        val spent = policy.count(start.plusHours(1), policy.resolve(start, PlanState()), Door.DEVICE, 3600)
        assertFalse(policy.depletedAt(start.plusMinutes(59), policy.resolve(start, PlanState())))
        assertTrue(policy.depletedAt(at(2, "11:00"), spent))
        assertFalse(policy.pinnedAt(at(2, "11:00"), spent))
        assertEquals(emptySet<Door>(), policy.doorsAt(at(2, "18:59"), spent))
        assertFalse(policy.depletedAt(at(2, "19:00"), spent))
        assertEquals(setOf(Door.BROWSER, Door.PLAYER), policy.doorsAt(at(2, "20:00"), spent))
        assertEquals(at(2, "19:00"), policy.nextChange(at(2, "11:00"), spent))
    }

    @Test fun `a door of the closed device with its budget used up is depleted, one in a rest is not`() {
        val policy = PolicyText.parse("plan mon\n  10:00-19:00 browser 1 player 1 rest 1")
        var s = policy.count(at(2, "10:01"), policy.resolve(at(2, "10:00"), PlanState()), Door.BROWSER, 60)
        s = policy.count(at(2, "10:02"), s, Door.PLAYER, 60)
        assertEquals(true, policy.budgetOf(Door.BROWSER, at(2, "10:02"), s)?.depleted)
        assertEquals(false, policy.budgetOf(Door.PLAYER, at(2, "10:02"), s)?.depleted)
        assertFalse(policy.depletedAt(at(2, "10:02"), s))
    }

    @Test fun `the closed device is always pinned`() {
        assertTrue(Policy().pinnedAt(LocalDateTime.of(2026, 11, 4, 15, 0)))
    }

    @Test fun `the doors of the closed device are the plan's`() {
        val plan = Plan(DayOfWeek.entries.toSet(), listOf(whole(Budget(setOf(Door.PLAYER), 1))))
        assertEquals(setOf(Door.PLAYER), Policy(plans = listOf(plan)).doorsAt(LocalDateTime.of(2026, 11, 4, 15, 0)))
    }
}
