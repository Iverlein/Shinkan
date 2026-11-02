package shinkan.core.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AddressTest {
    @Test fun `an address is opened, a host over https, the rest searched`() {
        assertEquals("https://example.org/a?b=1", Address.of(" https://example.org/a?b=1 "))
        assertEquals("http://10.0.2.2:8000/test.html", Address.of("http://10.0.2.2:8000/test.html"))
        assertEquals("https://example.org", Address.of("example.org"))
        assertEquals("https://en.wikipedia.org/wiki/Cat", Address.of("en.wikipedia.org/wiki/Cat"))
        assertEquals("https://example.org:8443/x", Address.of("example.org:8443/x"))
        assertEquals(Address.SEARCH + "how+do+cats+purr", Address.of("how do cats purr"))
        assertEquals(Address.SEARCH + "cats", Address.of("cats"))
        assertEquals(null, Address.of("   "))
    }

    @Test fun `other schemes are searched as text, never opened`() {
        assertTrue(Address.of("javascript:alert(1)")!!.startsWith(Address.SEARCH))
        assertTrue(Address.of("file:///sdcard/x.html")!!.startsWith(Address.SEARCH))
        assertTrue(Address.of("data:text/html,hi")!!.startsWith(Address.SEARCH))
        assertTrue(Address.allowed("https://example.org"))
        assertFalse(Address.allowed("intent://scan#Intent;end"))
        assertFalse(Address.allowed("file:///etc/hosts"))
    }
}
