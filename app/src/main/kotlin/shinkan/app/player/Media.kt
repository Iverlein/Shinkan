package shinkan.app.player

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import shinkan.core.net.Quality

/** What the player picks from what the extractor returns (SPEC §7.2). */
object Media {
    /** A video's playable streams: the pictures (with sound or alone), their choices, and the best sound. */
    class Streams(val videos: List<VideoStream>, val options: List<Quality.Option>, val sound: AudioStream?)

    /**
     * The streams of [info] the player can fetch as files (SPEC §7.2): the progressive
     * ones with sound, the pictures alone, and the sound to join to those, AAC first.
     */
    fun streams(info: StreamInfo): Streams {
        fun fetchable(d: DeliveryMethod) = d == DeliveryMethod.PROGRESSIVE_HTTP
        val videos = info.videoStreams.filter { it.isUrl && fetchable(it.deliveryMethod) } +
            info.videoOnlyStreams.filter { it.isUrl && fetchable(it.deliveryMethod) }
        val options = videos.mapIndexed { i, v ->
            Quality.Option(i, v.height.takeIf { it > 0 } ?: height(v.getResolution()), v.codec.orEmpty(), !v.isVideoOnly)
        }
        val sound = info.audioStreams.filter { it.isUrl && fetchable(it.deliveryMethod) }
            .sortedWith(compareBy<AudioStream>({ if (it.format == MediaFormat.M4A) 0 else 1 }, { -it.averageBitrate }))
            .firstOrNull()
        return Streams(videos, options, sound)
    }

    /**
     * A media source for one stream address (SPEC §7.2). The pictures alone and the sound
     * come from the visionOS client, the one whose addresses the platform serves to the
     * end without a proof-of-origin token; they are fetched with that client's user agent
     * and always with a range, without which the platform cuts the answer short
     * (both found 2026-11-02).
     */
    @OptIn(UnstableApi::class)
    fun source(url: String): MediaSource {
        val agent = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
            YoutubeParsingHelper.getVisionOsUserAgent(NewPipe.getPreferredLocalization())
        } else {
            Extractor.OkDownloader.USER_AGENT
        }
        // Media3 sends no range from position 0; it replaces this one when it seeks.
        val http = DefaultHttpDataSource.Factory().setUserAgent(agent)
            .setDefaultRequestProperties(mapOf("Range" to "bytes=0-"))
        return ProgressiveMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(url))
    }

    /** "720p60" → 720; anything else 0. */
    fun height(resolution: String?): Int = resolution?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0

    /** The thumbnail nearest [height] pixels high, or the first. */
    fun thumbnail(images: List<Image>, height: Int = 180): String? =
        images.minByOrNull { if (it.height > 0) kotlin.math.abs(it.height - height) else Int.MAX_VALUE }?.url
            ?: images.firstOrNull()?.url

    /** Seconds as m:ss or h:mm:ss; nothing for a live stream or an unknown length. */
    fun duration(seconds: Long): String? {
        if (seconds <= 0) return null
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
