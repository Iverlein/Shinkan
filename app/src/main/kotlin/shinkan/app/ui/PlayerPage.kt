package shinkan.app.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.PlayerView
import io.github.iverlein.shinkan.BuildConfig
import io.github.iverlein.shinkan.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import shinkan.app.plan.PlanClock
import shinkan.app.player.Creators
import shinkan.app.player.Extractor
import shinkan.app.player.Images
import shinkan.app.player.Media
import shinkan.app.player.Pages
import shinkan.app.player.PlayerHistory
import shinkan.app.player.SponsorBlock
import shinkan.core.net.History
import shinkan.core.net.Quality
import shinkan.core.net.Segment
import shinkan.core.net.Segments
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay

/** The player's two screens. */
private enum class Tab(val label: Int, val icon: Int) {
    HOME(R.string.player_home, R.drawable.nav_home),
    CREATORS(R.string.player_creators, R.drawable.nav_creators),
    HISTORY(R.string.player_history, R.drawable.nav_history),
}

/**
 * A list on screen and the rest of it, fetched a page at a time as the child
 * nears its end ([Pages]); where it was scrolled to, kept while a video plays.
 */
private class Listing(val rest: Pages, first: List<StreamInfoItem>) {
    val items = mutableStateListOf<StreamInfoItem>().apply { addAll(first) }
    var fetching by mutableStateOf(false)
    var failed by mutableStateOf(false)
    /** A page came with nothing to show: the next waits for the child's tap. */
    var stalled by mutableStateOf(false)
    var ended by mutableStateOf(rest.ended)
    var index = 0
    var offset = 0
}

/** A channel as the player shows it: its name and its videos. */
private class ChannelView(val name: String, val videos: Listing)

/** How many rows before a list's end its next page is fetched. */
private const val AHEAD = 5

