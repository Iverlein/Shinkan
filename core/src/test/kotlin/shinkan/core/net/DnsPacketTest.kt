package shinkan.core.net

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DnsPacketTest {
    // A query for example.org, type A, id 0x1234, RD set.
    private val query = byteArrayOf(
        0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0,
        7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
        3, 'o'.code.toByte(), 'r'.code.toByte(), 'g'.code.toByte(), 0,
        0, 1, 0, 1,
    )

    @Test fun `the question is read`() {
        val q = DnsQuestion.parse(query)!!
        assertEquals("example.org", q.name)
        assertEquals(DnsQuestion.TYPE_A, q.type)
        assertEquals(0x1234, q.id)
        assertTrue(q.isAddressQuery)
    }

    @Test fun `a response and a truncated query are not questions`() {
        assertNull(DnsQuestion.parse(query.copyOf().also { it[2] = 0x81.toByte() }))
        assertNull(DnsQuestion.parse(query.copyOf(20)))
        assertNull(DnsQuestion.parse(ByteArray(5)))
    }

    @Test fun `the refusal keeps id and question and says nxdomain`() {
        val r = DnsQuestion.refuse(query)
        assertEquals(query.size, r.size)
        assertEquals(0x12, r[0].toInt()); assertEquals(0x34, r[1].toInt())
        assertEquals(0x85, r[2].toInt() and 0xff)   // QR, AA, RD
        assertEquals(0x83, r[3].toInt() and 0xff)   // RA, NXDOMAIN
        assertArrayEquals(query.copyOfRange(12, query.size), r.copyOfRange(12, r.size))
    }

    @Test fun `rfc 1071 checksum example`() {
        val data = byteArrayOf(0x00, 0x01, 0xf2.toByte(), 0x03, 0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte())
        assertEquals(0x220d, Checksum.of(data, 0, data.size))
    }

    @Test fun `a built datagram parses back and verifies`() {
        val src = byteArrayOf(10, 111, 0, 2); val dst = byteArrayOf(10, 111, 0, 1)
        val bytes = Udp4.build(src, dst, 53, 40000, query)
        val u = Udp4.parse(bytes)!!
        assertArrayEquals(src, u.src); assertArrayEquals(dst, u.dst)
        assertEquals(53, u.srcPort); assertEquals(40000, u.dstPort)
        assertArrayEquals(query, u.payload)
        assertEquals(0, Checksum.of(bytes, 0, 20))   // a header with its checksum sums to zero
        assertNull(Udp4.parse(bytes.copyOf(20)))
        assertNull(Udp4.parse(bytes.copyOf().also { it[9] = 6 }))   // TCP
    }
}
