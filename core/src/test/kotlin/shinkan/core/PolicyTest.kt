package shinkan.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek.*
import java.time.LocalDateTime
import java.time.LocalTime

class PolicyTest {
    private val text = """
        # a reader at any time, a game on weekday afternoons and weekend mornings
        allow org.example.reader
        allow com.example.game
        window com.example.game mon,tue,wed,thu,fri 16:00-18:00
        window com.example.game sat,sun 10:00-12:00
        restrict factory_reset
        restrict safe_boot
    """.trimIndent()

    private val policy = PolicyText.parse(text)

    // 2026-11-02 is a Monday.
    private fun at(day: Int, time: String) = LocalDateTime.of(2026, 11, day, 0, 0).with(LocalTime.parse(time))

    @Test fun `reader at any time, game only in its windows`() {
        assertEquals(setOf("org.example.reader", "com.example.game"), policy.usableAt(at(2, "16:30")))
        assertEquals(setOf("org.example.reader"), policy.usableAt(at(2, "18:00")))
        assertEquals(setOf("org.example.reader"), policy.usableAt(at(2, "10:30")))
        assertEquals(setOf("org.example.reader", "com.example.game"), policy.usableAt(at(7, "10:30")))
    }

    @Test fun `next change is the nearest window edge`() {
        assertEquals(at(2, "16:00"), policy.nextChange(at(2, "12:00")))
        assertEquals(at(2, "18:00"), policy.nextChange(at(2, "16:00")))
        assertEquals(at(3, "16:00"), policy.nextChange(at(2, "18:00")))
        assertEquals(at(7, "10:00"), policy.nextChange(at(6, "18:00")))
    }

    @Test fun `an overnight window belongs to the day it opens`() {
        val w = Window("com.example.radio", setOf(FRIDAY), LocalTime.of(22, 0), LocalTime.of(1, 0))
        assertTrue(w.contains(at(6, "23:00")))
        assertTrue(w.contains(at(7, "00:30")))
        assertFalse(w.contains(at(7, "01:00")))
        assertFalse(w.contains(at(5, "23:00")))
        assertEquals(listOf(at(6, "22:00"), at(7, "01:00")), w.edgesAfter(at(2, "12:00")).take(2))
    }

    @Test fun `restrictions default when none are written`() {
        assertEquals(Restriction.DEFAULT, PolicyText.parse("allow a.b").restrictions)
        assertEquals(setOf(Restriction.FACTORY_RESET, Restriction.SAFE_BOOT), policy.restrictions)
    }

    @Test fun `render and parse round-trip`() {
        val rendered = PolicyText.render(policy)
        assertEquals(policy, PolicyText.parse(rendered))
        assertTrue(rendered.lines().first().startsWith("allow "))
    }

    @Test fun `malformed lines name their line`() {
        val e = assertThrows(PolicyText.Malformed::class.java) { PolicyText.parse("allow a.b\nwindow a.b mon 25:00-26:00") }
        assertTrue(e.message!!.startsWith("line 2:"))
        assertThrows(PolicyText.Malformed::class.java) { PolicyText.parse("permit a.b") }
        assertThrows(PolicyText.Malformed::class.java) { PolicyText.parse("restrict everything") }
        assertThrows(PolicyText.Malformed::class.java) { PolicyText.parse("window a.b mon 10:00-10:00") }
    }

    @Test fun `an empty policy holds everything`() {
        assertEquals(emptySet<String>(), PolicyText.parse("").usableAt(at(2, "12:00")))
        assertEquals(null, PolicyText.parse("").nextChange(at(2, "12:00")))
    }
}
