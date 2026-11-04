package shinkan.core.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SeenHostsTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // example.org, A: a CNAME by pointer to the question, then two A records under a pointer, then an AAAA.
    private val question = bytes(7) + "example".toByteArray() + bytes(3) + "org".toByteArray() + bytes(0, 0, 1, 0, 1)
    private val cname = bytes(0xc0, 12, 0, 5, 0, 1, 0, 0, 0, 60, 0, 4, 1, 'a'.code, 0xc0, 12)
    private fun a(vararg ip: Int) = bytes(0xc0, 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4) + bytes(*ip)
    private val aaaa = bytes(0xc0, 12, 0, 28, 0, 1, 0, 0, 0, 60, 0, 16) + ByteArray(16)
    private val answer = bytes(0x12, 0x34, 0x81, 0x80, 0, 1, 0, 4, 0, 0, 0, 0) + question + cname + a(93, 184, 216, 34) + a(93, 184, 216, 35) + aaaa

    @Test fun `the a and aaaa records are read past a cname`() {
        assertEquals(
            listOf(listOf(93, 184, 216, 34), listOf(93, 184, 216, 35), List(16) { 0 }),
            SeenHosts.addresses(answer).map { ip -> ip.map { it.toInt() and 0xff } },
        )
    }

    @Test fun `a broken answer gives what it could`() {
        assertEquals(emptyList<ByteArray>(), SeenHosts.addresses(ByteArray(5)))
        assertEquals(0, SeenHosts.addresses(answer.copyOf(30)).size)
        assertEquals(1, SeenHosts.addresses(answer.copyOf(answer.size - 40)).size)
    }

    @Test fun `an address leads back to its name`() {
        val seen = SeenHosts()
        seen.learn(answer, "example.org")
        assertEquals("example.org", seen.nameOf(bytes(93, 184, 216, 35)))
        assertNull(seen.nameOf(bytes(1, 1, 1, 1)))
    }

    @Test fun `the oldest address gives way`() {
        val seen = SeenHosts(limit = 2)
        seen.learn(answer, "example.org")
        assertNull(seen.nameOf(bytes(93, 184, 216, 34)))
        assertEquals("example.org", seen.nameOf(bytes(93, 184, 216, 35)))
        assertEquals("example.org", seen.nameOf(ByteArray(16)))
    }

    @Test fun `quic to a blocked host is dropped and nothing else`() {
        val list = Blocklist() + "example.org"
        assertTrue(list.dropsDatagram(443, "www.example.org"))
        assertFalse(list.dropsDatagram(443, "www.wikipedia.org"))
        assertFalse(list.dropsDatagram(443, null))
        assertFalse(list.dropsDatagram(3478, "www.example.org"))
    }
}
