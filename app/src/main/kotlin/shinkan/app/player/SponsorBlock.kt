package shinkan.app.player

import android.content.Context
import android.util.Log
import io.github.iverlein.shinkan.BuildConfig
import shinkan.app.filter.BlocklistStore
import shinkan.core.net.Segment
import shinkan.core.net.Segments
import java.io.IOException

/**
 * The segment lookup of SPEC §7.5, the second named request of §2.1, to the public
 * API or the parent's mirror. The request's shape follows Tubular (GPL-3.0, NewPipe
 * with SponsorBlock); the data is SponsorBlock's, CC BY-NC-SA 4.0, credited on the
 * video's page. Outside the tunnel, as all of Shinkan's requests are (§5.5); no
 * cookies, and nothing of the video but the hash prefix leaves.
 */
object SponsorBlock {
    /** The segments to skip in the video [id]; fails when the server cannot be asked. Off the main thread. */
    fun fetch(context: Context, id: String): List<Segment> {
        val list = BlocklistStore(context).blocklist
        if (list.segments.isEmpty()) return emptyList()
        val address = Segments.address(list.mirror ?: Segments.API, id, list.segments)
        if (BuildConfig.DEBUG) Log.d(TAG, "asking $address")
        val request = okhttp3.Request.Builder().url(address).build()
        Extractor.OkDownloader.client.newCall(request).execute().use { response ->
            // The API answers 404 for a bucket with nothing marked.
            if (response.code == 404) return emptyList()
            if (!response.isSuccessful) throw IOException("segments: HTTP ${response.code}")
            return Segments.parse(response.body.string(), id, list.segments)
        }
    }

    private const val TAG = "SponsorBlock"
}
