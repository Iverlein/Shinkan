package shinkan.core.net

import java.net.URI

/**
 * What a shared text means to the block list (SPEC §6.2): a site, a creator
 * the player knows, or a video whose creator must be looked up first.
 */
sealed interface Link {
    /** A site: the domain and its family. */
    data class Site(val domain: String, val family: Set<String>) : Link

    /** A creator the platform names in the URL itself. */
    data class CreatorLink(val creator: Creator) : Link

    /** A video or post; its creator is found by the player's extractor (SPEC §7.3). */
    data class Item(val platform: Platform, val id: String, val url: String) : Link

    companion object {
        private val urlInText = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
        private val youtubeHosts = setOf("youtube.com", "youtube-nocookie.com")

        /** The first URL in [text] read as a link; a bare domain counts too; null when there is none. */
        fun parse(text: String): Link? {
            val raw = urlInText.find(text)?.value
                ?: text.trim().takeIf { Regex("""^[a-z0-9.-]+\.[a-z]{2,}(/\S*)?$""", RegexOption.IGNORE_CASE).matches(it) }?.let { "https://$it" }
                ?: return null
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            val host = Host.site(uri.host ?: return null)
            val path = uri.path.orEmpty().trimEnd('/')
            youtube(host, path, uri)?.let { return it }
            return Site(host, Families.of(host))
        }

        private fun youtube(host: String, path: String, uri: URI): Link? {
            if (host == "youtu.be") {
                return path.removePrefix("/").substringBefore('/').takeIf { it.isNotEmpty() }?.let { Item(Platform.YOUTUBE, it, uri.toString()) }
            }
            if (youtubeHosts.none { Host.under(host, it) }) return null
            val parts = path.split('/').filter { it.isNotEmpty() }
            val first = parts.firstOrNull() ?: return null
            return when {
                first.startsWith("@") -> CreatorLink(Creator(Platform.YOUTUBE, first, first))
                first == "channel" && parts.size >= 2 -> CreatorLink(Creator(Platform.YOUTUBE, parts[1], parts[1]))
                (first == "c" || first == "user") && parts.size >= 2 -> CreatorLink(Creator(Platform.YOUTUBE, "$first/${parts[1]}", parts[1]))
                first == "watch" -> query(uri, "v")?.let { Item(Platform.YOUTUBE, it, uri.toString()) }
                (first == "shorts" || first == "live" || first == "embed") && parts.size >= 2 -> Item(Platform.YOUTUBE, parts[1], uri.toString())
                else -> null
            }
        }

        /**
         * The creator reference a YouTube channel address names, in the form a
         * shared link gives it (SPEC §6.2): `UC…` for `/channel/UC…`, `@handle`,
         * `c/name`, `user/name`; null for any other address.
         */
        fun creatorRef(url: String?): String? {
            val uri = runCatching { URI(url?.trim() ?: return null) }.getOrNull() ?: return null
            val host = Host.site(uri.host ?: return null)
            return (youtube(host, uri.path.orEmpty().trimEnd('/'), uri) as? CreatorLink)?.creator?.ref
        }

        private fun query(uri: URI, key: String): String? =
            uri.rawQuery?.split('&')?.map { it.substringBefore('=') to it.substringAfter('=', "") }
                ?.firstOrNull { it.first == key }?.second?.takeIf { it.isNotEmpty() }
    }
}
