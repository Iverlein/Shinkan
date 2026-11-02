package shinkan.core.net

/**
 * The network rules of EasyList and EasyPrivacy (Adblock Plus syntax), as the
 * browser door applies them to every request (SPEC §7.6, ROADMAP block 5).
 *
 * Kept: `||host^` and other host anchors, `|` anchors at either end, plain
 * substrings with `*` and `^`, exceptions `@@`, and the options
 * `third-party`/`3p`, `~third-party`/`first-party`/`1p`, `domain=` (with `~`
 * and `name.*`), the types `script`, `image`, `stylesheet`, `xmlhttprequest`,
 * `subdocument`, `font`, `media`, `other` (and their `~`), `match-case` and
 * `important` (read and ignored). Dropped at parse and counted: comments,
 * cosmetic rules (`##`, `#@#`, `#?#`, `#$#`, `#%#`), regex rules, and any rule
 * with another option (`popup`, `document`, `csp`, `redirect`, `removeparam`,
 * `generichide`, …): an option not understood could narrow the rule, so the
 * rule is not applied at all rather than applied too widely.
 *
 * A request is blocked when a block rule matches it and no exception does.
 * Rules are indexed so a request is checked against few of them: a pure
 * `||host^` rule by its host, found by walking up the request's domains; any
 * other rule by its rarest whole token, found among the tokens of the URL;
 * the rest, with no token, are checked every time.
 */
class FilterList private constructor(
    private val blocks: Index,
    private val exceptions: Index,
    /** Lines read as network rules and kept. */
    val kept: Int,
    /** Lines dropped: cosmetic, regex, unknown options. Comments and blank lines are not counted. */
    val dropped: Int,
) {
    /** The kind of a request, as far as the browser can tell it (see [typeOf]). */
    enum class Type { SCRIPT, IMAGE, STYLESHEET, XMLHTTPREQUEST, SUBDOCUMENT, FONT, MEDIA, OTHER, UNKNOWN }

    /**
     * One request: its [url], the host of the page that makes it ([page], empty
     * when unknown) and its [type].
     */
    class Request(url: String, page: String, val type: Type) {
        val url: String = url.lowercase()
        val host: String = hostOf(this.url)
        val page: String = Host.normalise(page)

        /** Third party: the request's site differs from the page's (no page is a third party). */
        val thirdParty: Boolean = this.page.isEmpty() || Host.base(host) != Host.base(this.page)

        /** Where the host starts in [url], and where it ends. */
        internal val hostStart: Int = url.indexOf("://").let { if (it < 0) 0 else it + 3 }
        internal val hostEnd: Int = hostStart + host.length
        internal val tokens: Set<String> by lazy { tokensOf(this.url) }
    }

    /** True when a block rule matches [request] and no exception does. */
    fun blocks(request: Request): Boolean =
        blocks.matches(request) && !exceptions.matches(request)

    companion object {
        /** Reads the lines of one or more lists. */
        fun parse(lines: Sequence<String>): FilterList {
            val blocks = Index()
            val exceptions = Index()
            var kept = 0
            var dropped = 0
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) continue
                val rule = Rule.parse(line)
                if (rule == null) {
                    dropped++
                    continue
                }
                kept++
                (if (rule.exception) exceptions else blocks).add(rule)
            }
            return FilterList(blocks, exceptions, kept, dropped)
        }

        fun parse(text: String): FilterList = parse(text.lineSequence())

        /**
         * The type of a request from what a WebView tells: whether it is the main
         * frame (never typed: the page itself is not filtered here), its `Accept`
         * header and the URL's file extension. What cannot be told is
         * [Type.UNKNOWN], which a typed rule matches when it names script,
         * xmlhttprequest or other.
         */
        fun typeOf(url: String, accept: String?, mainFrame: Boolean): Type? {
            if (mainFrame) return null
            val a = accept.orEmpty().lowercase()
            val path = url.lowercase().substringBefore('#').substringBefore('?')
            val ext = path.substringAfterLast('/').substringAfterLast('.', "")
            return when {
                a.startsWith("text/html") -> Type.SUBDOCUMENT
                a.startsWith("text/css") || ext == "css" -> Type.STYLESHEET
                a.startsWith("image/") || ext in IMAGES -> Type.IMAGE
                ext in SCRIPTS -> Type.SCRIPT
                ext in FONTS || a.startsWith("font/") -> Type.FONT
                ext in MEDIA || a.startsWith("video/") || a.startsWith("audio/") -> Type.MEDIA
                else -> Type.UNKNOWN
            }
        }

        private val IMAGES = setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp")
        private val SCRIPTS = setOf("js", "mjs")
        private val FONTS = setOf("woff", "woff2", "ttf", "otf", "eot")
        private val MEDIA = setOf("mp4", "webm", "m3u8", "mpd", "mp3", "ogg", "m4a", "aac", "wav")

        internal fun hostOf(url: String): String {
            val start = url.indexOf("://").let { if (it < 0) 0 else it + 3 }
            var end = start
            while (end < url.length && url[end] !in "/?#") end++
            val authority = url.substring(start, end).substringAfterLast('@')
            return authority.substringBefore(':').removeSuffix(".")
        }

        internal fun tokensOf(url: String): Set<String> {
            val out = HashSet<String>()
            var i = 0
            while (i < url.length) {
                if (isTokenChar(url[i])) {
                    var j = i
                    while (j < url.length && isTokenChar(url[j])) j++
                    if (j - i >= MIN_TOKEN) out += url.substring(i, j)
                    i = j
                } else {
                    i++
                }
            }
            return out
        }

        internal fun isTokenChar(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '%'

        internal const val MIN_TOKEN = 3
    }
}

