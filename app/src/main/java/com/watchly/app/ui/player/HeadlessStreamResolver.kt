/*
 * Watchly — Headless Stream Resolver (MovieBox-parity core).
 *
 * MovieBox-style apps open a title and the FILE just plays, and the
 * same file can be downloaded. They can do that because their sources
 * are direct media files. Watchly's sources are mostly player PAGES
 * (embeds) whose real media URL is created by JavaScript at runtime.
 *
 * This resolver closes that gap: it loads an embed page in a hidden
 * WebView (no UI, no ads shown), lets the page's own JavaScript do its
 * decrypt/resolve dance, and captures the real .m3u8/.mp4 request the
 * page makes — the exact direct file MovieBox would have. That file
 * then serves BOTH instant native playback (ExoPlayer) and Download.
 *
 * The WebView glue is deliberately thin; all decisions are in the pure
 * AdShield predicates so they stay JVM-testable.
 */
package com.watchly.app.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/** A direct media file captured from a player page. */
data class SniffedMedia(
    val url: String,
    val headers: Map<String, String>
)

object HeadlessStreamResolver {

    /** Same browser identity as the visible player WebView. */
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /** Per-candidate budget: real players resolve within a few seconds. */
    private const val PER_CANDIDATE_BUDGET_MS = 7_000L

    /** How many embed pages are tried before giving up. */
    private const val MAX_CANDIDATES = 3

    /**
     * Tries up to [MAX_CANDIDATES] of [candidates] in order and returns
     * the first direct media file any of them requests, or null.
     * Safe on any thread; does nothing expensive when the list is empty.
     */
    suspend fun sniff(context: Context, candidates: List<String>): SniffedMedia? =
        withContext(Dispatchers.Main) {
            for (pageUrl in candidates.take(MAX_CANDIDATES)) {
                val found = sniffOne(context, pageUrl) ?: continue
                return@withContext found
            }
            null
        }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffOne(context: Context, pageUrl: String): SniffedMedia? =
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            var resumed = false

            lateinit var webView: WebView
            fun complete(result: SniffedMedia?) {
                if (resumed) return
                resumed = true
                handler.removeCallbacksAndMessages(null)
                runCatching {
                    webView.stopLoading()
                    webView.destroy()
                }
                if (cont.isActive) cont.resume(result)
            }

            webView = WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = USER_AGENT
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message?
                    ): Boolean = false
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(
                        view: WebView?,
                        url: String?,
                        favicon: android.graphics.Bitmap?
                    ) {
                        super.onPageStarted(view, url, favicon)
                        view?.evaluateJavascript(AD_CLEAN_JS, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(AD_CLEAN_JS, null)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return null
                        if (isBlockedAdUrl(reqUrl)) {
                            return WebResourceResponse(
                                "text/plain", "utf-8", 204, "No Content",
                                emptyMap(), ByteArrayInputStream(ByteArray(0))
                            )
                        }
                        if (isSniffableDirectMedia(reqUrl)) {
                            val headers = mutableMapOf<String, String>()
                            request.requestHeaders?.forEach { (k, v) ->
                                if (k.equals("Referer", true) ||
                                    k.equals("Origin", true) ||
                                    k.equals("User-Agent", true)
                                ) {
                                    headers[k] = v
                                }
                            }
                            if (!headers.containsKey("Referer")) {
                                headers["Referer"] = pageUrl
                            }
                            complete(SniffedMedia(url = reqUrl, headers = headers))
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: android.webkit.WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        val code = error?.errorCode ?: return
                        if (isFatalLoadError(
                                errorCode = code,
                                isForMainFrame = request?.isForMainFrame == true,
                                failingUrl = request?.url?.toString(),
                                activeUrl = pageUrl
                            )
                        ) {
                            complete(null)
                        }
                    }
                }
                loadUrl(pageUrl)
            }

            handler.postDelayed({ complete(null) }, PER_CANDIDATE_BUDGET_MS)
            cont.invokeOnCancellation {
                resumed = true
                handler.removeCallbacksAndMessages(null)
                runCatching {
                    webView.stopLoading()
                    webView.destroy()
                }
            }
        }
}
