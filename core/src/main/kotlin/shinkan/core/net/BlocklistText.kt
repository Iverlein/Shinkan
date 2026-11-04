package shinkan.core.net

import shinkan.core.TextProblem

/**
 * The block list as text, one rule per line (SPEC §6.4), the stored form:
 *
 *     site tiktok.com
 *     creator youtube @somehandle Some Name
 *     segments sponsor selfpromo
 *     mirror https://sb.example.org
 *     category gambling
 *     pass example.org
 *
 * `segments` names the kinds the player skips, none when it stands alone, the
 * defaults of §7.5 when it is missing; `mirror` is asked for them instead of the
 * public API. `category` switches on one list of the built-in block list (§6.5); `pass`
 * lets a name of the built-in list through, with its subdomains.
 */
object BlocklistText {
    class Malformed(val line: Int, val text: String, val problem: TextProblem, val detail: String = "") :
        IllegalArgumentException("line $line: $problem $detail: $text")

    fun parse(text: String): Blocklist {
        var list = Blocklist()
        text.lines().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val parts = line.split(Regex("\\s+"), limit = 4)
            when (parts[0]) {
                "site" -> {
                    if (parts.size != 2) throw Malformed(i + 1, line, TextProblem.SITE)
                    list += parts[1]
                }
                "creator" -> {
                    if (parts.size < 3) throw Malformed(i + 1, line, TextProblem.CREATOR)
                    val platform = Platform.entries.firstOrNull { it.name.equals(parts[1], ignoreCase = true) }
                        ?: throw Malformed(i + 1, line, TextProblem.UNKNOWN_PLATFORM)
                    list += Creator(platform, parts[2], parts.getOrNull(3) ?: parts[2])
                }
                "player" -> {
                    list = list.copy(
                        player = when (parts.getOrNull(1)) {
                            "allow" -> PlayerMode.ALLOW
                            "block" -> PlayerMode.BLOCK
                            else -> throw Malformed(i + 1, line, TextProblem.PLAYER)
                        },
                    )
                }
                "history" -> {
                    val keep = parts.getOrNull(1)?.let { HistoryKeep.byKey(it) }
                    if (parts.size != 2 || keep == null) throw Malformed(i + 1, line, TextProblem.HISTORY)
                    list = list.copy(history = keep)
                }
                "segments" -> {
                    val kinds = line.split(Regex("\\s+")).drop(1).map {
                        SegmentKind.byKey(it) ?: throw Malformed(i + 1, line, TextProblem.UNKNOWN_SEGMENT)
                    }
                    list = list.copy(segments = kinds.toSet())
                }
                "mirror" -> {
                    val mirror = parts.getOrNull(1)?.let { Segments.mirror(it) }
                    if (parts.size != 2 || mirror == null) throw Malformed(i + 1, line, TextProblem.MIRROR)
                    list = list.copy(mirror = mirror)
                }
                "allow" -> {
                    if (parts.size < 3) throw Malformed(i + 1, line, TextProblem.ALLOW)
                    val platform = Platform.entries.firstOrNull { it.name.equals(parts[1], ignoreCase = true) }
                        ?: throw Malformed(i + 1, line, TextProblem.UNKNOWN_PLATFORM)
                    list = list.copy(allowed = list.allowed + Creator(platform, parts[2], parts.getOrNull(3) ?: parts[2]))
                }
                "category" -> {
                    if (parts.size != 2) throw Malformed(i + 1, line, TextProblem.CATEGORY)
                    val c = Category.byKey(parts[1]) ?: throw Malformed(i + 1, line, TextProblem.UNKNOWN_CATEGORY)
                    list = list.copy(categories = list.categories + c)
                }
                "pass" -> {
                    if (parts.size != 2) throw Malformed(i + 1, line, TextProblem.PASS)
                    list = list.copy(passes = list.passes + Host.normalise(parts[1]))
                }
                else -> throw Malformed(i + 1, line, TextProblem.UNKNOWN_STATEMENT)
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
        if (list.segments != SegmentKind.DEFAULT) {
            appendLine(SegmentKind.entries.filter { it in list.segments }.joinToString(" ", "segments ") { it.key }.trimEnd())
        }
        list.mirror?.let { appendLine("mirror $it") }
        list.allowed.sortedWith(compareBy({ it.platform }, { it.ref })).forEach {
            appendLine("allow ${it.platform.name.lowercase()} ${it.ref} ${it.name}")
        }
        Category.entries.filter { it in list.categories }.forEach { appendLine("category ${it.key}") }
        list.passes.sorted().forEach { appendLine("pass $it") }
    }
}