/** One network rule, its pattern ready to match and its options. */
internal class Rule(
    val exception: Boolean,
    /** The host of a pure `||host^` rule, else null. */
    val hostOnly: String?,
    private val pattern: Pattern,
    private val types: Set<FilterList.Type>?,
    private val notTypes: Set<FilterList.Type>,
    private val thirdParty: Boolean?,
    private val domains: List<String>,
    private val notDomains: List<String>,
) {
    val token: String? = pattern.token()

    fun matches(r: FilterList.Request): Boolean {
        if (thirdParty != null && thirdParty != r.thirdParty) return false
        val type = r.type
        if (types != null) {
            val ok = if (type == FilterList.Type.UNKNOWN) types.any { it in UNKNOWN_MATCHES } else type in types
            if (!ok) return false
        }
        if (type in notTypes) return false
        if (domains.isNotEmpty() && domains.none { under(r.page, it) }) return false
        if (notDomains.any { under(r.page, it) }) return false
        return hostOnly?.let { Host.under(r.host, it) } ?: pattern.matches(r)
    }

    companion object {
        private val UNKNOWN_MATCHES = setOf(FilterList.Type.SCRIPT, FilterList.Type.XMLHTTPREQUEST, FilterList.Type.OTHER)
        private val TYPES = mapOf(
            "script" to FilterList.Type.SCRIPT,
            "image" to FilterList.Type.IMAGE,
            "stylesheet" to FilterList.Type.STYLESHEET,
            "css" to FilterList.Type.STYLESHEET,
            "xmlhttprequest" to FilterList.Type.XMLHTTPREQUEST,
            "xhr" to FilterList.Type.XMLHTTPREQUEST,
            "subdocument" to FilterList.Type.SUBDOCUMENT,
            "frame" to FilterList.Type.SUBDOCUMENT,
            "font" to FilterList.Type.FONT,
            "media" to FilterList.Type.MEDIA,
            "other" to FilterList.Type.OTHER,
        )
        private val COSMETIC = Regex("#[@?$%]?#|#@[$?%]#")
        private val HOST = Regex("[a-z0-9_.-]+")

        /** The rule of [line], or null when it is dropped. */
        fun parse(line: String): Rule? {
            if (COSMETIC.containsMatchIn(line)) return null
            var text = line
            val exception = text.startsWith("@@")
            if (exception) text = text.substring(2)
            var options = ""
            val dollar = text.lastIndexOf('$')
            if (dollar >= 0 && !(text.startsWith("/") && text.endsWith("/"))) {
                options = text.substring(dollar + 1)
                text = text.substring(0, dollar)
            }
            if (text.length > 1 && text.startsWith("/") && text.endsWith("/")) return null
            text = text.lowercase()

            var types: MutableSet<FilterList.Type>? = null
            val notTypes = mutableSetOf<FilterList.Type>()
            var thirdParty: Boolean? = null
            val domains = mutableListOf<String>()
            val notDomains = mutableListOf<String>()
            if (options.isNotEmpty()) {
                for (opt in options.lowercase().split(',')) {
                    val negated = opt.startsWith("~")
                    val name = opt.removePrefix("~")
                    when {
                        name == "third-party" || name == "3p" -> thirdParty = !negated
                        name == "first-party" || name == "1p" -> thirdParty = negated
                        name == "match-case" || name == "important" -> Unit
                        name.startsWith("domain=") && !negated -> {
                            for (d in name.removePrefix("domain=").split('|')) {
                                if (d.isEmpty()) continue
                                if (d.startsWith("~")) notDomains += d.substring(1) else domains += d
                            }
                        }
                        name in TYPES -> {
                            val t = TYPES.getValue(name)
                            if (negated) notTypes += t else (types ?: mutableSetOf<FilterList.Type>().also { types = it }) += t
                        }
                        else -> return null
                    }
                }
            }
            if (text.isEmpty() || text == "*" || text == "|" || text == "||") {
                // A rule of options alone would match every request of its kind.
                if (domains.isEmpty()) return null
                text = "*"
            }
            val hostOnly = if (text.startsWith("||") && text.endsWith("^")) {
                text.substring(2, text.length - 1).takeIf { HOST.matches(it) }
            } else null
            return Rule(exception, hostOnly, Pattern.of(text), types, notTypes, thirdParty, domains, notDomains)
        }

        /** True when [host] is [domain] or under it; `name.*` stands for that name under any one or two labels. */
        fun under(host: String, domain: String): Boolean {
            if (host.isEmpty()) return false
            if (!domain.endsWith(".*")) return Host.under(host, domain)
            val name = domain.dropLast(2)
            var i = 0
            while (true) {
                val rest = host.substring(i)
                if (rest.startsWith("$name.") && rest.substring(name.length + 1).count { it == '.' } <= 1) return true
                val dot = host.indexOf('.', i)
                if (dot < 0) return false
                i = dot + 1
            }
        }
    }
}

