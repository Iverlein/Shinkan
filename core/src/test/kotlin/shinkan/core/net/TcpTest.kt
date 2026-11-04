package shinkan.core.net

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TcpTest {
    private val phone = byteArrayOf(10, 111, 0, 1)
    private val server = byteArrayOf(93, 100, 40, 34)

    /** A segment from the device. */
    private fun from(seq: Long, ack: Long, flags: Int, payload: ByteArray = ByteArray(0), window: Int = 65535, mss: Int? = null) =
        TcpSegment.parse(TcpSegment.build(phone, server, 40000, 443, seq, ack, flags, window, payload, mss))!!

    private fun read(step: TcpFlow.Step) = step.packets.map { TcpSegment.parse(it)!! }

    private val isn = 5000L

    /** A flow through its handshake, the device's next byte at 1001. */
    private fun open(window: Int = 65535, mss: Int? = 1460, start: Long = 1000, serverIsn: Long = isn): TcpFlow {
        val flow = TcpFlow(from(start, 0, TcpSegment.SYN, window = window, mss = mss), serverIsn)
        flow.connected()
        flow.segment(from((start + 1) and 0xffffffffL, (serverIsn + 1) and 0xffffffffL, TcpSegment.ACK, window = window))
        return flow
    }

    @Test fun `a built segment reads back with its checksums right`() {
        val bytes = TcpSegment.build(phone, server, 40000, 443, 0xfffffff0L, 7, TcpSegment.ACK or TcpSegment.PSH, 1000, "hello".toByteArray(), 1400)
        val t = TcpSegment.parse(bytes)!!
        assertEquals(40000, t.srcPort)
        assertEquals(443, t.dstPort)
        assertEquals(0xfffffff0L, t.seq)
        assertEquals(7L, t.ack)
        assertEquals(1000, t.window)
        assertEquals(1400, t.mss)
        assertTrue(t.has(TcpSegment.PSH) && !t.has(TcpSegment.SYN))
        assertArrayEquals("hello".toByteArray(), t.payload)
        assertEquals(0, Checksum.of(bytes, 0, 20))
        val length = bytes.size - 20
        val pseudo = phone + server + byteArrayOf(0, 6, (length shr 8).toByte(), length.toByte())
        assertEquals(0, Checksum.of(pseudo + bytes.copyOfRange(20, bytes.size), 0, 12 + length))
    }

    private val phone6 = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 1 }
    private val server6 = ByteArray(16).also { it[0] = 0x26; it[1] = 6; it[15] = 0x11 }

    /** The Internet checksum over the IPv6 pseudo header and what follows the 40-byte header. */
    private fun sum6(bytes: ByteArray, protocol: Int): Int {
        val length = bytes.size - 40
        val pseudo = bytes.copyOfRange(8, 40) + byteArrayOf(0, 0, (length shr 8).toByte(), length.toByte(), 0, 0, 0, protocol.toByte())
        return Checksum.of(pseudo + bytes.copyOfRange(40, bytes.size), 0, 40 + length)
    }

    @Test fun `a segment over ipv6 reads back with its checksum right`() {
        val bytes = TcpSegment.build(phone6, server6, 40000, 443, 9, 7, TcpSegment.ACK, 1000, "hello".toByteArray(), 1400)
        assertEquals(6, (bytes[0].toInt() shr 4) and 0xf)
        assertEquals(6, bytes[6].toInt())
        val t = TcpSegment.parse(bytes)!!
        assertEquals(server6.toList(), t.dst.toList())
        assertEquals(1400, t.mss)
        assertArrayEquals("hello".toByteArray(), t.payload)
        assertEquals(0, sum6(bytes, 6))
    }

    @Test fun `a datagram over ipv6 reads back with its checksum right`() {
        val bytes = UdpDatagram.build(phone6, server6, 5000, 443, "quic".toByteArray())
        val d = UdpDatagram.parse(bytes)!!
        assertEquals(443, d.dstPort)
        assertEquals(phone6.toList(), d.src.toList())
        assertArrayEquals("quic".toByteArray(), d.payload)
        assertEquals(0, sum6(bytes, 17))
    }

    @Test fun `an ipv6 packet with an extension header is not read`() {
        val bytes = TcpSegment.build(phone6, server6, 40000, 443, 9, 7, TcpSegment.ACK, 1000)
        bytes[6] = 0
        assertNull(TcpSegment.parse(bytes))
        assertNull(UdpDatagram.parse(bytes))
    }

    @Test fun `a flow over ipv6 answers in ipv6`() {
        val syn = TcpSegment.parse(TcpSegment.build(phone6, server6, 40000, 443, 1000, 0, TcpSegment.SYN, 65535, mss = 1440))!!
        val answer = read(TcpFlow(syn, isn).connected()).single()
        assertEquals(phone6.toList(), answer.dst.toList())
        assertEquals(server6.toList(), answer.src.toList())
        assertEquals(1001L, TcpSegment.parse(syn.reset())!!.ack)
    }

    @Test fun `what is not tcp is left alone`() {
        assertNull(TcpSegment.parse(UdpDatagram.build(phone, server, 1, 2, ByteArray(30))))
        assertNull(TcpSegment.parse(ByteArray(10)))
    }

    @Test fun `a reset acknowledges what the segment took`() {
        val syn = TcpSegment.parse(from(1000, 0, TcpSegment.SYN).reset())!!
        assertTrue(syn.has(TcpSegment.RST) && syn.has(TcpSegment.ACK))
        assertEquals(1001L, syn.ack)
        assertEquals(40000, syn.dstPort)
        assertEquals(1006L, TcpSegment.parse(from(1001, 7, TcpSegment.ACK or TcpSegment.FIN, "abcd".toByteArray()).reset())!!.ack)
        assertEquals(7L, TcpSegment.parse(from(1001, 7, TcpSegment.ACK).reset())!!.seq)
        assertEquals(0L, TcpSegment.parse(from(0xffffffffL, 0, TcpSegment.SYN).reset())!!.ack)
    }

    @Test fun `the device's syn is answered once the far side has`() {
        val flow = TcpFlow(from(1000, 0, TcpSegment.SYN, mss = 1460), isn)
        val answer = read(flow.connected()).single()
        assertTrue(answer.has(TcpSegment.SYN) && answer.has(TcpSegment.ACK))
        assertEquals(isn, answer.seq)
        assertEquals(1001L, answer.ack)
        assertEquals(TcpFlow.MSS, answer.mss)
        assertEquals(phone.toList(), answer.dst.toList())
    }

    @Test fun `a refused far side resets the device`() {
        val step = TcpFlow(from(1000, 0, TcpSegment.SYN), isn).failed()
        assertTrue(read(step).single().has(TcpSegment.RST))
        assertTrue(step.close)
    }

    @Test fun `a repeated syn gets the same answer`() {
        val flow = TcpFlow(from(1000, 0, TcpSegment.SYN), isn)
        assertTrue(flow.segment(from(1000, 0, TcpSegment.SYN)).packets.isEmpty())
        flow.connected()
        assertEquals(isn, read(flow.segment(from(1000, 0, TcpSegment.SYN))).single().seq)
    }

    @Test fun `the device's data goes on and is acknowledged`() {
        val flow = open()
        val step = flow.segment(from(1001, isn + 1, TcpSegment.ACK or TcpSegment.PSH, "abc".toByteArray()))
        assertArrayEquals("abc".toByteArray(), step.write)
        assertEquals(1004L, read(step).single().ack)
    }

    @Test fun `data past the room left waits to come again`() {
        val flow = open()
        val data = from(1001, isn + 1, TcpSegment.ACK or TcpSegment.PSH, "abc".toByteArray())
        val held = flow.segment(data, free = 2)
        assertNull(held.write)
        assertTrue(held.packets.isEmpty())
        assertArrayEquals("abc".toByteArray(), flow.segment(data).write)
    }

    @Test fun `the window told to the device follows the room left`() {
        val flow = open()
        val ack = read(flow.segment(from(1001, isn + 1, TcpSegment.ACK, ByteArray(1000)), free = 3000)).single()
        assertEquals(2000, ack.window)
        assertEquals(TcpFlow.WINDOW, read(flow.segment(from(2001, isn + 1, TcpSegment.ACK, ByteArray(1000)), free = 200_000)).single().window)
    }

    @Test fun `a window closed is opened by the writes catching up`() {
        val flow = open()
        val closed = read(flow.segment(from(1001, isn + 1, TcpSegment.ACK, ByteArray(1000)), free = 1000)).single()
        assertEquals(0, closed.window)
        assertTrue(flow.drained(TcpFlow.MSS).packets.isEmpty())
        val update = read(flow.drained(100_000)).single()
        assertEquals(TcpFlow.WINDOW, update.window)
        assertEquals(2001L, update.ack)
        assertTrue(flow.drained(100_000).packets.isEmpty())
    }

    @Test fun `data sent again is not written again`() {
        val flow = open()
        val data = from(1001, isn + 1, TcpSegment.ACK or TcpSegment.PSH, "abc".toByteArray())
        flow.segment(data)
        val again = flow.segment(data)
        assertNull(again.write)
        assertEquals(1004L, read(again).single().ack)
    }

    @Test fun `a repeat with more on it gives only the new part`() {
        val flow = open()
        flow.segment(from(1001, isn + 1, TcpSegment.ACK, "abc".toByteArray()))
        assertArrayEquals("de".toByteArray(), flow.segment(from(1001, isn + 1, TcpSegment.ACK, "abcde".toByteArray())).write)
    }

    @Test fun `data ahead of a gap is dropped and the gap asked for`() {
        val flow = open()
        val step = flow.segment(from(2000, isn + 1, TcpSegment.ACK, "x".toByteArray()))
        assertNull(step.write)
        assertEquals(1001L, read(step).single().ack)
    }

    @Test fun `the far side's data is cut to the device's mss`() {
        val flow = open(mss = 1000)
        val out = read(flow.fromServer(ByteArray(2500)))
        assertEquals(listOf(1000, 1000, 500), out.map { it.payload.size })
        assertEquals(listOf(isn + 1, isn + 1001, isn + 2001), out.map { it.seq })
    }

    @Test fun `no more goes than the device's window, and the rest follows its ack`() {
        val flow = open(window = 3000, mss = 1000)
        assertEquals(3, flow.fromServer(ByteArray(5000)).packets.size)
        val next = read(flow.segment(from(1001, isn + 1 + 2000, TcpSegment.ACK, window = 3000)))
        assertEquals(2, next.size)
        assertEquals(isn + 3001, next.first().seq)
    }

    @Test fun `a window of nothing holds everything until it opens`() {
        val flow = open(mss = 1000)
        flow.segment(from(1001, isn + 1, TcpSegment.ACK, window = 0))
        assertTrue(flow.fromServer(ByteArray(100)).packets.isEmpty())
        assertEquals(100, read(flow.segment(from(1001, isn + 1, TcpSegment.ACK, window = 2000))).single().payload.size)
    }

    @Test fun `room shrinks with what is held and grows with acks`() {
        val flow = open(mss = 1000, window = 2000)
        flow.fromServer(ByteArray(2000))
        assertEquals(TcpFlow.BUFFER - 2000, flow.room)
        flow.segment(from(1001, isn + 2001, TcpSegment.ACK, window = 2000))
        assertEquals(TcpFlow.BUFFER, flow.room)
    }

    @Test fun `what is not acknowledged goes again after the wait`() {
        val flow = open(mss = 1000)
        flow.fromServer(ByteArray(1000), at = 1000)
        assertTrue(flow.tick(1000 + TcpFlow.RESEND_MS - 1).packets.isEmpty())
        assertEquals(isn + 1, read(flow.tick(1000 + TcpFlow.RESEND_MS)).single().seq)
    }

    @Test fun `three of the same ack send the first segment again`() {
        val flow = open(mss = 1000)
        flow.fromServer(ByteArray(3000))
        val same = from(1001, isn + 1001, TcpSegment.ACK)
        flow.segment(same)
        assertTrue(flow.segment(same).packets.isEmpty())
        assertTrue(flow.segment(same).packets.isEmpty())
        assertEquals(isn + 1001, read(flow.segment(same)).single().seq)
    }

    @Test fun `sequence numbers wrap`() {
        val start = 0xffffffffL - 2
        val serverIsn = 0xffffffffL - 500
        val flow = open(start = start, serverIsn = serverIsn, mss = 1000)
        val step = flow.segment(from((start + 1) and 0xffffffffL, (serverIsn + 1) and 0xffffffffL, TcpSegment.ACK, ByteArray(10)))
        assertEquals(10, step.write!!.size)
        assertEquals((start + 11) and 0xffffffffL, read(step).single().ack)
        val out = read(flow.fromServer(ByteArray(1500)))
        assertEquals(2, out.size)
        val after = (serverIsn + 1501) and 0xffffffffL
        flow.segment(from((start + 11) and 0xffffffffL, after, TcpSegment.ACK))
        assertEquals(TcpFlow.BUFFER, flow.room)
    }

    @Test fun `the device closing half closes the far side, which may still answer`() {
        val flow = open()
        val step = flow.segment(from(1001, isn + 1, TcpSegment.ACK or TcpSegment.FIN))
        assertTrue(step.shutdown)
        assertFalse(step.close)
        assertEquals(1002L, read(step).single().ack)
        assertEquals(5, read(flow.fromServer(ByteArray(5))).single().payload.size)
    }

    @Test fun `the far side's end comes after its data, and both ends close the flow`() {
        val flow = open(mss = 1000, window = 1000)
        flow.fromServer(ByteArray(2000))
        assertTrue(flow.serverDone().packets.isEmpty())
        val rest = read(flow.segment(from(1001, isn + 1001, TcpSegment.ACK, window = 1000)))
        assertEquals(1000, rest.first().payload.size)
        assertTrue(rest.last().has(TcpSegment.FIN))
        assertEquals(isn + 2001, rest.last().seq)
        assertFalse(flow.segment(from(1001, isn + 2002, TcpSegment.ACK)).close)
        assertTrue(flow.segment(from(1001, isn + 2002, TcpSegment.ACK or TcpSegment.FIN)).close)
        assertTrue(flow.closed)
    }

    @Test fun `no more is taken from a far side that has ended`() {
        val flow = open()
        flow.serverDone()
        assertEquals(0, flow.room)
    }

    @Test fun `a lost fin goes again`() {
        val flow = open()
        flow.tick(0)
        flow.serverDone(at = 100)
        val again = read(flow.tick(100 + TcpFlow.RESEND_MS))
        assertTrue(again.single().has(TcpSegment.FIN))
    }

    @Test fun `a reset from the device ends the flow at once`() {
        val flow = open()
        val step = flow.segment(from(1001, isn + 1, TcpSegment.RST))
        assertTrue(step.close && step.packets.isEmpty())
    }

    @Test fun `a long silence ends the flow`() {
        val flow = open()
        val step = flow.tick(TcpFlow.IDLE_MS + 1)
        assertTrue(step.close)
        assertNotNull(read(step).single().takeIf { it.has(TcpSegment.RST) })
    }

    @Test fun `a long transfer both ways arrives whole through a lossy path`() {
        val random = java.util.Random(11)
        val flow = open(mss = 1000, window = 8000)
        val body = ByteArray(300_000).also(random::nextBytes)
        var at = 0L
        var taken = 0
        var deviceNext = isn + 1
        val received = java.io.ByteArrayOutputStream()
        val pending = ArrayDeque<TcpSegment>()
        var rounds = 0
        while ((received.size() < body.size) && rounds++ < 200_000) {
            at += 5
            val n = minOf(flow.room, body.size - taken, 4000)
            if (n > 0) {
                read(flow.fromServer(body.copyOfRange(taken, taken + n), at)).forEach { pending += it }
                taken += n
            }
            read(flow.tick(at)).forEach { pending += it }
            while (pending.isNotEmpty()) {
                val t = pending.removeFirst()
                if (random.nextInt(100) < 5) continue // lost on the way
                if (t.seq == deviceNext && t.payload.isNotEmpty()) {
                    received.write(t.payload)
                    deviceNext += t.payload.size
                }
                read(flow.segment(from(1001, deviceNext, TcpSegment.ACK, window = 8000), at = at)).forEach { pending += it }
            }
        }
        assertArrayEquals(body, received.toByteArray())
    }
}
