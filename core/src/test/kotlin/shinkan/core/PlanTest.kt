package shinkan.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** The day plan of SPEC §4.7: spans, budgets, rests, and the edges between them. */
class PlanTest {
    private val policy = PolicyText.parse(
        """
        mode closed
        plan mon,tue,wed,thu,fri
          06:00-10:00 browser player
          player 30 rest 90
          browser 120
        plan sat,sun
          08:00-12:00 browser player
          player 60 rest parent
        """.trimIndent(),
    )
    private val both = setOf(Door.BROWSER, Door.PLAYER)
    private val player = setOf(Door.PLAYER)
    private val browser = setOf(Door.BROWSER)

    // 2026-11-02 is a Monday.
    private fun at(day: Int, time: String) = LocalDateTime.of(2026, 11, day, 0, 0).with(LocalTime.parse(time))
    private val monday = LocalDate.of(2026, 11, 2)

    /** Uses [door] for [minutes] from [start], a minute at a time, as the app's counter would. */
    private fun use(p: Policy, start: LocalDateTime, state: PlanState, door: Door, minutes: Int): PlanState {
        var s = state
        for (m in 1..minutes) s = p.count(start.plusMinutes(m.toLong()), s, door, 60)
        return s
    }

    @Test fun `the weekday plan opens both doors at 09 59 and only the player at 10 00`() {
        assertEquals(both, policy.openAt(at(2, "09:59")))
        assertEquals(player, policy.openAt(at(2, "10:00")))
        assertEquals(emptySet<Door>(), policy.openAt(at(2, "05:59")))
        assertEquals(both, policy.openAt(at(2, "06:00")))
    }

    @Test fun `thirty minutes of the player end the budget and start a ninety-minute rest`() {
        var s = policy.resolve(at(2, "10:00"), PlanState())
        assertEquals(PlanState(monday, 1), s)
        s = use(policy, at(2, "10:00"), s, Door.PLAYER, 29)
        assertEquals(player, policy.openAt(at(2, "10:29"), s))
        assertEquals(60L, policy.budgetLeft(at(2, "10:29"), s))
        s = use(policy, at(2, "10:29"), s, Door.PLAYER, 1)
        assertTrue(s.resting)
        assertEquals(at(2, "12:00"), s.restEnd)
        assertEquals(emptySet<Door>(), policy.openAt(at(2, "10:30"), s))
        assertEquals(emptySet<Door>(), policy.openAt(at(2, "11:59"), s))
    }

    @Test fun `the rest ends at the moment the budget ran out plus the rest`() {
        val s = policy.resolve(at(2, "10:00"), PlanState())
        // 29 minutes counted, then one report of 5 minutes at 10:40: the budget ran out at 10:36.
        val nearly = s.copy(spent = 29 * 60)
        val after = policy.count(at(2, "10:40"), nearly, Door.PLAYER, 300)
        assertEquals(at(2, "12:06"), after.restEnd)
    }

    @Test fun `the browser's use does not count against the player's budget`() {
        val s = use(policy, at(2, "10:00"), policy.resolve(at(2, "10:00"), PlanState()), Door.BROWSER, 45)
        assertEquals(0L, s.spent)
        assertEquals(player, policy.openAt(at(2, "10:45"), s))
    }

    @Test fun `the rest's end opens the browser alone`() {
        val resting = PlanState(monday, 1, 30 * 60, at(2, "12:00"))
        assertEquals(emptySet<Door>(), policy.openAt(at(2, "11:59"), resting))
        assertEquals(browser, policy.openAt(at(2, "12:00"), resting))
        assertEquals(PlanState(monday, 2), policy.resolve(at(2, "12:00"), resting))
    }

    @Test fun `after the last stage nothing is open until the next plan day`() {
        var s = PlanState(monday, 2)
        s = use(policy, at(2, "12:00"), s, Door.BROWSER, 120)
        assertEquals(PlanState(monday, 3), s)
        assertEquals(emptySet<Door>(), policy.openAt(at(2, "14:00"), s))
        assertEquals(emptySet<Door>(), policy.openAt(at(2, "23:59"), s))
        assertEquals(both, policy.openAt(at(3, "06:00"), s))
    }

    @Test fun `a new day starts fresh`() {
        val resting = PlanState(monday, 1, 30 * 60, at(2, "23:30"))
        assertEquals(PlanState(monday.plusDays(1)), policy.resolve(at(3, "00:10"), resting))
    }

    @Test fun `a span starts at its time whatever is running`() {
        val p = PolicyText.parse("plan mon\n  player 30 rest parent\n  16:00-18:00 browser")
        val resting = p.count(at(2, "08:30"), PlanState(), Door.PLAYER, 30 * 60)
        assertTrue(resting.restParent)
        assertEquals(emptySet<Door>(), p.openAt(at(2, "15:59"), resting))
        assertEquals(browser, p.openAt(at(2, "16:00"), resting))
        assertEquals(PlanState(monday, 1), p.resolve(at(2, "16:00"), resting))
    }

    @Test fun `rest parent never ends by clock, only when a parent opens`() {
        val sat = LocalDate.of(2026, 11, 7)
        var s = policy.resolve(at(7, "12:00"), PlanState())
        assertEquals(PlanState(sat, 1), s)
        s = use(policy, at(7, "12:00"), s, Door.PLAYER, 60)
        assertTrue(s.restParent)
        assertEquals(emptySet<Door>(), policy.openAt(at(7, "23:59"), s))
        assertNull(policy.resolve(at(7, "23:59"), s).restEnd)
        assertEquals(at(8, "00:00"), policy.nextChange(at(7, "13:00"), s))
        val opened = policy.openNow(at(7, "14:00"), s)
        assertEquals(PlanState(sat, 2), opened)
        assertFalse(opened.resting)
    }

