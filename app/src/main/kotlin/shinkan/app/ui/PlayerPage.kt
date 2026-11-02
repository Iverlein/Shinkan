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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.media3.ui.PlayerView
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

/** A channel as the player shows it: its name and its videos. */
private class ChannelView(val name: String, val items: List<StreamInfoItem>)

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
    var results by remember { mutableStateOf<List<StreamInfoItem>>(emptyList()) }
    var stream by remember { mutableStateOf<StreamInfo?>(null) }
    var related by remember { mutableStateOf<List<StreamInfoItem>>(emptyList()) }
    var channel by remember { mutableStateOf<ChannelView?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.player_error)
    val noStream = stringResource(R.string.player_no_stream)
    val blocked = stringResource(R.string.player_blocked)

    val player = remember {
        val http = DefaultHttpDataSource.Factory().setUserAgent(Extractor.OkDownloader.USER_AGENT)
        // A decoder that fails to start gives way to the next one. The emulator's own H.264 decoder
        // fails on its first frames; the beta prefers Android's software decoders there
        // (`setprop debug.shinkan.software_codecs 1`), the release takes the device's own.
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        if (io.github.iverlein.shinkan.BuildConfig.DEBUG && debugProperty("debug.shinkan.software_codecs")) {
            renderers.setMediaCodecSelector { mime, secure, tunneling ->
                androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).sortedBy { if (it.softwareOnly) 0 else 1 }
            }
        }
        ExoPlayer.Builder(context, renderers).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
    }
    val view = remember { PlayerView(context).apply { this.player = player } }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LifecycleResumeEffect(Unit) { onPauseOrDispose { player.pause() } }
    LaunchedEffect(Unit) { PlanClock.pauses.collect { player.pause() } }

    fun load(block: suspend () -> Unit) {
        scope.launch {
            loading = true
            error = null
            try {
                block()
            } catch (e: Exception) {
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
            results = withContext(Dispatchers.IO) {
                Extractor.init()
                val hides = Creators.check(context)
                val extractor = ServiceList.YouTube.getSearchExtractor(query.trim())
                extractor.fetchPage()
                extractor.initialPage.items.filterIsInstance<StreamInfoItem>().filterNot { hides(it.uploaderUrl) }
            }
            stream = null
            channel = null
            player.stop()
        }
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
        val chosen = Media.bestProgressive(info)
        if (chosen == null) {
            error = noStream
            player.stop()
        } else {
            player.setMediaItem(MediaItem.fromUri(chosen.content))
            player.prepare()
            player.play()
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
            val videos = info.tabs.firstOrNull()?.let { tab ->
                ChannelTabInfo.getInfo(ServiceList.YouTube, tab).relatedItems.filterIsInstance<StreamInfoItem>()
            }.orEmpty()
            ChannelView(info.name, videos.filterNot { hides(it.uploaderUrl) })
        }
        if (shown == null) {
            error = blocked
            return@load
        }
        channel = shown
        stream = null
        player.stop()
    }

    BackHandler {
        when {
            stream != null -> {
                player.stop()
                stream = null
            }
            channel != null -> channel = null
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
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            when {
                playing != null -> {
                    item {
                        AndroidView(factory = { detached(view) }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(androidx.compose.ui.graphics.Color.Black))
                    }
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text(playing.name, style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { openChannel(playing.uploaderUrl) }) { Text(playing.uploaderName) }
                        }
                    }
                    items(related) { Item(it) { open(it.url) } }
                }
                shownChannel != null -> {
                    item { Text(shownChannel.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp)) }
                    items(shownChannel.items) { Item(it) { open(it.url) } }
                }
                else -> items(results) { Item(it) { open(it.url) } }
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

/** A beta's switch for the emulator: the system property [name] set to 1. */
private fun debugProperty(name: String): Boolean = runCatching {
    val process = Runtime.getRuntime().exec(arrayOf("getprop", name))
    process.inputStream.bufferedReader().use { it.readText() }.trim() == "1"
}.getOrDefault(false)

/** The one player view, taken from the place it was in: portrait's list and landscape's full screen share it. */
private fun detached(view: PlayerView): PlayerView {
    (view.parent as? android.view.ViewGroup)?.removeView(view)
    return view
}
