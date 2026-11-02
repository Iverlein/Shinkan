package shinkan.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.iverlein.shinkan.BuildConfig
import io.github.iverlein.shinkan.R
import shinkan.app.admin.PolicyStore
import shinkan.app.browser.Filters
import shinkan.app.filter.HostCheck
import shinkan.app.plan.PlanClock
import shinkan.core.net.Address
import shinkan.core.net.FilterList
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * The browser door (SPEC §7.6): Shinkan's own page on the system WebView.
 * Every request but the page itself passes EasyList and EasyPrivacy
 * ([Filters]) in `shouldInterceptRequest`; a blocked one gets an empty 403, so
 * the page's own error handling sees it fail. Names resolve through the system,
 * so the DNS filter and the built-in list answer them first.
 *
 * JavaScript and storage are on so sites work, and kept between sessions with
 * the first-party cookies; third-party cookies are off, file and content access
 * off, Safe Browsing off (it would send addresses to Google: SPEC §2.1),
 * downloads ignored, a new window opened in place, and only http and https
 * ever loaded. No history list is kept beyond the session's Back. A rest
 * (`PlanClock.pauses`) and leaving the door pause the page and its timers.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserPage(onClose: () -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val start = remember { PolicyStore(context).policy.start }
    var address by remember { mutableStateOf(start ?: "") }
    var loading by remember { mutableStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    val page = remember { AtomicReference("") }
    val blockedText = stringResource(R.string.browser_blocked)
    val paper = MaterialTheme.colorScheme.background

    val web = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // The emulator draws no Chromium page content on its software GPU; the beta alone may
            // draw the page in a software layer there (`setprop debug.shinkan.webview_software 1`).
            if (BuildConfig.DEBUG && softwareWebView()) setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.safeBrowsingEnabled = false
            settings.setSupportMultipleWindows(false)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !Address.allowed(request.url.toString())

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    if (!Address.allowed(url)) return null
                    // The block list first, as the DNS filter applies it to every other app: Shinkan's
                    // own traffic is outside the tunnel (SPEC §5.5). A page gets a page saying so.
                    if (request.isForMainFrame) HostCheck.refresh(context)
                    if (HostCheck.blocks(context, request.url.host ?: "")) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "blocked host ${request.url.host}")
                        return if (request.isForMainFrame) blockedPage(blockedText) else empty403()
                    }
                    val type = FilterList.typeOf(url, request.requestHeaders["Accept"], request.isForMainFrame) ?: return null
                    if (!Filters.get(context).blocks(FilterList.Request(url, page.get(), type))) return null
                    if (BuildConfig.DEBUG) Log.d(TAG, "blocked $type $url")
                    return empty403()
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    page.set(android.net.Uri.parse(url).host ?: "")
                    address = if (Address.allowed(url)) url else ""
                    loading++
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (BuildConfig.DEBUG && request.isForMainFrame) Log.d(TAG, "page failed: ${error.errorCode} ${error.description}")
                }

                override fun onPageFinished(view: WebView, url: String) {
                    loading = 0
                    canGoBack = view.canGoBack()
                    CookieManager.getInstance().flush()
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    page.set(android.net.Uri.parse(url).host ?: "")
                    address = if (Address.allowed(url)) url else ""
                    canGoBack = view.canGoBack()
                }
            }
            // A WebView that has loaded nothing draws no frame, and its window stays white, the
            // address bar with it (found 2026-11-02): with no start page it shows a blank page of its own.
            if (start != null) {
                loadUrl(start)
            } else {
                val bg = String.format("#%06X", paper.toArgb() and 0xFFFFFF)
                loadDataWithBaseURL(null, "<html><body style='background:$bg'></body></html>", "text/html", "utf-8", null)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().flush()
            web.stopLoading()
            web.destroy()
        }
    }
    // The door hidden or a rest begun: the page and its timers stop, a video with them.
    LifecycleResumeEffect(Unit) {
        web.onResume()
        web.resumeTimers()
        onPauseOrDispose {
            web.onPause()
            web.pauseTimers()
        }
    }
    LaunchedEffect(Unit) {
        PlanClock.pauses.collect {
            web.onPause()
            web.pauseTimers()
        }
    }
    BackHandler { if (web.canGoBack()) web.goBack() else onClose() }

    fun go() {
        val url = Address.of(address) ?: return
        focus.clearFocus()
        web.loadUrl(url)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Bar("✕", stringResource(R.string.door_close), onClick = onClose)
            Bar("←", stringResource(R.string.browser_back), enabled = canGoBack) { web.goBack() }
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.browser_address)) },
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
            )
            Bar("↻", stringResource(R.string.browser_reload)) { web.reload() }
        }
        if (loading > 0) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) else androidx.compose.foundation.layout.Spacer(Modifier.height(2.dp))
        AndroidView(factory = { web }, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}

/** A button of the bar: one sign, named for the screen reader and for `emulator.py tap --desc`. */
@Composable
private fun Bar(sign: String, name: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = name }) {
        Text(sign, style = MaterialTheme.typography.titleLarge)
    }
}

/** An empty 403: the page's own error handling sees the request fail. */
private fun empty403() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

/** The page shown for a blocked site, in the door's own words. */
private fun blockedPage(text: String): WebResourceResponse {
    val escaped = text.replace("&", "&amp;").replace("<", "&lt;")
    val html = "<!doctype html><meta name=viewport content='width=device-width'>" +
        "<body style='font-family:sans-serif;padding:2em;color:#111311;background:#F5F1E8'><h2>$escaped</h2>"
    return WebResourceResponse("text/html", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(html.toByteArray()))
}

/** The beta's switch for the emulator: the system property `debug.shinkan.webview_software` set to 1. */
private fun softwareWebView(): Boolean = runCatching {
    val process = Runtime.getRuntime().exec(arrayOf("getprop", "debug.shinkan.webview_software"))
    process.inputStream.bufferedReader().use { it.readText() }.trim() == "1"
}.getOrDefault(false)

private const val TAG = "Browser"