    @Test fun `open now ends a rest in minutes too, and does nothing outside a rest`() {
        val resting = PlanState(monday, 1, 30 * 60, at(2, "12:00"))
        assertEquals(browser, policy.openAt(at(2, "11:00"), policy.openNow(at(2, "11:00"), resting)))
        val running = PlanState(monday, 1, 60)
        assertEquals(running, policy.openNow(at(2, "11:00"), running))
    }

    @Test fun `a span crossing midnight belongs to its first day`() {
        val p = PolicyText.parse("plan fri\n  22:00-01:00 player\nplan sat\n  browser 30")
        assertEquals(player, p.openAt(at(6, "23:30")))
        assertEquals(player, p.openAt(at(7, "00:30")))
        assertEquals(LocalDate.of(2026, 11, 6), p.resolve(at(7, "00:30"), PlanState()).day)
        // Saturday's plan begins when Friday's span ends.
        assertEquals(browser, p.openAt(at(7, "01:00")))
        assertEquals(LocalDate.of(2026, 11, 7), p.resolve(at(7, "01:00"), PlanState()).day)
        // Thursday has no plan: Friday's early hours are closed.
        assertEquals(emptySet<Door>(), p.openAt(at(6, "00:30")))
    }

    @Test fun `next change gives the right edge in each case`() {
        // Before the first span: its start.
        assertEquals(at(2, "06:00"), policy.nextChange(at(2, "05:00")))
        // In a span: its end.
        assertEquals(at(2, "10:00"), policy.nextChange(at(2, "09:00")))
        // In a budget: now plus what is left of it.
        val running = PlanState(monday, 1, 10 * 60)
        assertEquals(at(2, "10:40"), policy.nextChange(at(2, "10:20"), running))
        // In a rest: its end.
        val resting = PlanState(monday, 1, 30 * 60, at(2, "12:00"))
        assertEquals(at(2, "12:00"), policy.nextChange(at(2, "10:30"), resting))
        // After the last stage: the day's end.
        assertEquals(at(3, "00:00"), policy.nextChange(at(2, "20:00"), PlanState(monday, 3)))
        // Friday evening to Saturday's plan, which opens nothing before 08:00; midnight is still an edge.
        assertEquals(at(7, "00:00"), policy.nextChange(at(6, "20:00"), PlanState(LocalDate.of(2026, 11, 6), 3)))
        // No plan at all: nothing is timed.
        assertNull(Policy().nextChange(at(2, "12:00")))
    }

    @Test fun `next change finds the next plan day across days without one`() {
        val p = PolicyText.parse("plan sat\n  08:00-12:00 player")
        assertEquals(at(7, "00:00"), p.nextChange(at(2, "12:00")))
        assertEquals(at(7, "08:00"), p.nextChange(at(7, "00:00")))
    }

    @Test fun `next opening is by the clock alone`() {
        assertEquals(at(2, "06:00"), policy.nextOpening(Door.PLAYER, at(2, "05:00")))
        assertEquals(at(2, "10:00"), policy.nextOpening(Door.PLAYER, at(2, "10:00")))
        val resting = PlanState(monday, 1, 30 * 60, at(2, "12:00"))
        assertEquals(at(2, "12:00"), policy.nextOpening(Door.BROWSER, at(2, "10:30"), resting))
        // The player has no more stage on Monday: Tuesday's span.
        assertEquals(at(3, "06:00"), policy.nextOpening(Door.PLAYER, at(2, "10:30"), resting))
        assertNull(PolicyText.parse("plan mon\n  player 10").nextOpening(Door.BROWSER, at(2, "10:00")))
    }

    @Test fun `no plan at all leaves every door open`() {
        assertEquals(Door.entries.toSet(), Policy().openAt(at(2, "03:00")))
        assertNull(Policy().budgetLeft(at(2, "03:00"), PlanState()))
    }

    @Test fun `a day without a plan opens nothing`() {
        val p = PolicyText.parse("plan sat,sun\n  player 60")
        assertEquals(emptySet<Door>(), p.openAt(at(2, "12:00")))
        assertEquals(player, p.openAt(at(7, "00:00")))
    }

    @Test fun `a state from an edited plan is kept within the plan`() {
        val stale = PlanState(monday, 9, 5)
        assertEquals(emptySet<Door>(), policy.openAt(at(2, "11:00"), stale))
        assertEquals(PlanState(monday, 3, 5), policy.resolve(at(2, "11:00"), stale))
    }

    @Test fun `the stored state reads back as it was`() {
        val states = listOf(
            PlanState(),
            PlanState(monday, 1, 1800, at(2, "12:00")),
            PlanState(monday, 1, 3600, restParent = true),
            PlanState(monday, 2, 75),
        )
        for (s in states) assertEquals(s, PolicyText.parseState(PolicyText.renderState(s)))
        assertEquals("day=2026-11-02 stage=1 spent=1800 rest=2026-11-02T12:00", PolicyText.renderState(states[1]))
        assertEquals(PlanState(), PolicyText.parseState("day=yesterday stage=x"))
        assertEquals(PlanState(), PolicyText.parseState(""))
    }
}
