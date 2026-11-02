package shinkan.core.net

/**
 * What the filter blocks (SPEC §6): sites by registered domain, every
 * subdomain included; creators on the platforms the built-in player serves;
 * the built-in categories the parent switched on (§6.5); and passes, names
 * the built-in list may not block. Pure data with the one question the filter
 * asks: [blocksHost].
 */
data class Blocklist(
    val sites: Set<String> = emptySet(),
    val creators: Set<Creator> = emptySet(),
    val categories: Set<Category> = emptySet(),
    val passes: Set<String> = emptySet(),
    /** The player's mode (SPEC §7.1): every creator but the blocked [creators], or the [allowed] alone. */
    val player: PlayerMode = PlayerMode.BLOCK,
    val allowed: Set<Creator> = emptySet(),
) {
    /**
     * True when [host] is a blocked site or under one; else when the [builtin]
     * list blocks it (ads and trackers, and the categories switched on) and no
     * pass covers it. A pass lets a built-in name through, never a parent's site.
     */
    fun blocksHost(host: String, builtin: Builtin = Builtin.NONE): Boolean {
        val h = Host.normalise(host)
        if (sites.any { Host.under(h, it) }) return true
        if (!builtin.blocks(h, categories)) return false
        return passes.none { Host.under(h, it) }
    }

    /**
     * True when the uploader at [url] is a blocked creator (SPEC §6.3, §7.1): its
     * reference ([Link.creatorRef]) is one on the list, or [aliases] (a channel id
     * to the handle or name a parent blocked it by, found by the player) leads to
     * one. Handles and `c/`, `user/` names are compared without case, channel ids
     * exactly.
     */
    fun hidesUploader(url: String?, aliases: Map<String, String> = emptyMap()): Boolean {
        val ref = Link.creatorRef(url)
        if (player == PlayerMode.ALLOW) return ref == null || !listed(ref, allowed, aliases)
        return ref != null && listed(ref, creators, aliases)
    }

    private fun listed(ref: String, list: Set<Creator>, aliases: Map<String, String>): Boolean {
        val refs = list.filter { it.platform == Platform.YOUTUBE }.map { comparable(it.ref) }.toSet()
        return comparable(ref) in refs || aliases[ref]?.let { comparable(it) in refs } == true
    }

    /** The creators whose reference is not a channel id: the player resolves these to one. */
    val unresolvedCreators: List<String>
        get() = (if (player == PlayerMode.ALLOW) allowed else creators)
            .filter { it.platform == Platform.YOUTUBE && !isChannelId(it.ref) }.map { it.ref }

    operator fun plus(site: String) = copy(sites = sites + Host.normalise(site))
    operator fun plus(creator: Creator) = copy(creators = creators + creator)
}

/** The player's two modes (SPEC §7.1): a block list of creators, or an allow list. */
enum class PlayerMode { BLOCK, ALLOW }

/** One creator on one platform, by the stable reference the platform gives (a channel id, a handle, an account). */
data class Creator(val platform: Platform, val ref: String, val name: String = ref)

/** A YouTube channel id: `UC` and 22 more characters. */
internal fun isChannelId(ref: String): Boolean = ref.length == 24 && ref.startsWith("UC")

/** A reference as compared: a channel id as it is, a handle or a name in lowercase. */
private fun comparable(ref: String): String = if (isChannelId(ref)) ref else ref.lowercase()

enum class Platform { YOUTUBE, PEERTUBE, SOUNDCLOUD, BANDCAMP }

object Host {
    /** Lowercase, no trailing dot, no port; an invalid host comes back as is. */
    fun normalise(host: String): String = host.trim().lowercase().removeSuffix(".").substringBefore(":")

    /** True when [host] equals [domain] or ends in ".[domain]". */
    fun under(host: String, domain: String): Boolean = host == domain || host.endsWith(".$domain")

    /**
     * The site a host belongs to, near enough for telling first party from third
     * (SPEC §7.6): its last two labels, or three under a two-letter country's
     * common second level (`example.co.uk`). Without the public suffix list, a
     * shared hosting domain counts as one site.
     */
    fun base(host: String): String {
        val labels = normalise(host).split('.')
        if (labels.size <= 2) return labels.joinToString(".")
        val three = labels.size >= 3 && labels.last().length == 2 && labels[labels.size - 2] in secondLevels
        return labels.takeLast(if (three) 3 else 2).joinToString(".")
    }

    private val secondLevels = setOf("co", "com", "net", "org", "gov", "edu", "ac", "ne", "or", "go", "gob", "mil", "nic", "ltd", "plc", "sch")

    private val mobilePrefixes = listOf("www.", "m.", "mobile.", "amp.", "touch.")

    /** The domain a shared link stands for: the host without the mobile and www prefixes (SPEC §6.2). */
    fun site(host: String): String {
        var h = normalise(host)
        for (p in mobilePrefixes) if (h.startsWith(p) && h.count { it == '.' } >= 2) { h = h.removePrefix(p); break }
        return h
    }
}

/**
 * The hosts a platform is reached through besides its name (SPEC §6.2):
 * blocking the site blocks the family. Known families only; a domain not
 * listed is blocked by itself.
 */
object Families {
    private val table: Map<String, Set<String>> = mapOf(
        "youtube.com" to setOf("youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com", "ytimg.com", "yt.be"),
        "tiktok.com" to setOf("tiktok.com", "tiktokv.com", "tiktokcdn.com", "tiktokcdn-us.com", "musical.ly", "byteoversea.com"),
        "instagram.com" to setOf("instagram.com", "cdninstagram.com", "ig.me"),
        "facebook.com" to setOf("facebook.com", "fb.com", "fbcdn.net", "fb.me", "messenger.com"),
        "twitch.tv" to setOf("twitch.tv", "ttvnw.net", "jtvnw.net"),
        "x.com" to setOf("x.com", "twitter.com", "twimg.com", "t.co"),
        "snapchat.com" to setOf("snapchat.com", "sc-cdn.net", "snap.com"),
        "discord.com" to setOf("discord.com", "discordapp.com", "discord.gg", "discord.media"),
        "reddit.com" to setOf("reddit.com", "redd.it", "redditmedia.com", "redditstatic.com"),
    )
    private val byMember: Map<String, String> = table.flatMap { (k, v) -> v.map { it to k } }.toMap()

    /** The family [site] belongs to, or the site alone. */
    fun of(site: String): Set<String> {
        val s = Host.site(site)
        val key = byMember.entries.firstOrNull { Host.under(s, it.key) }?.value ?: return setOf(s)
        return table.getValue(key)
    }
}
