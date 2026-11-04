package shinkan.core.net

import java.nio.ByteBuffer

/** An IPv4/TCP segment as the tun device hands it (SPEC §5.2). Sequence numbers are unsigned, in a Long. */
class Tcp4(
    val src: ByteArray,
    val dst: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val seq: Long,
    val ack: Long,
    val flags: Int,
    val window: Int,
    /** The MSS option of a SYN, or null. */
    val mss: Int?,
    val payload: ByteArray,
) {
    fun has(flag: Int): Boolean = flags and flag != 0

    companion object {
        const val FIN = 1
        const val SYN = 2
        const val RST = 4
        const val PSH = 8
        const val ACK = 16

        /** The segment in [packet], or null when it is not IPv4/TCP or is truncated. */
        fun parse(packet: ByteArray, length: Int = packet.size): Tcp4? {
            if (length < 40 || (packet[0].toInt() shr 4) and 0xf != 4 || packet[9].toInt() != 6) return null
            val ihl = (packet[0].toInt() and 0xf) * 4
            if (length < ihl + 20) return null
            val b = ByteBuffer.wrap(packet, 0, length)
            val end = minOf(b.getShort(2).toInt() and 0xffff, length)
            val offset = ((packet[ihl + 12].toInt() shr 4) and 0xf) * 4
            if (offset < 20 || end < ihl + offset) return null
            var mss: Int? = null
            var i = ihl + 20
            while (i < ihl + offset) {
                when (val kind = packet[i].toInt() and 0xff) {
                    0 -> break
                    1 -> i++
                    else -> {
                        if (i + 1 >= ihl + offset) break
                        val size = packet[i + 1].toInt() and 0xff
                        if (size < 2) break
                        if (kind == 2 && size == 4) mss = b.getShort(i + 2).toInt() and 0xffff
                        i += size
                    }
                }
            }
            return Tcp4(
                packet.copyOfRange(12, 16), packet.copyOfRange(16, 20),
                b.getShort(ihl).toInt() and 0xffff, b.getShort(ihl + 2).toInt() and 0xffff,
                b.getInt(ihl + 4).toLong() and 0xffffffffL, b.getInt(ihl + 8).toLong() and 0xffffffffL,
                packet[ihl + 13].toInt() and 0x3f, b.getShort(ihl + 14).toInt() and 0xffff,
                mss, packet.copyOfRange(ihl + offset, end),
            )
        }

        /** A segment with its checksums set, carrying the MSS option when [mss] is given. */
        fun build(
            src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int,
            seq: Long, ack: Long, flags: Int, window: Int, payload: ByteArray = EMPTY, mss: Int? = null,
        ): ByteArray {
            val tcpHeader = if (mss == null) 20 else 24
            val tcpLength = tcpHeader + payload.size
            val total = 20 + tcpLength
            val p = ByteBuffer.allocate(total)
            p.put(0x45).put(0).putShort(total.toShort()).putShort(0).putShort(0x4000.toShort())
                .put(64).put(6).putShort(0).put(src).put(dst)
            p.putShort(srcPort.toShort()).putShort(dstPort.toShort()).putInt(seq.toInt()).putInt(ack.toInt())
                .put(((tcpHeader / 4) shl 4).toByte()).put(flags.toByte()).putShort(window.toShort()).putShort(0).putShort(0)
            if (mss != null) p.put(2).put(4).putShort(mss.toShort())
            p.put(payload)
            val bytes = p.array()
            val ipSum = Checksum.of(bytes, 0, 20)
            bytes[10] = (ipSum shr 8).toByte()
            bytes[11] = ipSum.toByte()
            // The pseudo header's sum is carried in, so the segment is not copied to be summed.
            var pseudo = 6L + tcpLength
            for (i in 12 until 20 step 2) pseudo += ((bytes[i].toInt() and 0xff) shl 8) or (bytes[i + 1].toInt() and 0xff)
            val sum = Checksum.of(bytes, 20, tcpLength, pseudo)
            bytes[36] = (sum shr 8).toByte()
            bytes[37] = sum.toByte()
            return bytes
        }

        private val EMPTY = ByteArray(0)
    }
}

