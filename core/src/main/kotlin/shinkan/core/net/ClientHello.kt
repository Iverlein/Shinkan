package shinkan.core.net

/**
 * The name a TLS client asks for (SPEC §5.4), read from the first bytes it sends. A
 * hello can span segments, so a read may need more; what is not a hello, or names
 * nothing, is left alone.
 */
sealed interface ClientHello {
    data class Name(val host: String) : ClientHello
    data object Nameless : ClientHello
    data object NeedMore : ClientHello

    companion object {
        /** More than any hello in one record: a record's own limit and its header. */
        const val MAX = 16384 + 5

        fun read(b: ByteArray, length: Int = b.size): ClientHello {
            if (length == 0) return NeedMore
            if (b[0].toInt() != 22) return Nameless
            if (length < 5) return NeedMore
            val record = u16(b, 3)
            if (length < 5 + record) return if (5 + record > MAX) Nameless else NeedMore
            val end = 5 + record
            var i = 5
            if (i + 38 > end || b[i].toInt() != 1) return Nameless
            i += 38 // type, length, version, random
            i += 1 + u8(b, i) // session id
            if (i + 2 > end) return Nameless
            i += 2 + u16(b, i) // cipher suites
            if (i + 1 > end) return Nameless
            i += 1 + u8(b, i) // compression
            if (i + 2 > end) return Nameless
            val extensionsEnd = minOf(i + 2 + u16(b, i), end)
            i += 2
            while (i + 4 <= extensionsEnd) {
                val type = u16(b, i)
                val size = u16(b, i + 2)
                i += 4
                if (i + size > extensionsEnd) return Nameless
                if (type == 0) return name(b, i, i + size)
                i += size
            }
            return Nameless
        }

        private fun name(b: ByteArray, from: Int, end: Int): ClientHello {
            var i = from + 2
            while (i + 3 <= end) {
                val kind = u8(b, i)
                val size = u16(b, i + 1)
                i += 3
                if (i + size > end) return Nameless
                if (kind == 0) return Name(String(b, i, size, Charsets.US_ASCII).lowercase())
                i += size
            }
            return Nameless
        }

        private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff

        private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    }
}

/** Public resolvers over HTTPS and TLS, refused like any built-in name (SPEC §5.4): an app using one slips the filter. */
internal object KnownResolvers {
    private val hosts = setOf(
        "dns.google", "dns.google.com", "cloudflare-dns.com", "one.one.one.one", "1dot1dot1dot1.cloudflare-dns.com",
        "dns.quad9.net", "dns9.quad9.net", "dns10.quad9.net", "dns11.quad9.net", "doh.opendns.com", "dns.umbrella.com",
        "dns.adguard.com", "dns.adguard-dns.com", "doh.cleanbrowsing.org", "dns.nextdns.io", "doh.mullvad.net",
        "dns.mullvad.net", "doh.dns.sb", "dns.sb", "dns.alidns.com", "doh.pub", "dns.twnic.tw", "doh.libredns.gr",
    )

    fun has(host: String): Boolean = hosts.any { Host.under(host, it) }
}
