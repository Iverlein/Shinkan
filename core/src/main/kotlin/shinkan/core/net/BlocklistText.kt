package shinkan.core.net

/**
 * The block list as text, one rule per line (SPEC §6.4), the stored form:
 *
 *     site tiktok.com
 *     creator youtube @somehandle Some Name
 */
object BlocklistText {
    class Malformed(line: Int, text: String, why: String) : IllegalArgumentException("line $line: $why: $text")

    fun parse(text: String): Blocklist {
        var list = Blocklist()
        text.lines().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val parts = line.split(Regex("\\s+"), limit = 4)
            when (parts[0]) {
                "site" -> {
                    if (parts.size != 2) throw Malformed(i + 1, line, "site takes one domain")
                    list += parts[1]
                }
                "creator" -> {
                    if (parts.size < 3) throw Malformed(i + 1, line, "creator takes platform, reference and name")
                    val platform = Platform.entries.firstOrNull { it.name.equals(parts[1], ignoreCase = true) }
                        ?: throw Malformed(i + 1, line, "unknown platform")
                    list += Creator(platform, parts[2], parts.getOrNull(3) ?: parts[2])
                }
                else -> throw Malformed(i + 1, line, "unknown statement")
            }
        }
        return list
    }

    fun render(list: Blocklist): String = buildString {
        list.sites.sorted().forEach { appendLine("site $it") }
        list.creators.sortedWith(compareBy({ it.platform }, { it.ref })).forEach {
            appendLine("creator ${it.platform.name.lowercase()} ${it.ref} ${it.name}")
        }
    }
}
