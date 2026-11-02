package shinkan.core.net

/**
 * The block list as text, one rule per line (SPEC §6.4), the stored form:
 *
 *     site tiktok.com
 *     creator youtube @somehandle Some Name
 *     category gambling
 *     pass example.org
 *
 * `category` switches on one list of the built-in block list (§6.5); `pass`
 * lets a name of the built-in list through, with its subdomains.
 */
object BlocklistText {
    class Malformed(val line: Int, val text: String, val why: String) : IllegalArgumentException("line $line: $why: $text")

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
                "player" -> {
                    list = list.copy(
                        player = when (parts.getOrNull(1)) {
                            "allow" -> PlayerMode.ALLOW
                            "block" -> PlayerMode.BLOCK
                            else -> throw Malformed(i + 1, line, "player is allow or block")
                        },
                    )
                }
                "history" -> {
                    val keep = parts.getOrNull(1)?.let { HistoryKeep.byKey(it) }
                    if (parts.size != 2 || keep == null) throw Malformed(i + 1, line, "history is off, day, week, month or forever")
                    list = list.copy(history = keep)
                }
                "allow" -> {
                    if (parts.size < 3) throw Malformed(i + 1, line, "allow takes platform, reference and name")
                    val platform = Platform.entries.firstOrNull { it.name.equals(parts[1], ignoreCase = true) }
                        ?: throw Malformed(i + 1, line, "unknown platform")
                    list = list.copy(allowed = list.allowed + Creator(platform, parts[2], parts.getOrNull(3) ?: parts[2]))
                }
                "category" -> {
                    if (parts.size != 2) throw Malformed(i + 1, line, "category takes one name")
                    val c = Category.byKey(parts[1]) ?: throw Malformed(i + 1, line, "unknown category")
                    list = list.copy(categories = list.categories + c)
                }
                "pass" -> {
                    if (parts.size != 2) throw Malformed(i + 1, line, "pass takes one domain")
                    list = list.copy(passes = list.passes + Host.normalise(parts[1]))
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
        if (list.player == PlayerMode.ALLOW) appendLine("player allow")
        if (list.history != HistoryKeep.FOREVER) appendLine("history ${list.history.key}")
        list.allowed.sortedWith(compareBy({ it.platform }, { it.ref })).forEach {
            appendLine("allow ${it.platform.name.lowercase()} ${it.ref} ${it.name}")
        }
        Category.entries.filter { it in list.categories }.forEach { appendLine("category ${it.key}") }
        list.passes.sorted().forEach { appendLine("pass $it") }
    }
}
