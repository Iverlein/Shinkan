package shinkan.app.player

import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

/** What the player picks from what the extractor returns (SPEC §7.2). */
object Media {
    /** The best stream with sound in one file, up to [maxHeight]: the progressive stream Media3 plays as it is. */
    fun bestProgressive(info: StreamInfo, maxHeight: Int = 720): VideoStream? =
        info.videoStreams
            .filter { !it.isVideoOnly && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .sortedBy { height(it.getResolution()) }
            .let { all -> all.lastOrNull { height(it.getResolution()) <= maxHeight } ?: all.firstOrNull() }

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