/**
 * The matching part of a rule, lowercase: segments between `*`, `^` for a
 * separator (anything but a letter, a digit or `_-.%`, or the end), an anchor
 * at the start (`|` the URL's start, `||` a label of the host) and at the end.
 */
internal class Pattern private constructor(
    private val segments: List<String>,
    private val hostAnchor: Boolean,
    private val startAnchor: Boolean,
    private val endAnchor: Boolean,
) {
    /**
     * The longest token of the pattern that a matching URL must contain whole:
     * a run of token characters with a fixed character or an anchor on both
     * sides, never a `*` or an open end.
     */
    fun token(): String? {
        var best: String? = null
        segments.forEachIndexed { si, seg ->
            var i = 0
            while (i < seg.length) {
                if (!FilterList.isTokenChar(seg[i])) {
                    i++
                    continue
                }
                var j = i
                while (j < seg.length && FilterList.isTokenChar(seg[j])) j++
                val closedLeft = i > 0 || (si == 0 && (startAnchor || hostAnchor))
                val closedRight = j < seg.length || (si == segments.lastIndex && endAnchor)
                if (closedLeft && closedRight && j - i >= FilterList.MIN_TOKEN) {
                    val t = seg.substring(i, j)
                    if (best == null || t.length > best!!.length) best = t
                }
                i = j
            }
        }
        return best
    }

    fun matches(r: FilterList.Request): Boolean {
        val url = r.url
        if (segments.isEmpty()) return true
        if (hostAnchor) {
            // The first segment starts the host or a label of it.
            var at = r.hostStart
            while (at <= r.hostEnd) {
                if (rest(url, at, 0)) return true
                val dot = url.indexOf('.', at)
                if (dot < 0 || dot >= r.hostEnd) return false
                at = dot + 1
            }
            return false
        }
        if (startAnchor) return rest(url, 0, 0)
        return rest(url, -1, 0)
    }

    /**
     * Segments from [index] on, the first at [at] exactly (or anywhere from 0 when
     * [at] is -1), each later one at its first place after the one before; the
     * last at the end when the pattern is anchored there.
     */
    private fun rest(url: String, at: Int, index: Int): Boolean {
        var pos: Int
        var i = index
        if (at >= 0) {
            val end = matchAt(url, at, segments[i]) ?: return false
            if (i == segments.lastIndex) return !endAnchor || end == url.length
            pos = end
            i++
        } else {
            pos = 0
        }
        while (i < segments.size) {
            val seg = segments[i]
            if (i == segments.lastIndex && endAnchor) {
                // The last segment ends the URL: try it there, or with a final ^ at the end.
                for (start in listOf(url.length - seg.length, url.length - seg.length + 1)) {
                    if (start >= pos && start <= url.length) {
                        val end = matchAt(url, start, seg)
                        if (end == url.length) return true
                    }
                }
                return false
            }
            val found = find(url, pos, seg) ?: return false
            pos = found
            i++
        }
        return true
    }

    /** The first place at or after [from] where [seg] matches, as the index after it. */
    private fun find(url: String, from: Int, seg: String): Int? {
        if (seg.isEmpty()) return from
        var s = from
        while (s <= url.length) {
            matchAt(url, s, seg)?.let { return it }
            s++
        }
        return null
    }

    /** [seg] matched at [at]: the index after it, or null. A `^` may match the URL's end, taking nothing. */
    private fun matchAt(url: String, at: Int, seg: String): Int? {
        var u = at
        for ((k, c) in seg.withIndex()) {
            if (c == '^') {
                if (u == url.length) {
                    // The end is a separator only for a final ^.
                    return if (k == seg.lastIndex) u else null
                }
                if (!isSeparator(url[u])) return null
                u++
            } else {
                if (u >= url.length || url[u] != c) return null
                u++
            }
        }
        return u
    }

    private fun isSeparator(c: Char) = !(c.isLetterOrDigit() || c in "_-.%")

    companion object {
        fun of(text: String): Pattern {
            var t = text
            val hostAnchor = t.startsWith("||")
            if (hostAnchor) t = t.substring(2)
            val startAnchor = !hostAnchor && t.startsWith("|")
            if (startAnchor) t = t.substring(1)
            val endAnchor = t.endsWith("|")
            if (endAnchor) t = t.dropLast(1)
            var segments = t.split('*')
            // Leading or trailing stars only lift an anchor; empty segments add nothing.
            val lead = segments.first().isEmpty()
            val trail = segments.last().isEmpty()
            segments = segments.filter { it.isNotEmpty() }
            return Pattern(segments, hostAnchor && !lead, startAnchor && !lead, endAnchor && !trail)
        }
    }
}

/** The rules of one kind, indexed by host and by token. */
internal class Index {
    private val byHost = HashMap<String, MutableList<Rule>>()
    private val byToken = HashMap<String, MutableList<Rule>>()
    private val rest = ArrayList<Rule>()

    fun add(rule: Rule) {
        val host = rule.hostOnly
        val token = rule.token
        when {
            host != null -> byHost.getOrPut(host) { ArrayList(1) } += rule
            token != null -> byToken.getOrPut(token) { ArrayList(1) } += rule
            else -> rest += rule
        }
    }

    fun matches(r: FilterList.Request): Boolean {
        var h = r.host
        while (h.isNotEmpty()) {
            byHost[h]?.let { rules -> if (rules.any { it.matches(r) }) return true }
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        if (byToken.isNotEmpty()) {
            for (t in r.tokens) byToken[t]?.let { rules -> if (rules.any { it.matches(r) }) return true }
        }
        return rest.any { it.matches(r) }
    }
}