/**
 * The player door (SPEC §7): YouTube through NewPipe Extractor, with no account
 * and no official app. A search, its results, a video playing in Media3 with
 * its uploader and related videos, the uploader's channel. Every list leaves
 * out the videos of blocked creators, and a blocked creator's channel or video
 * is refused ([Creators], block 6b). In
 * landscape a playing video fills the screen. A rest and leaving the door
 * pause playback; the volume keys keep the door's ceiling (SPEC §4.6).
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun PlayerPage(onClose: () -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<Listing?>(null) }
    var stream by remember { mutableStateOf<StreamInfo?>(null) }
    var related by remember { mutableStateOf<List<StreamInfoItem>>(emptyList()) }
    var channel by remember { mutableStateOf<ChannelView?>(null) }
    var loading by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(Tab.HOME) }
    var history by remember { mutableStateOf(History()) }
    var allowMode by remember { mutableStateOf(false) }
    var feed by remember { mutableStateOf<Listing?>(null) }
    var allowed by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.player_error)
    val nothing = stringResource(R.string.player_nothing)
    val noStream = stringResource(R.string.player_no_stream)
    val blocked = stringResource(R.string.player_blocked)

    val softwareDecoders = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    // The quality asked for: the screen's shorter side, never below 1080 (SPEC §7.2); the child may change it.
    var quality by remember {
        val m = context.resources.displayMetrics
        mutableStateOf(Quality.default(minOf(m.widthPixels, m.heightPixels)))
    }
    var streams by remember { mutableStateOf<Media.Streams?>(null) }
    var playingHeight by remember { mutableStateOf(0) }
    val player = remember {
        val http = DefaultHttpDataSource.Factory().setUserAgent(Extractor.OkDownloader.USER_AGENT)
        // A decoder that fails to start gives way to the next one (Media3's fallback); one that
        // fails while decoding (the emulator's own H.264 decoder does, 2026-11-02) makes the
        // player prepare again preferring Android's software decoders, from where it was.
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        renderers.setMediaCodecSelector { mime, secure, tunneling ->
            val all = androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
            if (softwareDecoders.get()) all.sortedBy { if (it.softwareOnly) 0 else 1 } else all
        }
        ExoPlayer.Builder(context, renderers).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
    }
    // Once the device's decoder has failed, the software decoders first (see the selector above).
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (error.errorCode in androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED &&
                    !softwareDecoders.getAndSet(true)
                ) {
                    player.prepare()
                    player.play()
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val view = remember { PlayerView(context).apply { this.player = player } }
    DisposableEffect(Unit) { onDispose { player.release() } }

    // The video's segments (SPEC §7.5): asked for once as it opens, the video playing meanwhile.
    var segments by remember { mutableStateOf<List<Segment>>(emptyList()) }
    var segmentsFailed by remember { mutableStateOf(false) }
    val videoId = stream?.id
    LaunchedEffect(videoId) {
        segments = emptyList()
        segmentsFailed = false
        if (videoId == null) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) { runCatching { SponsorBlock.fetch(context, videoId) } }
        segments = found.getOrDefault(emptyList())
        segmentsFailed = found.isFailure
    }
    LaunchedEffect(segments) {
        if (segments.isEmpty()) return@LaunchedEffect
        while (true) {
            if (player.isPlaying) {
                Segments.skipTo(player.currentPosition, segments)?.let {
                    if (BuildConfig.DEBUG) android.util.Log.d("SponsorBlock", "skipped ${player.currentPosition} to $it")
                    player.seekTo(it)
                }
            }
            delay(200)
        }
    }

    /** Where the playing video stands, written into the history (SPEC §7.1). */
    fun keepPlace() {
        val info = stream ?: return
        val entry = History.Entry(
            info.url, info.name, info.uploaderName.orEmpty(), info.uploaderUrl.orEmpty(),
            Media.thumbnail(info.thumbnails).orEmpty(), info.duration, player.currentPosition, System.currentTimeMillis(),
        )
        scope.launch {
            history = withContext(Dispatchers.IO) {
                PlayerHistory.played(context, entry)
                PlayerHistory.load(context)
            }
        }
    }

    LifecycleResumeEffect(Unit) {
        onPauseOrDispose {
            keepPlace()
            player.pause()
        }
    }
    LaunchedEffect(Unit) {
        PlanClock.pauses.collect {
            keepPlace()
            player.pause()
        }
    }
    // The history, the mode and, in the allow mode, the newest videos of the allowed creators.
    LaunchedEffect(Unit) {
        val (h, allow, list) = withContext(Dispatchers.IO) {
            Triple(PlayerHistory.load(context), Creators.allowMode(context), Creators.allowed(context))
        }
        history = h
        allowMode = allow
        allowed = list
        if (allow && list.isNotEmpty()) {
            // Each allowed creator's videos tab, merged newest first and fetched a page at a time.
            runCatching {
                withContext(Dispatchers.IO) {
                    Extractor.init()
                    val tabs = list.map { (_, url) ->
                        async {
                            runCatching {
                                val info = ChannelInfo.getInfo(ServiceList.YouTube, url)
                                Creators.keepAvatar(url, info.avatars)
                                info.tabs.firstOrNull()
                            }.getOrNull()
                        }
                    }.awaitAll().filterNotNull()
                    val pages = Pages.Merged(tabs)
                    pages to pages.next()
                }
            }.onSuccess { (pages, first) -> feed = Listing(pages, first) }
        }
    }
    // A playing video's place is kept every few seconds, and as it leaves.
    LaunchedEffect(stream?.url) {
        if (stream == null) return@LaunchedEffect
        while (true) {
            delay(5_000)
            if (player.isPlaying) keepPlace()
        }
    }

    fun load(block: suspend () -> Unit) {
        scope.launch {
            loading = true
            error = null
            try {
                block()
            } catch (e: org.schabi.newpipe.extractor.search.SearchExtractor.NothingFoundException) {
                error = nothing
            } catch (e: Exception) {
                if (io.github.iverlein.shinkan.BuildConfig.DEBUG) android.util.Log.w("Player", "failed: $e", e)
                error = failed
            } finally {
                loading = false
            }
        }
    }

    fun search() {
        if (query.isBlank()) return
        focus.clearFocus()
        load {
            val (pages, first) = withContext(Dispatchers.IO) {
                Extractor.init()
                val hides = Creators.check(context)
                val keep = { item: StreamInfoItem -> !hides(item.uploaderUrl) }
                val extractor = ServiceList.YouTube.getSearchExtractor(query.trim())
                extractor.fetchPage()
                val initial = extractor.initialPage
                Pages.Of(initial.nextPage, keep) { extractor.getPage(it) } to
                    initial.items.filterIsInstance<StreamInfoItem>().filter(keep)
            }
            results = Listing(pages, first)
            stream = null
            channel = null
            player.stop()
        }
    }

    /** Plays [s] at the stream [Quality.choose] gives for [height], from [startMs]; false when there is none. */
    fun playQuality(s: Media.Streams, height: Int, startMs: Long): Boolean {
        val chosen = Quality.choose(s.options, height) ?: return false
        val picture = Media.source(s.videos[chosen.id].content)
        val sound = s.sound
        // A picture alone is joined with the sound stream; 360p comes with its own.
        val source = if (!chosen.withSound && sound != null) {
            MergingMediaSource(picture, Media.source(sound.content))
        } else {
            picture
        }
        playingHeight = chosen.height
        player.setMediaSource(source, startMs)
        player.prepare()
        player.play()
        return true
    }

    fun open(url: String) = load {
        val (info, hidden, next) = withContext(Dispatchers.IO) {
            Extractor.init()
            val hides = Creators.check(context)
            val info = StreamInfo.getInfo(ServiceList.YouTube, url)
            Triple(info, hides(info.uploaderUrl), info.relatedItems.filterIsInstance<StreamInfoItem>().filterNot { hides(it.uploaderUrl) })
        }
        if (hidden) {
            error = blocked
            return@load
        }
        stream = info
        related = next
        val resume = withContext(Dispatchers.IO) { PlayerHistory.load(context).positionOf(info.url) }
        val found = Media.streams(info)
        streams = found
        if (!playQuality(found, quality, resume)) {
            error = noStream
            player.stop()
        }
    }

    fun openChannel(url: String) = load {
        val shown = withContext(Dispatchers.IO) {
            Extractor.init()
            val hides = Creators.check(context)
            // A blocked channel is refused, not loaded (SPEC §6.3).
            if (hides(url)) return@withContext null
            val info = ChannelInfo.getInfo(ServiceList.YouTube, url)
            if (hides(info.url)) return@withContext null
            val keep = { item: StreamInfoItem -> !hides(item.uploaderUrl) }
            val tab = info.tabs.firstOrNull()
            val first = tab?.let { ChannelTabInfo.getInfo(ServiceList.YouTube, it) }
            val pages = if (tab != null && first != null) Pages.Of.channel(tab, first, keep) else Pages.Of(null, keep) { error("no tab") }
            Triple(info.name, pages, first?.relatedItems.orEmpty().filterIsInstance<StreamInfoItem>().filter(keep))
        }
        if (shown == null) {
            error = blocked
            return@load
        }
        channel = ChannelView(shown.first, Listing(shown.second, shown.third))
        stream = null
        player.stop()
    }

    /** The next page of [list], once at a time; a failure or a page with nothing shown waits for the child's tap. */
    fun more(list: Listing) {
        if (list.fetching || list.ended) return
        list.fetching = true
        list.failed = false
        list.stalled = false
        scope.launch {
            try {
                val got = withContext(Dispatchers.IO) { list.rest.next() }
                list.items.addAll(got)
                list.stalled = got.isEmpty()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (io.github.iverlein.shinkan.BuildConfig.DEBUG) android.util.Log.w("Player", "more failed: $e", e)
                list.failed = true
            } finally {
                list.ended = list.rest.ended
                list.fetching = false
            }
        }
    }

    BackHandler {
        when {
            stream != null -> {
                keepPlace()
                player.stop()
                stream = null
            }
            channel != null -> channel = null
            // Search results give way to Home before the door closes.
            results != null -> results = null
            else -> onClose()
        }
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val playing = stream
    if (landscape && playing != null) {
        // A playing video fills the screen in landscape (SPEC §8.6).
        AndroidView(factory = { detached(view) }, modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black))
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = resources.getString(R.string.door_close) }) {
                Text("✕", style = MaterialTheme.typography.titleLarge)
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.player_search)) },
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
            )
            TextButton(onClick = { search() }, modifier = Modifier.semantics { contentDescription = resources.getString(R.string.player_search) }) {
                Text("⌕", style = MaterialTheme.typography.titleLarge)
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        val shownChannel = channel
        val shownResults = results
        val shownFeed = feed
        // The list on screen that has more pages, if any.
        val paged = when {
            playing != null -> null
            shownChannel != null -> shownChannel.videos
            shownResults != null -> shownResults
            tab == Tab.HOME && allowMode -> shownFeed
            else -> null
        }
        val listState = rememberLazyListState()
        LaunchedEffect(paged, playing?.url) {
            // Back from a video, a list is where it was left; anything else starts at the top.
            listState.scrollToItem(paged?.index ?: 0, paged?.offset ?: 0)
            val list = paged ?: return@LaunchedEffect
            launch {
                snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                    .collect { (i, o) -> list.index = i; list.offset = o }
            }
            // The next page as the last rows come into view, again after each page while they still show.
            snapshotFlow {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= info.totalItemsCount - AHEAD && !list.fetching && !list.ended && !list.failed && !list.stalled
            }.collect { near -> if (near) more(list) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            /** The end of a paged list: a bar while a page comes, a retry when one failed, a button when one showed nothing. */
            fun footer(list: Listing) {
                if (list.fetching) {
                    item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp).height(2.dp)) }
                } else if (list.failed) {
                    item {
                        TextButton(onClick = { more(list) }, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                            Text(stringResource(R.string.player_more_failed), color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else if (list.stalled && !list.ended) {
                    item {
                        TextButton(onClick = { more(list) }, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                            Text(stringResource(R.string.player_more))
                        }
                    }
                }
            }
            when {
                playing != null -> {
                    item {
                        AndroidView(factory = { detached(view) }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(androidx.compose.ui.graphics.Color.Black))
                    }
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text(playing.name, style = MaterialTheme.typography.titleMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { openChannel(playing.uploaderUrl) }, modifier = Modifier.weight(1f)) { Text(playing.uploaderName) }
                                // The quality selector (SPEC §7.2): the heights on offer, the one playing marked.
                                val offered = streams?.let { Quality.heights(it.options) }.orEmpty()
                                var open by remember { mutableStateOf(false) }
                                if (offered.isNotEmpty()) {
                                    Box {
                                        TextButton(
                                            onClick = { open = true },
                                            modifier = Modifier.semantics { contentDescription = context.getString(R.string.player_quality) },
                                        ) { Text(stringResource(R.string.player_height_open, playingHeight)) }
                                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                            offered.forEach { h ->
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(if (h == playingHeight) R.string.player_height_playing else R.string.player_height, h)) },
                                                    onClick = {
                                                        open = false
                                                        quality = h
                                                        streams?.let { playQuality(it, h, player.currentPosition) }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            when {
                                segmentsFailed -> Text(stringResource(R.string.player_segments_failed), style = MaterialTheme.typography.bodySmall)
                                segments.isNotEmpty() -> Text(stringResource(R.string.player_segments_credit), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    items(related) { Item(it) { open(it.url) } }
                }
                shownChannel != null -> {
                    item { Text(shownChannel.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp)) }
                    items(shownChannel.videos.items) { Item(it) { open(it.url) } }
                    footer(shownChannel.videos)
                }
                shownResults != null -> {
                    items(shownResults.items) { Item(it) { open(it.url) } }
                    footer(shownResults)
                }
                // The player's three screens (SPEC §7.1), each of the mode set: Home, Creators, History.
                tab == Tab.HOME -> {
                    if (allowMode) {
                        item { Heading(stringResource(R.string.player_newest)) }
                        if (shownFeed != null) {
                            items(shownFeed.items) { Item(it) { open(it.url) } }
                            footer(shownFeed)
                        }
                    } else {
                        item { Note(stringResource(R.string.player_start)) }
                    }
                }
                tab == Tab.HISTORY -> {
                    if (history.entries.isEmpty()) item { Note(stringResource(R.string.player_no_history)) }
                    items(history.entries) { HistoryItem(it) { open(it.url) } }
                }
                else -> {
                    run {
                        val creators = if (allowMode) allowed else history.creators()
                        if (creators.isEmpty()) {
                            item { Note(stringResource(if (allowMode) R.string.player_no_allowed else R.string.player_no_recent)) }
                        }
                        items(creators) { (name, url) ->
                            // Each creator by its avatar first, then its name.
                            Row(
                                Modifier.fillMaxWidth().clickable { openChannel(url) }.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Avatar(url)
                                Text(name, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
        if (playing == null) {
            // Home, Creators and History at the bottom, as Grayjay has them; hidden while a video plays.
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t && shownResults == null && shownChannel == null,
                        onClick = {
                            tab = t
                            results = null
                            channel = null
                        },
                        icon = { androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(t.icon), contentDescription = null, Modifier.size(26.dp)) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        }
    }
}

/** One video of a list: its thumbnail, title, uploader and length. */
@Composable
private fun Item(item: StreamInfoItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(144.dp).aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceVariant)) {
            val url = Media.thumbnail(item.thumbnails)
            val bitmap by produceState<android.graphics.Bitmap?>(null, url) { value = url?.let { Images.load(it) } }
            bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Media.duration(item.duration)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).background(androidx.compose.ui.graphics.Color(0xAA000000)).padding(horizontal = 4.dp),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(item.uploaderName.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


/** The one player view, taken from the place it was in: portrait's list and landscape's full screen share it. */
private fun detached(view: PlayerView): PlayerView {
    (view.parent as? android.view.ViewGroup)?.removeView(view)
    return view
}

@Composable
private fun Heading(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))

@Composable
private fun Note(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))

/** A video of the history: its thumbnail with how much was watched, its title and uploader. */
@Composable
private fun HistoryItem(entry: History.Entry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(144.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceVariant)) {
                val bitmap by produceState<android.graphics.Bitmap?>(null, entry.thumbnail) {
                    value = entry.thumbnail.takeIf { it.isNotEmpty() }?.let { Images.load(it) }
                }
                bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            androidx.compose.material3.LinearProgressIndicator(
                progress = { entry.progress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                drawStopIndicator = {},
            )
        }
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(entry.uploader, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A creator's avatar in a circle, loaded off the main thread; a plain circle until it comes. */
@Composable
private fun Avatar(channelUrl: String) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, channelUrl) {
        value = withContext(Dispatchers.IO) { Creators.avatar(channelUrl) }?.let { Images.load(it) }
    }
    Box(
        Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}
