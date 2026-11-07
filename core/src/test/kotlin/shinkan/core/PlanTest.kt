package shinkan.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** The day plan of SPEC §4.7: spans, the budgets inside them, rests, and the edges between them. */
class PlanTest {
    private val policy = PolicyText.parse(
        """
        mode closed
        plan mon,tue,wed,thu,fri
          06:00-10:00 browser player
          12:00-14:00 player 30
        plan sat,sun
          08:00-12:00 browser player 60 rest parent
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

    @Test fun `a span opens at its start and closes at its end`() {
        assertEquals(emptySet<Door>(), policy.openAt(at(2, "05:59")))
        assertEquals(both, policy.openAt(at(2, "06:00")))
        assertEquals(both, policy.openAt(at(2, "09:59")))
        assertEquals(emptySet<Door>(), policy.openAt(at(2, "10:00")))
        assertEquals(player, policy.openAt(at(2, "12:00")))
    }

    @Test fun `the state names the span under way or the next`() {
        assertEquals(PlanState(monday, 0), policy.resolve(at(2, "05:00"), PlanState()))
        assertEquals(PlanState(monday, 0), policy.resolve(at(2, "09:00"), PlanState()))
        assertEquals(PlanState(monday, 1), policy.resolve(at(2, "10:00"), PlanState()))
        assertEquals(PlanState(monday, 2), policy.resolve(at(2, "14:00"), PlanState()))
    }

    @Test fun `the rest ends at the moment the budget ran out plus the rest`() {
        val s = rounds.resolve(at(2, "10:00"), PlanState())
        // 59 minutes counted, then one report of 5 minutes at 11:04: the budget ran out at 11:00.
        val nearly = s.copy(uses = mapOf(0 to Use(59 * 60)))
        val after = rounds.count(at(2, "11:04"), nearly, Door.PLAYER, 300)
        assertEquals(at(2, "11:30"), after.uses[0]?.restEnd)
    }

    @Test fun `after the last span nothing is open until the next plan day`() {
        assertEquals(emptySet<Door>(), policy.openAt(at(2, "14:00")))
        assertEquals(emptySet<Door>(), policy.openAt(at(2, "23:59")))
        assertEquals(both, policy.openAt(at(3, "06:00")))
    }

    @Test fun `a new day starts fresh, a rest included`() {
        val p = PolicyText.parse("plan mon,tue\n  00:00-23:59 player 30 rest parent")
        val s = use(p, at(2, "22:00"), p.resolve(at(2, "22:00"), PlanState()), Door.PLAYER, 30)
        assertTrue(s.resting)
        assertEquals(PlanState(monday.plusDays(1)), p.resolve(at(3, "00:10"), s))
    }

    @Test fun `a later span takes over at its start`() {
        val p = PolicyText.parse("plan mon\n  08:00-20:00 player 30 rest parent\n  16:00-18:00 browser")
        val resting = use(p, at(2, "08:00"), p.resolve(at(2, "08:00"), PlanState()), Door.PLAYER, 30)
        assertTrue(resting.resting)
        assertEquals(emptySet<Door>(), p.openAt(at(2, "15:59"), resting))
        assertEquals(browser, p.openAt(at(2, "16:00"), resting))
        assertEquals(PlanState(monday, 1), p.resolve(at(2, "16:00"), resting))
    }

    @Test fun `rest parent never ends by clock, only when a parent opens or the span ends`() {
        val sat = LocalDate.of(2026, 11, 7)
        var s = policy.resolve(at(7, "08:00"), PlanState())
        assertEquals(PlanState(sat, 0), s)
        s = use(policy, at(7, "08:00"), s, Door.PLAYER, 60)
        assertTrue(s.uses[0]!!.restParent)
        assertEquals(browser, policy.openAt(at(7, "11:59"), s))
        assertEquals(both, policy.openAt(at(7, "11:00"), policy.openNow(at(7, "11:00"), s)))
        assertEquals(emptySet<Door>(), policy.openAt(at(7, "12:00"), s))
        assertFalse(policy.resolve(at(7, "12:00"), s).resting)
    }

    @Test fun `open now does nothing outside a rest`() {
        val running = PlanState(monday, 0, mapOf(0 to Use(60)))
        val p = PolicyText.parse("plan mon\n  10:00-12:00 player 30 rest 30")
        assertEquals(running, p.openNow(at(2, "11:00"), running))
    }

    @Test fun `a span crossing midnight belongs to its first day`() {
        val p = PolicyText.parse("plan fri\n  22:00-01:00 player\nplan sat\n  02:00-03:00 browser")
        assertEquals(player, p.openAt(at(6, "23:30")))
        assertEquals(player, p.openAt(at(7, "00:30")))
        assertEquals(LocalDate.of(2026, 11, 6), p.resolve(at(7, "00:30"), PlanState()).day)
        // Saturday's plan begins when Friday's span ends.
        assertEquals(emptySet<Door>(), p.openAt(at(7, "01:00")))
        assertEquals(LocalDate.of(2026, 11, 7), p.resolve(at(7, "01:00"), PlanState()).day)
        assertEquals(browser, p.openAt(at(7, "02:00")))
        // Thursday has no plan: Friday's early hours are closed.
        assertEquals(emptySet<Door>(), p.openAt(at(6, "00:30")))
    }

    @Test fun `a budget's use survives across midnight in a span crossing it`() {
        val p = PolicyText.parse("plan fri\n  22:00-02:00 player 60")
        val s = use(p, at(6, "23:30"), p.resolve(at(6, "23:30"), PlanState()), Door.PLAYER, 45)
        assertEquals(15 * 60L, p.budgetLeft(at(7, "00:30"), s))
    }

    @Test fun `next change gives the right edge in each case`() {
        // Before the first span: its start.
        assertEquals(at(2, "06:00"), policy.nextChange(at(2, "05:00")))
        // In a span: its end.
        assertEquals(at(2, "10:00"), policy.nextChange(at(2, "09:00")))
        // Between spans: the next start.
        assertEquals(at(2, "12:00"), policy.nextChange(at(2, "11:00")))
        // In a budget: now plus what is left of it.
        val running = PlanState(monday, 1, mapOf(0 to Use(10 * 60)))
        assertEquals(at(2, "12:40"), policy.nextChange(at(2, "12:20"), running))
        // After the last span: the day's end.
        assertEquals(at(3, "00:00"), policy.nextChange(at(2, "20:00")))
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
        assertEquals(at(2, "12:00"), policy.nextOpening(Door.PLAYER, at(2, "10:00")))
        // The browser has no more span on Monday: Tuesday's.
        assertEquals(at(3, "06:00"), policy.nextOpening(Door.BROWSER, at(2, "10:30")))
        assertNull(PolicyText.parse("plan mon\n  10:00-11:00 player").nextOpening(Door.BROWSER, at(2, "10:00")))
    }

    @Test fun `no plan at all leaves every door open`() {
        assertEquals(Door.entries.toSet(), Policy().openAt(at(2, "03:00")))
        assertNull(Policy().budgetLeft(at(2, "03:00"), PlanState()))
    }

    @Test fun `a day without a plan opens nothing`() {
        val p = PolicyText.parse("plan sat,sun\n  00:00-23:59 player 60")
        assertEquals(emptySet<Door>(), p.openAt(at(2, "12:00")))
        assertEquals(player, p.openAt(at(7, "00:00")))
    }

    // The owner's example: the morning free, the day in rounds of 60 and 30 (SPEC §4.7).
    private val rounds = PolicyText.parse(
        """
        plan mon
          06:00-09:00 player
          10:00-19:00 browser player 60 rest 30
          19:00-21:00 browser,player 45
        """.trimIndent(),
    )

    @Test fun `a door with no budget in its span runs free to the span's end`() {
        val s = use(rounds, at(2, "06:00"), PlanState(), Door.PLAYER, 179)
        assertEquals(player, rounds.openAt(at(2, "08:59"), s))
        assertNull(rounds.budgetLeft(at(2, "08:59"), s, Door.PLAYER))
        assertEquals(emptySet<Door>(), rounds.openAt(at(2, "09:00"), s))
        assertEquals(emptySet<Door>(), rounds.openAt(at(2, "09:59"), s))
    }

    @Test fun `each span of a day has its own budget`() {
        assertEquals(both, rounds.openAt(at(2, "10:00")))
        assertEquals(3600L, rounds.budgetLeft(at(2, "10:00"), PlanState(), Door.PLAYER))
        assertNull(rounds.budgetLeft(at(2, "10:00"), PlanState(), Door.BROWSER))
        assertEquals(45 * 60L, rounds.budgetLeft(at(2, "19:00"), PlanState(), Door.BROWSER))
    }

    @Test fun `sixty minutes of the player rest that door alone`() {
        var s = rounds.resolve(at(2, "10:00"), PlanState())
        s = use(rounds, at(2, "10:00"), s, Door.PLAYER, 59)
        assertEquals(60L, rounds.budgetLeft(at(2, "10:59"), s, Door.PLAYER))
        assertEquals(both, rounds.openAt(at(2, "10:59"), s))
        s = use(rounds, at(2, "10:59"), s, Door.PLAYER, 1)
        assertTrue(s.resting)
        assertEquals(browser, rounds.openAt(at(2, "11:00"), s))
        assertEquals(browser, rounds.openAt(at(2, "11:29"), s))
        assertEquals(at(2, "11:30"), rounds.nextChange(at(2, "11:00"), s))
        assertEquals(at(2, "11:30"), rounds.budgetOf(Door.PLAYER, at(2, "11:00"), s)?.restEnd)
        assertEquals(at(2, "11:30"), rounds.nextOpening(Door.PLAYER, at(2, "11:00"), s))
    }

    @Test fun `the browser's use goes on while the player rests and does not touch its budget`() {
        var s = rounds.resolve(at(2, "10:00"), PlanState())
        s = use(rounds, at(2, "10:00"), s, Door.PLAYER, 60)
        val during = use(rounds, at(2, "11:00"), s, Door.BROWSER, 20)
        assertEquals(s, during)
        assertEquals(browser, rounds.openAt(at(2, "11:20"), during))
    }

    @Test fun `a rest in a span fills its budget again, round after round`() {
        var s = rounds.resolve(at(2, "10:00"), PlanState())
        var start = at(2, "10:00")
        repeat(3) {
            s = use(rounds, start, s, Door.PLAYER, 60)
            assertEquals(browser, rounds.openAt(start.plusMinutes(60), s))
            start = start.plusMinutes(90)
            assertEquals(both, rounds.openAt(start, s))
            s = rounds.resolve(start, s)
            assertEquals(3600L, rounds.budgetLeft(start, s, Door.PLAYER))
        }
        assertEquals(at(2, "14:30"), start)
    }

    @Test fun `a rest running into the next span is measured by that span's budget`() {
        val s = use(rounds, at(2, "17:50"), rounds.resolve(at(2, "17:50"), PlanState()), Door.PLAYER, 60)
        assertEquals(at(2, "19:20"), rounds.budgetOf(Door.PLAYER, at(2, "18:50"), s)?.restEnd)
        assertEquals(browser, rounds.openAt(at(2, "18:59"), s))
        // Sixty minutes used are past the evening's shared 45, which has no rest: both doors stay shut.
        assertEquals(emptySet<Door>(), rounds.openAt(at(2, "19:00"), s))
        assertEquals(true, rounds.budgetOf(Door.BROWSER, at(2, "19:00"), s)?.depleted)
        assertEquals(at(2, "21:00"), rounds.nextChange(at(2, "19:00"), rounds.resolve(at(2, "19:00"), s)))
    }

    // Two spans that meet, as the owner gave them for the seam (2026-11-07).
    private val seam = PolicyText.parse("plan mon\n  10:00-14:00 player 60 rest 30\n  14:00-19:00 player 45 rest 15\n  19:00-20:00 player")

    @Test fun `minutes used run on across a seam`() {
        val s = use(seam, at(2, "13:20"), seam.resolve(at(2, "13:20"), PlanState()), Door.PLAYER, 40)
        assertEquals(5 * 60L, seam.budgetLeft(at(2, "14:00"), s, Door.PLAYER))
        assertEquals(player, seam.openAt(at(2, "14:00"), s))
    }

    @Test fun `minutes used past the next span's budget start its rest at the seam`() {
        val s = use(seam, at(2, "13:09"), seam.resolve(at(2, "13:09"), PlanState()), Door.PLAYER, 50)
        assertEquals(player, seam.openAt(at(2, "13:59"), s))
        assertEquals(emptySet<Door>(), seam.openAt(at(2, "14:00"), s))
        assertEquals(at(2, "14:15"), seam.budgetOf(Door.PLAYER, at(2, "14:00"), s)?.restEnd)
        assertEquals(at(2, "14:15"), seam.nextOpening(Door.PLAYER, at(2, "14:00"), s))
    }

    @Test fun `a rest under way runs on with the next span's length from its start`() {
        // Spent at 13:50: the morning's rest would end 14:20, the afternoon's 15 minutes end it at 14:05.
        val s = use(seam, at(2, "12:50"), seam.resolve(at(2, "12:50"), PlanState()), Door.PLAYER, 60)
        assertEquals(at(2, "14:20"), seam.budgetOf(Door.PLAYER, at(2, "13:59"), s)?.restEnd)
        assertEquals(at(2, "14:05"), seam.budgetOf(Door.PLAYER, at(2, "14:00"), s)?.restEnd)
        assertEquals(player, seam.openAt(at(2, "14:05"), s))
        assertEquals(45 * 60L, seam.budgetLeft(at(2, "14:05"), s, Door.PLAYER))
    }

    @Test fun `a rest the next span's length has already ended fills its budget at the seam`() {
        val s = use(seam, at(2, "12:40"), seam.resolve(at(2, "12:40"), PlanState()), Door.PLAYER, 60)
        assertTrue(seam.resolve(at(2, "13:59"), s).resting)
        assertEquals(player, seam.openAt(at(2, "14:00"), s))
        assertEquals(45 * 60L, seam.budgetLeft(at(2, "14:00"), s, Door.PLAYER))
    }

    @Test fun `a door free in the next span runs free, and a seam carries through several spans`() {
        val s = use(seam, at(2, "18:00"), seam.resolve(at(2, "18:00"), PlanState()), Door.PLAYER, 45)
        assertEquals(emptySet<Door>(), seam.openAt(at(2, "18:59"), s))
        assertEquals(player, seam.openAt(at(2, "19:00"), s))
        // A state last stored at 13:30 is walked through both seams.
        val early = use(seam, at(2, "13:00"), seam.resolve(at(2, "13:00"), PlanState()), Door.PLAYER, 30)
        assertEquals(PlanState(monday, 2), seam.resolve(at(2, "19:30"), early))
        assertEquals(15 * 60L, seam.budgetLeft(at(2, "14:30"), early, Door.PLAYER))
    }

    @Test fun `a budget used up with no rest opens with what is left of a larger one`() {
        val p = PolicyText.parse("plan mon\n  10:00-14:00 player 30\n  14:00-19:00 player 60")
        val s = use(p, at(2, "13:00"), p.resolve(at(2, "13:00"), PlanState()), Door.PLAYER, 30)
        assertEquals(emptySet<Door>(), p.openAt(at(2, "13:59"), s))
        assertEquals(player, p.openAt(at(2, "14:00"), s))
        assertEquals(30 * 60L, p.budgetLeft(at(2, "14:00"), s, Door.PLAYER))
    }

    @Test fun `use counted across a seam goes to the span of each second`() {
        val p = PolicyText.parse("plan mon\n  10:00-14:00 player\n  14:00-19:00 player 45")
        val before = p.resolve(at(2, "13:59:50"), PlanState())
        val s = p.count(at(2, "14:00:05"), before, Door.PLAYER, 15)
        assertEquals(45 * 60L - 5, p.budgetLeft(at(2, "14:00:05"), s, Door.PLAYER))
    }

    @Test fun `use counted across a span's end into a gap stays with that span`() {
        val p = PolicyText.parse("plan mon\n  10:00-14:00 player 30\n  15:00-19:00 player 30")
        val s = p.count(at(2, "14:00:10"), p.resolve(at(2, "13:59:50"), PlanState()), Door.PLAYER, 20)
        assertEquals(30 * 60L, p.budgetLeft(at(2, "15:00"), s, Door.PLAYER))
    }

    @Test fun `a gap between spans starts the next one whole`() {
        val p = PolicyText.parse("plan mon\n  10:00-14:00 player 30\n  14:01-19:00 player 30")
        val s = use(p, at(2, "13:00"), p.resolve(at(2, "13:00"), PlanState()), Door.PLAYER, 30)
        assertEquals(30 * 60L, p.budgetLeft(at(2, "14:01"), s, Door.PLAYER))
    }

    @Test fun `doors joined by a comma share their minutes`() {
        var s = rounds.resolve(at(2, "19:00"), PlanState())
        s = use(rounds, at(2, "19:00"), s, Door.BROWSER, 30)
        s = use(rounds, at(2, "19:30"), s, Door.PLAYER, 14)
        assertEquals(60L, rounds.budgetLeft(at(2, "19:44"), s, Door.BROWSER))
        s = use(rounds, at(2, "19:44"), s, Door.PLAYER, 1)
        assertEquals(emptySet<Door>(), rounds.openAt(at(2, "19:45"), s))
    }

    @Test fun `a budget with no rest stays shut to its span's end and is no rest`() {
        val p = PolicyText.parse("plan mon\n  10:00-12:00 browser player 30\n  12:00-13:00 player")
        val s = use(p, at(2, "10:00"), p.resolve(at(2, "10:00"), PlanState()), Door.PLAYER, 30)
        assertFalse(s.resting)
        assertEquals(browser, p.openAt(at(2, "11:59"), s))
        assertNull(p.budgetLeft(at(2, "11:59"), s, Door.PLAYER))
        assertEquals(at(2, "12:00"), p.nextOpening(Door.PLAYER, at(2, "10:30"), s))
        assertEquals(player, p.openAt(at(2, "12:00"), s))
    }

    @Test fun `a rest a parent ends holds its door until the parent opens, then the budget fills`() {
        val p = PolicyText.parse("plan mon\n  10:00-19:00 browser player 60 rest parent")
        val s = use(p, at(2, "10:00"), p.resolve(at(2, "10:00"), PlanState()), Door.PLAYER, 60)
        assertEquals(browser, p.openAt(at(2, "18:59"), s))
        assertEquals(at(2, "19:00"), p.nextChange(at(2, "12:00"), s))
        val opened = p.openNow(at(2, "12:00"), s)
        assertFalse(opened.resting)
        assertEquals(both, p.openAt(at(2, "12:00"), opened))
        assertEquals(3600L, p.budgetLeft(at(2, "12:00"), opened, Door.PLAYER))
    }

    @Test fun `open now ends a rest in a span and leaves the other budget's use`() {
        val p = PolicyText.parse("plan mon\n  10:00-19:00 browser 120 player 60 rest 30")
        var s = use(p, at(2, "10:00"), p.resolve(at(2, "10:00"), PlanState()), Door.BROWSER, 10)
        s = use(p, at(2, "10:10"), s, Door.PLAYER, 60)
        val opened = p.openNow(at(2, "11:15"), s)
        assertEquals(both, p.openAt(at(2, "11:15"), opened))
        assertEquals(110 * 60L, p.budgetLeft(at(2, "11:15"), opened, Door.BROWSER))
    }

    @Test fun `next change in a span takes the nearest budget's end`() {
        val p = PolicyText.parse("plan mon\n  10:00-19:00 browser 120 player 60 rest 30")
        var s = p.resolve(at(2, "10:00"), PlanState())
        s = use(p, at(2, "10:00"), s, Door.PLAYER, 45)
        assertEquals(at(2, "11:00"), p.nextChange(at(2, "10:45"), s))
        assertEquals(15 * 60L, p.budgetLeft(at(2, "10:45"), s))
    }

    @Test fun `no use counts before a span starts`() {
        val s = rounds.count(at(2, "09:30"), PlanState(monday, 1), Door.PLAYER, 600)
        assertEquals(PlanState(monday, 1), s)
        assertEquals(emptyList<BudgetNow>(), rounds.budgets(at(2, "09:30"), s))
    }

    @Test fun `a budget of the open device's span rests and pins it, and the rest ends`() {
        val p = PolicyText.parse("mode open\nplan mon\n  10:00-19:00 device 60 rest 30")
        val s = use(p, at(2, "10:00"), p.resolve(at(2, "10:00"), PlanState()), Door.DEVICE, 60)
        assertTrue(p.pinnedAt(at(2, "11:29"), s))
        assertEquals(emptySet<Door>(), p.doorsAt(at(2, "11:29"), s))
        assertFalse(p.pinnedAt(at(2, "11:30"), s))
        assertEquals(3600L, p.budgetLeft(at(2, "11:30"), s, Door.DEVICE))
    }

    @Test fun `a stored use from an edited span is dropped`() {
        val stale = PlanState(monday, 0, uses = mapOf(4 to Use(100)))
        assertEquals(PlanState(monday, 0), rounds.resolve(at(2, "07:00"), stale))
    }

    @Test fun `a state of another span or day is left behind`() {
        val other = PlanState(monday, 0, mapOf(0 to Use(100)))
        assertEquals(PlanState(monday, 1), rounds.resolve(at(2, "11:00"), other))
        assertEquals(PlanState(monday.plusDays(7), 1), rounds.resolve(at(9, "11:00"), PlanState(monday, 1, mapOf(0 to Use(100)))))
    }

    // A parent's edit applied in a span under way (SPEC §8.2), the owner's word of 2026-11-07.
    private val day = PolicyText.parse("plan mon\n  09:00-13:00 browser player 30 rest 10")
    private fun edited(line: String) = PolicyText.parse("plan mon\n  $line")

    @Test fun `a budget lowered below the minutes used starts its rest at once`() {
        val s = use(day, at(2, "10:00"), day.resolve(at(2, "10:00"), PlanState()), Door.PLAYER, 20)
        val p = edited("09:00-13:00 browser player 15 rest 10")
        val taken = p.takeOver(day, at(2, "10:20"), s)
        assertEquals(browser, p.openAt(at(2, "10:20"), taken))
        assertEquals(at(2, "10:30"), p.budgetOf(Door.PLAYER, at(2, "10:20"), taken)?.restEnd)
    }

    @Test fun `a budget raised during its rest opens with what is left`() {
        val s = use(day, at(2, "10:00"), day.resolve(at(2, "10:00"), PlanState()), Door.PLAYER, 30)
        assertTrue(day.resolve(at(2, "10:31"), s).resting)
        val p = edited("09:00-13:00 browser player 45 rest 10")
        val taken = p.takeOver(day, at(2, "10:31"), s)
        assertEquals(both, p.openAt(at(2, "10:31"), taken))
        assertEquals(15 * 60L, p.budgetLeft(at(2, "10:31"), taken, Door.PLAYER))
    }

    @Test fun `a rest shortened ends at its start plus the new length`() {
        val s = use(day, at(2, "10:00"), day.resolve(at(2, "10:00"), PlanState()), Door.PLAYER, 30)
        val p = edited("09:00-13:00 browser player 30 rest 5")
        assertEquals(at(2, "10:35"), p.budgetOf(Door.PLAYER, at(2, "10:32"), p.takeOver(day, at(2, "10:32"), s))?.restEnd)
        assertEquals(both, p.openAt(at(2, "10:36"), p.takeOver(day, at(2, "10:36"), s)))
    }

    @Test fun `an edit elsewhere keeps the use and the rest as they stand`() {
        val s = use(day, at(2, "10:00"), day.resolve(at(2, "10:00"), PlanState()), Door.PLAYER, 30)
        val p = PolicyText.parse("plan mon\n  09:00-13:00 browser player 30 rest 10\n  18:00-19:00 browser")
        val taken = p.takeOver(day, at(2, "10:33"), s)
        assertEquals(day.resolve(at(2, "10:33"), s), taken)
        val running = use(day, at(2, "11:00"), day.resolve(at(2, "11:00"), PlanState()), Door.PLAYER, 12)
        assertEquals(18 * 60L, p.budgetLeft(at(2, "11:12"), p.takeOver(day, at(2, "11:12"), running), Door.PLAYER))
    }

    @Test fun `a span moved off the present moment, or a door taken out of a budget, starts afresh`() {
        val s = use(day, at(2, "10:00"), day.resolve(at(2, "10:00"), PlanState()), Door.PLAYER, 20)
        val later = edited("11:00-13:00 browser player 30 rest 10")
        assertEquals(PlanState(monday, 0), later.takeOver(day, at(2, "10:20"), s))
        val other = edited("09:00-13:00 browser 30 rest 10 player")
        val taken = other.takeOver(day, at(2, "10:20"), s)
        assertEquals(30 * 60L, other.budgetLeft(at(2, "10:20"), taken, Door.BROWSER))
        assertNull(other.budgetLeft(at(2, "10:20"), taken, Door.PLAYER))
    }

    @Test fun `the stored state reads back as it was`() {
        val states = listOf(
            PlanState(),
            PlanState(monday, 2),
            PlanState(monday, 1, mapOf(0 to Use(600), 1 to Use(3600, at(2, "12:00")), 2 to Use(3600, restParent = true))),
        )
        for (s in states) assertEquals(s, PolicyText.parseState(PolicyText.renderState(s)))
        assertEquals("day=2026-11-02 span=1 b0=600 b1=3600,2026-11-02T12:00 b2=3600,parent", PolicyText.renderState(states[2]))
        assertEquals(PlanState(), PolicyText.parseState("day=yesterday span=x"))
        assertEquals(PlanState(), PolicyText.parseState(""))
    }

    @Test fun `a state stored by 1_0 keeps its day and span and loses the rest`() {
        assertEquals(PlanState(monday, 1), PolicyText.parseState("day=2026-11-02 stage=1 spent=1800 rest=2026-11-02T12:00"))
    }
}
