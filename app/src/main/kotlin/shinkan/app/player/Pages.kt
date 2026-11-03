package shinkan.app.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import shinkan.core.net.Feed

/**
 * A list of the player's fetched a page at a time (SPEC §7.1.1): a search, a
 * channel's videos, the allow mode's newest videos. The platform answers about
 * twenty videos a request; the next page is fetched as the child nears the end.
 */
interface Pages {
    /** The next page's videos, the blocked creators' left out; may be empty while not [ended]. Off the main thread. */
    suspend fun next(): List<StreamInfoItem>

    /** Whether nothing more is to come. */
    val ended: Boolean

    /** One extractor list from a [next] page on, through [more]; [keep] says which videos are shown. */
    class Of(
        private var page: Page?,
        private val keep: (StreamInfoItem) -> Boolean,
        private val more: (Page) -> InfoItemsPage<out InfoItem>,
    ) : Pages {
        override val ended get() = !Page.isValid(page)

        /** Up to [ROUNDS] pages until one has a video kept: in the allow mode most of a search is not. */
        override suspend fun next(): List<StreamInfoItem> {
            repeat(ROUNDS) {
                val at = page?.takeIf { Page.isValid(it) } ?: return emptyList()
                val got = more(at)
                page = got.nextPage
                val kept = got.items.filterIsInstance<StreamInfoItem>().filter(keep)
                if (kept.isNotEmpty()) return kept
            }
            return emptyList()
        }

        companion object {
            /** The rest of the channel tab at [tab] after its [first] page. */
            fun channel(tab: ListLinkHandler, first: ChannelTabInfo, keep: (StreamInfoItem) -> Boolean) =
                Of(first.nextPage, keep) { ChannelTabInfo.getMoreItems(ServiceList.YouTube, tab, it) }
        }
    }

    /**
     * The newest videos of several creators' channel tabs, merged newest first
     * through [Feed]; each tab's pages are fetched as the merge needs them, the
     * needed ones at once. A tab that fails is left out.
     */
    class Merged(private val tabs: List<ListLinkHandler>) : Pages {
        private val feed = Feed<StreamInfoItem>(tabs.size) { it.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() }
        private val cursors = arrayOfNulls<Page>(tabs.size)

        override val ended get() = feed.done

        override suspend fun next(): List<StreamInfoItem> {
            val out = ArrayList<StreamInfoItem>()
            // A few rounds of fetching at most, so a run of empty pages cannot hold the list.
            repeat(ROUNDS) {
                out += feed.take(PAGE - out.size)
                val wanted = feed.wanted()
                if (out.size >= PAGE || wanted.isEmpty()) return out
                coroutineScope {
                    wanted.map { i -> async(Dispatchers.IO) { i to fetch(i) } }.awaitAll()
                }.forEach { (i, got) -> feed.add(i, got.first, got.second) }
            }
            return out
        }

        /** A tab's next page and whether one follows; its first page when none was fetched. */
        private fun fetch(i: Int): Pair<List<StreamInfoItem>, Boolean> = runCatching {
            val (items, next) = cursors[i]?.let { at ->
                ChannelTabInfo.getMoreItems(ServiceList.YouTube, tabs[i], at).let { it.items to it.nextPage }
            } ?: ChannelTabInfo.getInfo(ServiceList.YouTube, tabs[i]).let { it.relatedItems to it.nextPage }
            cursors[i] = next
            items.filterIsInstance<StreamInfoItem>() to Page.isValid(next)
        }.getOrDefault(emptyList<StreamInfoItem>() to false)

        private companion object {
            const val PAGE = 20
        }
    }

    private companion object {
        /** The most requests one [next] makes in a row, so a list that keeps nothing cannot run on by itself. */
        const val ROUNDS = 3
    }
}
