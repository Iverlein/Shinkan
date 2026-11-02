package shinkan.core.net

/**
 * One list of the built-in block list (SPEC §6.5), as `tools/blocklist/refresh.py`
 * writes it: lines of names, lowercase ASCII, sorted by bytes, `#` lines first.
 * Held as the file's own bytes with the start of each name, a few hundred
 * thousand names in a few megabytes, and searched by halving.
 */
class BuiltinList(private val bytes: ByteArray) {
    private val starts: IntArray
    private val ends: IntArray

    init {
        val s = ArrayList<Int>()
        val e = ArrayList<Int>()
        var i = 0
        while (i < bytes.size) {
            var j = i
            while (j < bytes.size && bytes[j] != NL) j++
            var end = j
            if (end > i && bytes[end - 1] == CR) end--
            if (end > i && bytes[i] != HASH) {
                s += i
                e += end
            }
            i = j + 1
        }
        starts = s.toIntArray()
        ends = e.toIntArray()
        for (k in 1 until starts.size) {
            require(compare(k - 1, bytes, starts[k], ends[k]) < 0) { "names not sorted by bytes at ${starts[k]}" }
        }
    }

    val size: Int get() = starts.size

    /** True when [name] (lowercase) is a line of the list. */
    operator fun contains(name: String): Boolean {
        val key = name.toByteArray(Charsets.US_ASCII)
        var lo = 0
        var hi = starts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compare(mid, key, 0, key.size)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return true
            }
        }
        return false
    }

    /**
     * True when [host] or a domain above it is in the list: `a.b.example.org`, then
     * `b.example.org`, `example.org`, `org` (SPEC §6.1, every subdomain blocked).
     */
    fun blocks(host: String): Boolean {
        var h = Host.normalise(host)
        while (h.isNotEmpty()) {
            if (h in this) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
        return false
    }

    /** Entry [k] against bytes [from, to) of [other]: unsigned, byte by byte, the shorter first on a tie. */
    private fun compare(k: Int, other: ByteArray, from: Int, to: Int): Int {
        val a = starts[k]
        val la = ends[k] - a
        val lb = to - from
        for (i in 0 until minOf(la, lb)) {
            val d = (bytes[a + i].toInt() and 0xff) - (other[from + i].toInt() and 0xff)
            if (d != 0) return d
        }
        return la - lb
    }

    private companion object {
        const val NL = '\n'.code.toByte()
        const val CR = '\r'.code.toByte()
        const val HASH = '#'.code.toByte()
    }
}

/**
 * The parent's categories of the built-in list (SPEC §6.5), each a list of its
 * own beside ads and trackers, which are always on. [key] is the list's file
 * name and the word of the `category` line.
 */
enum class Category(val key: String) {
    GAMBLING("gambling"),
    PORN("porn"),
    FAKENEWS("fakenews"),
    SOCIAL("social"),
    ;

    companion object {
        fun byKey(key: String): Category? = entries.firstOrNull { it.key == key.lowercase() }
    }
}

/** The built-in lists as the filter holds them: ads and trackers, and one list per category. */
class Builtin(val ads: BuiltinList, val categories: Map<Category, BuiltinList>) {
    /** True when [host] is on the ads list or on the list of a category in [on]. */
    fun blocks(host: String, on: Set<Category>): Boolean =
        ads.blocks(host) || on.any { categories[it]?.blocks(host) == true }

    companion object {
        /** No built-in list at all: what the filter holds until the lists are read. */
        val NONE = Builtin(BuiltinList(ByteArray(0)), emptyMap())
    }
}
