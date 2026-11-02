package shinkan.app.player

import android.content.Context
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import shinkan.app.filter.BlocklistStore
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
}
