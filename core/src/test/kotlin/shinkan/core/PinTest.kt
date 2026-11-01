package shinkan.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PinTest {
    @Test fun `the right pin verifies, a wrong one does not`() {
        val stored = Pin.hash("4821".toCharArray())
        assertTrue(stored.startsWith("pbkdf2:"))
        assertTrue(Pin.verify("4821".toCharArray(), stored))
        assertFalse(Pin.verify("4822".toCharArray(), stored))
        assertFalse(Pin.verify("".toCharArray(), stored))
    }

    @Test fun `two hashes of one pin differ by their salt`() {
        assertNotEquals(Pin.hash("4821".toCharArray()), Pin.hash("4821".toCharArray()))
    }

    @Test fun `garbage in storage never verifies`() {
        assertFalse(Pin.verify("4821".toCharArray(), ""))
        assertFalse(Pin.verify("4821".toCharArray(), "pbkdf2:x:00:00"))
        assertFalse(Pin.verify("4821".toCharArray(), "plain:4821"))
    }
}

class PinGateTest {
    @Test fun `three wrong attempts are free, then the wait doubles from five seconds`() {
        assertEquals(listOf(0L, 0L, 0L, 5L, 10L, 20L, 40L, 80L), (0..7).map(PinGate::wait))
    }

    @Test fun `the wait stops at an hour`() {
        assertEquals(2560L, PinGate.wait(12))
        assertEquals(3600L, PinGate.wait(13))
        assertEquals(3600L, PinGate.wait(14))
        assertEquals(3600L, PinGate.wait(1000))
        assertEquals(3600L, PinGate.wait(Int.MAX_VALUE))
    }

    @Test fun `the remaining wait counts down from the last attempt`() {
        val last = 1_000_000L
        assertEquals(0L, PinGate.remaining(2, last, last))
        assertEquals(5L, PinGate.remaining(3, last, last))
        assertEquals(2L, PinGate.remaining(3, last, last + 3_000))
        assertEquals(0L, PinGate.remaining(3, last, last + 5_000))
        assertEquals(0L, PinGate.remaining(3, last, last + 60_000))
    }

    @Test fun `a clock set back never lengthens the wait`() {
        assertEquals(10L, PinGate.remaining(4, 1_000_000L, 0L))
    }

    @Test fun `a pin is four or more digits`() {
        assertTrue(PinGate.acceptable("4821".toCharArray()))
        assertTrue(PinGate.acceptable("482193".toCharArray()))
        assertFalse(PinGate.acceptable("482".toCharArray()))
        assertFalse(PinGate.acceptable("48a1".toCharArray()))
        assertFalse(PinGate.acceptable(CharArray(0)))
    }
}
