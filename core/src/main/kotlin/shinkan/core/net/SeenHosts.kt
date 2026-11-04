package shinkan.core.net

/**
 * Which name an address was given for (SPEC §5.2): the filter forwards the device's
 * lookups, so it learns this from their answers, and a datagram shows an address and
 * no name. Kept in memory for the newest [limit] addresses, nothing stored (§2.5).
 */
class SeenHosts(private val limit: Int = 4096) {
    private val names = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > limit
    }

    /** Records the addresses in the DNS [answer] as those of the name its question asked for. */
    @Synchronized
    fun learn(answer: ByteArray, name: String) {
        addresses(answer).forEach { names[key(it)] = name }
    }

    @Synchronized
    fun nameOf(address: ByteArray): String? = names[key(address)]

    private fun key(address: ByteArray) = address.joinToString(".") { (it.toInt() and 0xff).toString() }

    companion object {
        private const val TYPE_A = 1
        private const val TYPE_AAAA = 28
        private const val CLASS_IN = 1

        /** The A and AAAA records of a DNS response, whatever name they sit under; none when it cannot be read. */
        fun addresses(message: ByteArray): List<ByteArray> {
            if (message.size < 12) return emptyList()
            fun u16(i: Int) = ((message[i].toInt() and 0xff) shl 8) or (message[i + 1].toInt() and 0xff)
            val questions = u16(4)
            val answers = u16(6)
            var i = 12
            repeat(questions) {
                i = skipName(message, i) ?: return emptyList()
                i += 4
            }
            val out = mutableListOf<ByteArray>()
            repeat(answers) {
                i = skipName(message, i) ?: return out
                if (i + 10 > message.size) return out
                val type = u16(i)
                val kind = u16(i + 2)
                val length = u16(i + 8)
                i += 10
                if (i + length > message.size) return out
                val address = (type == TYPE_A && length == 4) || (type == TYPE_AAAA && length == 16)
                if (address && kind == CLASS_IN) out += message.copyOfRange(i, i + length)
                i += length
            }
            return out
        }

        /** The index after the name at [from]: labels, then a zero or a two-byte pointer. */
        private fun skipName(message: ByteArray, from: Int): Int? {
            var i = from
            while (i < message.size) {
                val length = message[i].toInt() and 0xff
                when {
                    length == 0 -> return i + 1
                    length and 0xc0 == 0xc0 -> return i + 2
                    length and 0xc0 != 0 -> return null
                    else -> i += 1 + length
                }
            }
            return null
        }
    }
}

/**
 * Whether a datagram goes on (SPEC §5.4). Port 443 to a host the list blocks is QUIC
 * to a blocked site: dropped, and the app falls back to TLS. A host unknown goes on.
 * Port 853 is a resolver over QUIC, dropped whoever it is.
 */
fun Blocklist.dropsDatagram(port: Int, host: String?, builtin: Builtin = Builtin.NONE): Boolean =
    port == 853 || (port == 443 && host != null && blocksHost(host, builtin))
