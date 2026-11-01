package shinkan.core.net

import java.nio.ByteBuffer

/**
 * The little of DNS the filter needs (SPEC §5.3): the first question's name
 * and type from a query, and a refusal built on the query. Everything else
 * is passed through untouched.
 */
data class DnsQuestion(val name: String, val type: Int, val id: Int) {
    val isAddressQuery get() = type == TYPE_A || type == TYPE_AAAA || type == TYPE_HTTPS

    companion object {
        const val TYPE_A = 1
        const val TYPE_AAAA = 28
        const val TYPE_HTTPS = 65

        /** The first question of a DNS message, or null when the bytes are not a query with one. */
        fun parse(message: ByteArray): DnsQuestion? {
            if (message.size < 12) return null
            val b = ByteBuffer.wrap(message)
            val id = b.short.toInt() and 0xffff
            val flags = b.short.toInt() and 0xffff
            if (flags and 0x8000 != 0) return null            // a response
            val questions = b.short.toInt() and 0xffff
            if (questions < 1) return null
            b.position(12)
            val labels = mutableListOf<String>()
            while (true) {
                if (!b.hasRemaining()) return null
                val len = b.get().toInt() and 0xff
                if (len == 0) break
                if (len and 0xc0 != 0 || b.remaining() < len) return null   // compression has no place in a question
                val label = ByteArray(len).also(b::get)
                labels += String(label, Charsets.US_ASCII)
            }
            if (b.remaining() < 4) return null
            val type = b.short.toInt() and 0xffff
            return DnsQuestion(labels.joinToString(".").lowercase(), type, id)
        }

        /**
         * A reply to [query] with NXDOMAIN (SPEC §5.3): same id and question, answer
         * authoritative, no records. Browsers and apps show "site not found" and stop.
         */
        fun refuse(query: ByteArray): ByteArray {
            val questionEnd = questionEnd(query) ?: return ByteArray(0)
            val reply = query.copyOf(questionEnd)
            reply[2] = (0x85).toByte()      // QR=1, opcode 0, AA=1, TC=0, RD as asked
            reply[2] = (reply[2].toInt() or (query[2].toInt() and 0x01)).toByte()
            reply[3] = (0x83).toByte()      // RA=1, rcode NXDOMAIN(3)
            reply[4] = 0; reply[5] = 1      // one question
            reply[6] = 0; reply[7] = 0      // no answers
            reply[8] = 0; reply[9] = 0      // no authority
            reply[10] = 0; reply[11] = 0    // no additional
            return reply
        }

        private fun questionEnd(message: ByteArray): Int? {
            if (message.size < 12) return null
            var i = 12
            while (i < message.size) {
                val len = message[i].toInt() and 0xff
                if (len == 0) { i += 1; break }
                i += 1 + len
            }
            return if (i + 4 <= message.size) i + 4 else null
        }
    }
}