/**
 * One connection of the relay (SPEC §5.2), as a state machine with no socket in it:
 * the device's side is the segments it is given, the far side what the app reports,
 * and each call says what to do next in a [Step]. Segments come over a tun device in
 * order and are rarely lost, so this is TCP in its plainest form: no options but the
 * MSS, no window scaling, a segment out of order dropped and asked for again.
 */
class TcpFlow(syn: Tcp4, isn: Long, private var now: Long = 0) {
    /** What follows a call: [packets] for the tunnel, [write] for the far side, then a half or a whole close. */
    class Step(
        val packets: List<ByteArray> = emptyList(),
        val write: ByteArray? = null,
        val shutdown: Boolean = false,
        val close: Boolean = false,
    )

    private enum class State { CONNECTING, OPENING, OPEN, CLOSED }

    private class Chunk(val seq: Long, val data: ByteArray)

    private val local = syn.dst
    private val remote = syn.src
    private val localPort = syn.dstPort
    private val remotePort = syn.srcPort
    private val mss = minOf(syn.mss ?: 536, MSS)
    private var state = State.CONNECTING
    private var clientNext = (syn.seq + 1) and MASK
    private var clientWindow = syn.window
    private var isn = isn and MASK
    private var sendNext = (isn + 1) and MASK
    private var acked = sendNext
    private val sent = ArrayDeque<Chunk>()
    private val waiting = ArrayDeque<ByteArray>()
    private var queued = 0
    private var finWanted = false
    private var finSeq: Long? = null
    private var clientFin = false
    private var lastProgress = now
    private var lastActivity = now
    private var repeats = 0

    val closed: Boolean get() = state == State.CLOSED

    /** How many bytes of the far side's may be taken now. */
    val room: Int get() = if (finWanted || closed) 0 else maxOf(0, BUFFER - queued)

    private fun segment(seq: Long, flags: Int, payload: ByteArray = ByteArray(0), mss: Int? = null) =
        Tcp4.build(local, remote, localPort, remotePort, seq, clientNext, flags, WINDOW, payload, mss)

    /** The far side answered: the device gets its SYN ACK. */
    fun connected(): Step {
        if (closed) return Step(close = true)
        state = State.OPENING
        return Step(listOf(synAck()))
    }

    private fun synAck() = segment(isn, Tcp4.SYN or Tcp4.ACK, mss = MSS)

    /** The far side refused or failed: the device gets a reset. */
    fun failed(): Step {
        val wasOpen = !closed
        state = State.CLOSED
        return Step(if (wasOpen) listOf(segment(sendNext, Tcp4.RST or Tcp4.ACK)) else emptyList(), close = true)
    }

    /** [data] came from the far side. */
    fun fromServer(data: ByteArray, at: Long = now): Step {
        now = at
        lastActivity = at
        var i = 0
        while (i < data.size) {
            val end = minOf(i + mss, data.size)
            waiting.addLast(data.copyOfRange(i, end))
            i = end
        }
        queued += data.size
        return Step(flush())
    }

    /** The far side finished sending. */
    fun serverDone(at: Long = now): Step {
        now = at
        finWanted = true
        return finish(flush())
    }

    private fun inFlight(): Int = diff(sendNext, acked)

    /** What may go now: whole chunks within the device's window, then the FIN behind the last of them. */
    private fun flush(): List<ByteArray> {
        if (state != State.OPEN) return emptyList()
        val out = mutableListOf<ByteArray>()
        while (waiting.isNotEmpty() && inFlight() + waiting.first().size <= clientWindow) {
            val data = waiting.removeFirst()
            if (sent.isEmpty()) lastProgress = now
            sent.addLast(Chunk(sendNext, data))
            out += segment(sendNext, Tcp4.ACK or Tcp4.PSH, data)
            sendNext = (sendNext + data.size) and MASK
        }
        if (finWanted && waiting.isEmpty() && finSeq == null) {
            finSeq = sendNext
            out += segment(sendNext, Tcp4.FIN or Tcp4.ACK)
            sendNext = (sendNext + 1) and MASK
        }
        return out
    }

