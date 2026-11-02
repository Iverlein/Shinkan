package shinkan.app.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Thumbnails for the player's lists, fetched with the extractor's client and kept in memory only. */
object Images {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = okhttp3.Request.Builder().url(url).header("User-Agent", Extractor.OkDownloader.USER_AGENT).build()
                Extractor.OkDownloader.client.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    BitmapFactory.decodeStream(r.body.byteStream())
                }
            }.getOrNull()?.also { cache.put(url, it) }
        }
    }
}
