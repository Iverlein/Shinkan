package shinkan.app.player

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * NewPipe Extractor set up for the player (SPEC §7.2): its HTTP through OkHttp
 * with a fixed desktop user agent, no cookies kept, the device's language and
 * country. These are the player's requests to the platform, the only ones of
 * Shinkan's own besides the creator lookup and the segments (SPEC §2.1); they
 * leave outside the filter's tunnel, as all of Shinkan's do (SPEC §5.5).
 */
object Extractor {
    @Volatile private var ready = false

    fun init() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val locale = Locale.getDefault()
            NewPipe.init(
                OkDownloader,
                Localization.fromLocale(locale),
                ContentCountry(locale.country.ifEmpty { "US" }),
            )
            ready = true
        }
    }

    /** The extractor's [Downloader] on OkHttp. */
    object OkDownloader : Downloader() {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        val client: OkHttpClient = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        override fun execute(request: Request): Response {
            val body = request.dataToSend()?.toRequestBody()
            val builder = okhttp3.Request.Builder()
                .url(request.url())
                .method(request.httpMethod(), body)
                .header("User-Agent", USER_AGENT)
            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }
            client.newCall(builder.build()).execute().use { response ->
                if (response.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
                return Response(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    response.body.string(),
                    response.request.url.toString(),
                )
            }
        }
    }
}