    private fun finish(packets: List<ByteArray>, write: ByteArray? = null, shutdown: Boolean = false): Step {
        val finAcked = finSeq?.let { diff(acked, it) > 0 } == true
        if (clientFin && finAcked) {
            state = State.CLOSED
            return Step(packets, write, shutdown, close = true)
        }
        return Step(packets, write, shutdown)
    }

    /**
     * A segment from the device. [accept] is false while the far side's writes lag:
     * new data is then left unacknowledged, to come again.
     */
    fun segment(t: Tcp4, accept: Boolean = true, at: Long = now): Step {
        now = at
        lastActivity = at
        if (closed) return Step(close = true)
        if (t.has(Tcp4.RST)) {
            state = State.CLOSED
            return Step(close = true)
        }
        if (t.has(Tcp4.SYN)) return Step(if (state == State.OPENING) listOf(synAck()) else emptyList())
        if (state == State.CONNECTING) return Step()
        val out = mutableListOf<ByteArray>()
        if (t.has(Tcp4.ACK)) acknowledged(t, out)

        var write: ByteArray? = null
        var shutdown = false
        val offset = diff(clientNext, t.seq)
        val fresh = t.payload.size - offset
        if (t.payload.isNotEmpty()) {
            if (offset in 0 until t.payload.size && accept) {
                write = if (offset == 0) t.payload else t.payload.copyOfRange(offset, t.payload.size)
                clientNext = (clientNext + fresh) and MASK
            }
            if (accept) out += segment(sendNext, Tcp4.ACK)
        }
        if (t.has(Tcp4.FIN) && !clientFin && diff((t.seq + t.payload.size) and MASK, clientNext) == 0) {
            clientNext = (clientNext + 1) and MASK
            clientFin = true
            shutdown = true
            out += segment(sendNext, Tcp4.ACK)
        }
        out += flush()
        return finish(out, write, shutdown)
    }

    private fun acknowledged(t: Tcp4, out: MutableList<ByteArray>) {
        clientWindow = t.window
        val advance = diff(t.ack, acked)
        if (state == State.OPENING && diff(t.ack, sendNext) == 0) state = State.OPEN
        if (advance > 0 && diff(sendNext, t.ack) >= 0) {
            acked = t.ack
            repeats = 0
            lastProgress = now
            while (sent.isNotEmpty() && diff(acked, sent.first().seq) >= sent.first().data.size) {
                queued -= sent.removeFirst().data.size
            }
        } else if (advance == 0 && t.payload.isEmpty() && sent.isNotEmpty() && ++repeats == 3) {
            // Three of the same: the first unacknowledged segment was lost.
            val first = sent.first()
            out += segment(first.seq, Tcp4.ACK or Tcp4.PSH, first.data)
        }
    }

    /** Time passing: what was sent and not acknowledged goes again; a long silence ends the flow. */
    fun tick(at: Long): Step {
        now = at
        if (closed) return Step(close = true)
        if (at - lastActivity > IDLE_MS) {
            state = State.CLOSED
            return Step(listOf(segment(sendNext, Tcp4.RST or Tcp4.ACK)), close = true)
        }
        if (state == State.OPEN && (sent.isNotEmpty() || unackedFin()) && at - lastProgress >= RESEND_MS) {
            lastProgress = at
            val out = sent.map { segment(it.seq, Tcp4.ACK or Tcp4.PSH, it.data) }.toMutableList()
            finSeq?.takeIf { unackedFin() }?.let { out += segment(it, Tcp4.FIN or Tcp4.ACK) }
            return Step(out)
        }
        return Step()
    }

    private fun unackedFin(): Boolean = finSeq?.let { diff(acked, it) <= 0 } == true

    companion object {
        const val MSS = 1400
        const val WINDOW = 65535
        const val BUFFER = 256 * 1024
        const val RESEND_MS = 400L
        const val IDLE_MS = 10 * 60 * 1000L
        private const val MASK = 0xffffffffL

        /** [a] minus [b] in the sequence space, signed, so it holds across the wrap. */
        internal fun diff(a: Long, b: Long): Int = (a - b).toInt()
    }
}
