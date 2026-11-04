package shinkan.app.player

import android.content.Context
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import shinkan.app.filter.BlocklistStore
import shinkan.core.net.Creator
import shinkan.core.net.Link
import shinkan.core.net.Platform
import java.util.concurrent.ConcurrentHashMap

/**
 * The creator block list as the player applies it (SPEC §6.3, §7.1). The
 * extractor names an uploader by channel id; a parent may have blocked one by
 * its handle or name, so each such reference is resolved to its channel id once
 * per process through the extractor (the creator lookup of SPEC §7.3) and kept
 * in memory. Called off the main thread.
 */
object Creators {
    private val aliases = ConcurrentHashMap<String, String>()
    private val tried = ConcurrentHashMap.newKeySet<String>()

    /** Whether the uploader at an address is blocked, by the list as stored now. */
    fun check(context: Context): (String?) -> Boolean {
        val list = BlocklistStore(context).blocklist
        for (ref in list.unresolvedCreators) {
            if (!tried.add(ref)) continue
            runCatching {
                Extractor.init()
                aliases[ChannelInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/$ref").id] = ref
            }.onFailure { tried.remove(ref) }
        }
        val known = HashMap(aliases)
        return { url -> list.hidesUploader(url, known) }
    }

    /**
     * The creator of the video at [url], by the extractor (the lookup of SPEC §7.3),
     * or null when the page names no channel. Fails with the extractor's exception
     * when the video cannot be read. Called off the main thread.
     */
    fun ofVideo(url: String): Creator? {
        Extractor.init()
        val video = ServiceList.YouTube.getStreamExtractor(url).apply { fetchPage() }
        val ref = Link.creatorRef(video.uploaderUrl) ?: return null
        return Creator(Platform.YOUTUBE, ref, video.uploaderName.orEmpty().ifBlank { ref })
    }

    private val avatars = ConcurrentHashMap<String, String>()

    /** Keeps a channel's avatar address, from a ChannelInfo the player loaded anyway. */
    fun keepAvatar(channelUrl: String, images: List<org.schabi.newpipe.extractor.Image>) {
        Media.thumbnail(images, 88)?.let { avatars[channelUrl] = it }
    }

    /**
     * The avatar address of the channel at [channelUrl], from memory or through
     * the extractor once (the creator lookup of SPEC §7.3); null when it cannot be had.
     * Called off the main thread.
     */
    fun avatar(channelUrl: String): String? = avatars[channelUrl] ?: runCatching {
        Extractor.init()
        val info = ChannelInfo.getInfo(ServiceList.YouTube, channelUrl)
        keepAvatar(channelUrl, info.avatars)
        avatars[channelUrl]
    }.getOrNull()

    /** The allowed creators of the allow mode, by name and channel address. */
    fun allowed(context: Context): List<Pair<String, String>> =
        BlocklistStore(context).blocklist.allowed.map { it.name to channelUrl(it.ref) }

    /** Whether the player is in its allow mode. */
    fun allowMode(context: Context): Boolean = BlocklistStore(context).blocklist.player == shinkan.core.net.PlayerMode.ALLOW

    /** The channel page of a reference: `channel/UC…` for an id, the handle or name as it is. */
    fun channelUrl(ref: String): String =
        "https://www.youtube.com/" + if (ref.length == 24 && ref.startsWith("UC")) "channel/$ref" else ref
}
