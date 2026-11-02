package shinkan.core.net

import java.net.URLEncoder

/**
 * What the browser door's address bar makes of a typed text (SPEC §7.6): a web
 * address is opened as it is, a bare host over HTTPS, anything else is a search
 * on DuckDuckGo with safe search strict (`kp=1`). Only `http` and `https` are
 * ever opened: no `file:`, `data:`, `javascript:` or `intent:`.
 */
object Address {
    const val SEARCH = "https://duckduckgo.com/?kp=1&q="

    fun of(typed: String): String? {
        val t = typed.trim()
        if (t.isEmpty()) return null
        if (allowed(t)) return t
        // A scheme of its own (`file:`, `javascript:`, `intent:` …) is never opened: it is searched as text.
        val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):(//|[a-zA-Z])").find(t)?.groupValues?.get(1)?.lowercase()
        if (scheme != null && scheme in OTHER_SCHEMES || t.contains("://")) return search(t)
        val host = t.substringBefore('/').substringBefore('?').substringBefore(':')
        val port = t.substringBefore('/').substringAfter(':', "")
        val isHost = ' ' !in t && '.' in host && !host.startsWith('.') && !host.endsWith('.') &&
            host.all { it.isLetterOrDigit() || it == '.' || it == '-' } && port.all { it.isDigit() }
        return if (isHost) "https://$t" else search(t)
    }

    private fun search(t: String) = SEARCH + URLEncoder.encode(t, "UTF-8")

    private val OTHER_SCHEMES = setOf("file", "javascript", "data", "intent", "content", "about", "chrome", "mailto", "tel", "sms", "market", "blob", "view-source")

    /** True when the browser may open [url]: http or https alone. */
    fun allowed(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("https://") || u.startsWith("http://")
    }
}
