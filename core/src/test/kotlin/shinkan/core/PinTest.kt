package shinkan.core

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
