package shinkan.core.net

import java.nio.ByteBuffer

/**
 * IPv4/UDP datagrams as the tun device hands them (SPEC §5.2): read one,
 * build the reply. IPv6 is routed to the filter the same way; block 8.
 */
data class Udp4(val src: ByteArray, val dst: ByteArray, val srcPort: Int, val dstPort: Int, val payload: ByteArray) {
    companion object {
        /** The datagram in [packet], or null when it is not IPv4/UDP or is truncated. */
        fun parse(packet: ByteArray, length: Int = packet.size): Udp4? {
            if (length < 28) return null
            val version = (packet[0].toInt() shr 4) and 0xf
            if (version != 4) return null
            val ihl = (packet[0].toInt() and 0xf) * 4
            if (packet[9].toInt() != 17 || length < ihl + 8) return null
            val b = ByteBuffer.wrap(packet, 0, length)
            val total = b.getShort(2).toInt() and 0xffff
            val src = packet.copyOfRange(12, 16)
            val dst = packet.copyOfRange(16, 20)
            val srcPort = b.getShort(ihl).toInt() and 0xffff
            val dstPort = b.getShort(ihl + 2).toInt() and 0xffff
            val udpLen = b.getShort(ihl + 4).toInt() and 0xffff
            val end = minOf(total, length, ihl + udpLen)
            if (end < ihl + 8) return null
            return Udp4(src, dst, srcPort, dstPort, packet.copyOfRange(ihl + 8, end))
        }

        /** A datagram with [payload] going back the other way, checksums set. */
        fun build(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
            val udpLen = 8 + payload.size
            val total = 20 + udpLen
            val p = ByteBuffer.allocate(total)
            p.put(0x45).put(0).putShort(total.toShort()).putShort(0).putShort(0x4000.toShort())
                .put(64).put(17).putShort(0).put(src).put(dst)
            p.putShort(srcPort.toShort()).putShort(dstPort.toShort()).putShort(udpLen.toShort()).putShort(0).put(payload)
            val bytes = p.array()
            val ipSum = Checksum.of(bytes, 0, 20)
            bytes[10] = (ipSum shr 8).toByte(); bytes[11] = ipSum.toByte()
            val pseudo = ByteBuffer.allocate(12).put(src).put(dst).put(0).put(17).putShort(udpLen.toShort()).array()
            val udpSum = Checksum.of(pseudo + bytes.copyOfRange(20, total), 0, 12 + udpLen).let { if (it == 0) 0xffff else it }
            bytes[26] = (udpSum shr 8).toByte(); bytes[27] = udpSum.toByte()
            return bytes
        }
    }
}

/** The Internet checksum of RFC 1071. */
object Checksum {
    /** [start] is added in first, a pseudo header's sum for one. */
    fun of(data: ByteArray, offset: Int, length: Int, start: Long = 0): Int {
        var sum = start
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xff) shl 8) or (data[i + 1].toInt() and 0xff)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xff) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
        return sum.inv().toInt() and 0xffff
    }
}
