package shinkan.core.net

import java.nio.ByteBuffer

/**
 * The IP layer of what the tun device hands over (SPEC §5.2), both families: where
 * the protocol's own header starts and the packet ends. An IPv6 packet with extension
 * headers is not read, and is dropped as anything unread is.
 */
internal class IpPacket(val protocol: Int, val start: Int, val end: Int, val src: ByteArray, val dst: ByteArray) {
    companion object {
        const val TCP = 6
        const val UDP = 17
        private const val V6_HEADER = 40

        fun parse(packet: ByteArray, length: Int): IpPacket? {
            if (length < 20) return null
            val b = ByteBuffer.wrap(packet, 0, length)
            return when ((packet[0].toInt() shr 4) and 0xf) {
                4 -> {
                    val ihl = (packet[0].toInt() and 0xf) * 4
                    if (ihl < 20 || length < ihl) return null
                    val end = minOf(b.getShort(2).toInt() and 0xffff, length)
                    IpPacket(packet[9].toInt() and 0xff, ihl, end, packet.copyOfRange(12, 16), packet.copyOfRange(16, 20))
                }
                6 -> {
                    if (length < V6_HEADER) return null
                    val end = minOf(V6_HEADER + (b.getShort(4).toInt() and 0xffff), length)
                    IpPacket(packet[6].toInt() and 0xff, V6_HEADER, end, packet.copyOfRange(8, 24), packet.copyOfRange(24, 40))
                }
                else -> null
            }
        }

        /** The size of the header [build] writes for addresses like [src]. */
        fun headerSize(src: ByteArray) = if (src.size == 16) V6_HEADER else 20

        /** Writes the IP header of a packet of [upper] bytes after it, checksum set. */
        fun build(p: ByteBuffer, src: ByteArray, dst: ByteArray, protocol: Int, upper: Int) {
            if (src.size == 16) {
                p.putInt(0x60000000).putShort(upper.toShort()).put(protocol.toByte()).put(64).put(src).put(dst)
                return
            }
            p.put(0x45).put(0).putShort((20 + upper).toShort()).putShort(0).putShort(0x4000.toShort())
                .put(64).put(protocol.toByte()).putShort(0).put(src).put(dst)
            val sum = Checksum.of(p.array(), 0, 20)
            p.array()[10] = (sum shr 8).toByte()
            p.array()[11] = sum.toByte()
        }

        /** The pseudo header's sum, for [Checksum.of]'s start. */
        fun pseudoSum(src: ByteArray, dst: ByteArray, protocol: Int, upper: Int): Long {
            var sum = protocol.toLong() + upper
            for (address in listOf(src, dst)) {
                for (i in address.indices step 2) sum += ((address[i].toInt() and 0xff) shl 8) or (address[i + 1].toInt() and 0xff)
            }
            return sum
        }
    }
}

/**
 * UDP datagrams as the tun device hands them (SPEC §5.2), over IPv4 or IPv6: read one,
 * build the reply.
 */
data class UdpDatagram(val src: ByteArray, val dst: ByteArray, val srcPort: Int, val dstPort: Int, val payload: ByteArray) {
    companion object {
        /** The datagram in [packet], or null when it is not UDP or is truncated. */
        fun parse(packet: ByteArray, length: Int = packet.size): UdpDatagram? {
            val ip = IpPacket.parse(packet, length) ?: return null
            if (ip.protocol != IpPacket.UDP || ip.end < ip.start + 8) return null
            val b = ByteBuffer.wrap(packet, 0, length)
            val srcPort = b.getShort(ip.start).toInt() and 0xffff
            val dstPort = b.getShort(ip.start + 2).toInt() and 0xffff
            val end = minOf(ip.end, ip.start + (b.getShort(ip.start + 4).toInt() and 0xffff))
            if (end < ip.start + 8) return null
            return UdpDatagram(ip.src, ip.dst, srcPort, dstPort, packet.copyOfRange(ip.start + 8, end))
        }

        /** A datagram with [payload] going back the other way, checksums set. */
        fun build(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
            val header = IpPacket.headerSize(src)
            val udpLen = 8 + payload.size
            val p = ByteBuffer.allocate(header + udpLen)
            IpPacket.build(p, src, dst, IpPacket.UDP, udpLen)
            p.putShort(srcPort.toShort()).putShort(dstPort.toShort()).putShort(udpLen.toShort()).putShort(0).put(payload)
            val bytes = p.array()
            val sum = Checksum.of(bytes, header, udpLen, IpPacket.pseudoSum(src, dst, IpPacket.UDP, udpLen)).let { if (it == 0) 0xffff else it }
            bytes[header + 6] = (sum shr 8).toByte()
            bytes[header + 7] = sum.toByte()
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
