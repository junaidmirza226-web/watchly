/*
 * Watchly — Cinema-grade cloud stream player with:
 *  - Auto-landscape edge-to-edge immersive fullscreen (restored on exit)
 *  - 1-tap floating Server Switcher (VidLink, VidSrc, MultiEmbed, AutoEmbed, 2Embed, etc.)
 *  - Real-time Stream Sniffer that detects live .m3u8 / .mp4 streams to unlock Watchly's
 *    native Media3 ExoPlayer (subtitles, gestures, PiP, offline download)
 *  - Network-level Ad / Popunder / Redirect Shield (65+ ad networks blocked at request level)
 *  - 1-tap Next Episode support for TV & Anime series
 */
package com.watchly.app.ui.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.ui.theme.WatchlyBlack
import com.watchly.app.ui.theme.WatchlyGold
import com.watchly.app.ui.theme.WatchlyGoldLight
import com.watchly.app.ui.theme.WatchlySurface
import com.watchly.app.ui.theme.WatchlySurfaceVariant
import java.io.ByteArrayInputStream
import kotlinx.coroutines.delay

// Ad/popup/banner shield lives in AdShield.kt (same package): network-level
// domain blocking + DOM-level banner/overlay cleanup ([AD_CLEAN_JS]).

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebStreamPlayerLayout(
    streamUrl: String,
    titleName: String,
    episodeLabel: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    servers: List<PlayableFile> = emptyList(),
    onSelectServer: (PlayableFile) -> Unit = {},
    hasNextEpisode: Boolean = false,
    onNextEpisode: () -> Unit = {},
    onPromoteDirectStream: ((url: String, headers: Map<String, String>) -> Unit)? = null,
    /** Called when the embed page itself is dead (main-frame load failure). */
    onSourceDead: () -> Unit = {},
    /** Failover in progress ("This source failed — trying the next one…"). */
    statusNote: String? = null,
    /** Terminal: every source failed — full-card overlay with Retry. */
    errorMessage: String? = null,
    onRetry: () -> Unit = {},
    /** Bumped by the ViewModel to force-reload the same URL (Retry). */
    reloadTick: Int = 0
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity

    var landscapeLocked by remember { mutableStateOf(false) }

    // Immersive system-bar fullscreen + clean WebView teardown on exit
    DisposableEffect(activity) {
        val prevOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val window = activity?.window
        val insetsController = if (window != null) {
            WindowCompat.getInsetsController(window, view)
        } else null

        insetsController?.let { controller ->
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            activity?.requestedOrientation = prevOrientation
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    var isPageLoading by remember(streamUrl) { mutableStateOf(true) }
    var chromeExpanded by remember { mutableStateOf(true) }
    var serverSheetOpen by remember { mutableStateOf(false) }
    var sniffedStream by remember(streamUrl) {
        mutableStateOf<Pair<String, Map<String, String>>?>(null)
    }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // One death report per source load (aborted-load noise can fire
    // several callbacks for the same dying page).
    val deadReported = remember { mutableStateOf(false) }
    // Identifies the current load ("url#tick"); the WebView reloads
    // only when this changes — that is what makes Retry re-load the
    // very same URL instead of being a no-op.
    val loadKey = remember { mutableStateOf("$streamUrl#$reloadTick") }

    // Auto-collapse top bar after 5s once loaded so video fills 100% of screen
    LaunchedEffect(chromeExpanded, isPageLoading, serverSheetOpen) {
        if (chromeExpanded && !isPageLoading && !serverSheetOpen) {
            delay(5_000L)
            chromeExpanded = false
        }
    }

    // Source-death watchdog: a black-hole network hangs the load and
    // never reports an error at all — after the deadline, treat the
    // source as dead so failover moves on instead of spinning forever.
    LaunchedEffect(streamUrl, reloadTick) {
        delay(LOAD_DEADLINE_MS)
        if (isPageLoading && !deadReported.value) {
            deadReported.value = true
            onSourceDead()
        }
    }

    val activeServerName = remember(streamUrl, servers) {
        servers.firstOrNull { it.url == streamUrl }?.qualityLabel
            ?: runCatching { java.net.URI(streamUrl).host?.removePrefix("www.") }.getOrNull()
            ?: "Cloud HD"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(android.graphics.Color.BLACK)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        setSupportMultipleWindows(true) // trap window.open in onCreateWindow
                        javaScriptCanOpenWindowsAutomatically = false
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        userAgentString =
                            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message?
                        ): Boolean {
                            // Never allow ad popups to open secondary windows
                            return false
                        }

                        // Fake "system" alert/confirm/prompt dialogs (the
                        // "your phone is infected / you won a prize" class)
                        // are swallowed whole — the player never needs one.
                        override fun onJsAlert(
                            view: WebView?,
                            url: String?,
                            message: String?,
                            result: android.webkit.JsResult?
                        ): Boolean {
                            result?.confirm()
                            return true
                        }

                        override fun onJsConfirm(
                            view: WebView?,
                            url: String?,
                            message: String?,
                            result: android.webkit.JsResult?
                        ): Boolean {
                            result?.confirm()
                            return true
                        }

                        override fun onJsPrompt(
                            view: WebView?,
                            url: String?,
                            message: String?,
                            defaultValue: String?,
                            result: android.webkit.JsPromptResult?
                        ): Boolean {
                            result?.confirm(defaultValue)
                            return true
                        }
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

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            val code = error?.errorCode ?: return
                            if (isFatalLoadError(
                                    errorCode = code,
                                    isForMainFrame = request?.isForMainFrame == true,
                                    failingUrl = request?.url?.toString(),
                                    activeUrl = streamUrl
                                ) && !deadReported.value
                            ) {
                                deadReported.value = true
                                view?.post { onSourceDead() }
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?
                        ) {
                            super.onReceivedHttpError(view, request, errorResponse)
                            if (isFatalHttpError(
                                    statusCode = errorResponse?.statusCode ?: 0,
                                    isForMainFrame = request?.isForMainFrame == true
                                ) && !deadReported.value
                            ) {
                                deadReported.value = true
                                view?.post { onSourceDead() }
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript(AD_CLEAN_JS, null)
                            isPageLoading = false
                            // Dead-end detection: a page can load perfectly
                            // (HTTP 200) and still say "MEDIA NOT FOUND".
                            // Read the visible text + video count and switch
                            // source instead of spinning forever.
                            if (!deadReported.value) {
                                view?.evaluateJavascript(
                                    "(function(){try{" +
                                        "var t=(document.body&&document.body.innerText||'').slice(0,4000);" +
                                        "var v=document.querySelectorAll('video').length;" +
                                        "return JSON.stringify([t,v]);}catch(e){return '[\"\",0]';}})()"
                                ) { raw ->
                                    val parsed = runCatching {
                                        val inner = org.json.JSONTokener(raw).nextValue() as? String
                                            ?: return@runCatching null
                                        val arr = org.json.JSONArray(inner)
                                        arr.getString(0) to (arr.getInt(1) > 0)
                                    }.getOrNull()
                                    val (pageText, hasVideo) = parsed ?: return@evaluateJavascript
                                    if (isDeadPageText(pageText, hasVideo) && !deadReported.value) {
                                        deadReported.value = true
                                        view?.post { onSourceDead() }
                                    }
                                }
                            }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            if (isBlockedAdUrl(reqUrl)) {
                                return WebResourceResponse(
                                    "text/plain",
                                    "utf-8",
                                    204,
                                    "No Content",
                                    emptyMap(),
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }
                            if (sniffedStream == null && isSniffableDirectMedia(reqUrl)) {
                                val headers = HashMap<String, String>()
                                request.requestHeaders?.forEach { (k, v) ->
                                    if (k.equals("Referer", true) ||
                                        k.equals("Origin", true) ||
                                        k.equals("User-Agent", true)
                                    ) {
                                        headers[k] = v
                                    }
                                }
                                if (!headers.containsKey("Referer")) {
                                    headers["Referer"] = streamUrl
                                }
                                view?.post {
                                    if (sniffedStream == null) {
                                        sniffedStream = reqUrl to headers
                                    }
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val targetUrl = request?.url?.toString() ?: return false
                            if (!targetUrl.startsWith("https://") && !targetUrl.startsWith("http://")) {
                                return true
                            }
                            if (isBlockedAdUrl(targetUrl)) return true
                            // Block top-level navigation hijack to unrelated ad domains
                            if (request.isForMainFrame) {
                                val baseHost = runCatching { java.net.URI(streamUrl).host }.getOrNull()
                                val targetHost = runCatching { java.net.URI(targetUrl).host }.getOrNull()
                                if (baseHost != null && targetHost != null) {
                                    val baseRoot = baseHost.split(".").takeLast(2).joinToString(".")
                                    if (!targetHost.contains(baseRoot, ignoreCase = true)) {
                                        return true
                                    }
                                }
                            }
                            return false
                        }
                    }
                    webViewRef = this
                    loadUrl(streamUrl)
                }
            },
            update = { webView ->
                val key = "$streamUrl#$reloadTick"
                if (loadKey.value != key) {
                    loadKey.value = key
                    deadReported.value = false
                    sniffedStream = null
                    isPageLoading = true
                    webView.loadUrl(streamUrl)
                }
            },
            onRelease = { webView ->
                runCatching {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.clearHistory()
                    webView.destroy()
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Loading overlay
        if (isPageLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.88f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = WatchlyGold)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Connecting to $activeServerName…",
                        style = MaterialTheme.typography.titleMedium,
                        color = WatchlyGoldLight
                    )
                }
            }
        }

        // Failover in progress — the system is switching sources itself.
        statusNote?.let { note ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .border(1.dp, WatchlyGold.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = WatchlyGoldLight
                )
            }
        }

        // Terminal: every source failed — Retry, or pick a server by hand.
        errorMessage?.let { message ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.titleMedium,
                        color = WatchlyGold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(18.dp))
                    Surface(
                        onClick = onRetry,
                        shape = RoundedCornerShape(999.dp),
                        color = WatchlyGold
                    ) {
                        Text(
                            text = "Retry",
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = Color(0xFF1A1405)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        onClick = { serverSheetOpen = true },
                        shape = RoundedCornerShape(999.dp),
                        color = Color.Transparent,
                        modifier = Modifier.border(
                            1.dp,
                            WatchlyGold.copy(alpha = 0.6f),
                            RoundedCornerShape(999.dp)
                        )
                    ) {
                        Text(
                            text = "Switch source",
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = WatchlyGold
                        )
                    }
                }
            }
        }

        // Subtle floating pill when top bar is auto-hidden — lets user summon Back / Servers in 1 tap
        if (!chromeExpanded && !serverSheetOpen) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(1.dp, WatchlyGold.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                    .clickable { chromeExpanded = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Show player controls",
                    tint = WatchlyGold,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = activeServerName,
                    style = MaterialTheme.typography.labelMedium,
                    color = WatchlyGoldLight
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = WatchlyGoldLight,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Expanded luxury top bar
        AnimatedVisibility(
            visible = chromeExpanded,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.90f),
                                Color.Black.copy(alpha = 0.55f),
                                Color.Transparent
                            )
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = WatchlyGold
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { chromeExpanded = false }
                ) {
                    Text(
                        text = titleName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOfNotNull(
                            episodeLabel,
                            "Server: $activeServerName",
                            "Ad-Shield Active"
                        ).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = WatchlyGoldLight,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Native ExoPlayer upgrade pill when Sniffer catches a direct .m3u8 / .mp4 stream!
                val captured = sniffedStream
                if (captured != null && onPromoteDirectStream != null) {
                    Surface(
                        onClick = { onPromoteDirectStream(captured.first, captured.second) },
                        shape = RoundedCornerShape(999.dp),
                        color = WatchlyGold,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Hd,
                                contentDescription = null,
                                tint = Color(0xFF1A1405),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Native Player",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1A1405)
                            )
                        }
                    }
                }

                // 1-tap Widescreen Landscape / Portrait toggle
                Surface(
                    onClick = {
                        val nextLandscape = !landscapeLocked
                        landscapeLocked = nextLandscape
                        activity?.requestedOrientation = if (nextLandscape) {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                    },
                    shape = RoundedCornerShape(999.dp),
                    color = WatchlySurfaceVariant.copy(alpha = 0.92f),
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .border(1.dp, WatchlyGold.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                ) {
                    Text(
                        text = if (landscapeLocked) "Portrait" else "Landscape",
                        style = MaterialTheme.typography.labelLarge,
                        color = WatchlyGoldLight,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }

                // 1-tap Next Episode pill for TV / Anime series
                if (hasNextEpisode) {
                    Surface(
                        onClick = onNextEpisode,
                        shape = RoundedCornerShape(999.dp),
                        color = WatchlySurfaceVariant.copy(alpha = 0.92f),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .border(1.dp, WatchlyGold.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = null,
                                tint = WatchlyGold,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Next Ep",
                                style = MaterialTheme.typography.labelLarge,
                                color = WatchlyGoldLight
                            )
                        }
                    }
                }

                // 1-tap Server Switcher button
                if (servers.size > 1) {
                    Surface(
                        onClick = { serverSheetOpen = !serverSheetOpen },
                        shape = RoundedCornerShape(999.dp),
                        color = WatchlySurfaceVariant.copy(alpha = 0.92f),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .border(1.dp, WatchlyGold, RoundedCornerShape(999.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Dns,
                                contentDescription = null,
                                tint = WatchlyGold,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Switch source",
                                style = MaterialTheme.typography.labelLarge,
                                color = WatchlyGold
                            )
                        }
                    }
                }
            }
        }

        // Instant floating Server Switcher ribbon
        AnimatedVisibility(
            visible = serverSheetOpen && servers.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 62.dp, start = 12.dp, end = 12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = WatchlyBlack.copy(alpha = 0.95f),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, WatchlyGold.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Switch Streaming Server",
                            style = MaterialTheme.typography.titleSmall,
                            color = WatchlyGold,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = { serverSheetOpen = false },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close server list",
                                tint = WatchlyGoldLight,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp)
                    ) {
                        itemsIndexed(servers) { idx, server ->
                            val selected = server.url == streamUrl
                            val label = server.qualityLabel ?: "Server ${idx + 1}"
                            Surface(
                                onClick = {
                                    serverSheetOpen = false
                                    onSelectServer(server)
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) WatchlyGold else WatchlySurface,
                                modifier = Modifier.border(
                                    width = 1.dp,
                                    color = if (selected) WatchlyGold else WatchlyGold.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (selected) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = Color(0xFF1A1405),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Column {
                                        Text(
                                            text = "${idx + 1}. $label",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (selected) Color(0xFF1A1405) else Color.White
                                        )
                                        Text(
                                            text = if (idx == 0) "Fastest • Auto HD" else "Backup HD",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (selected) {
                                                Color(0xFF1A1405).copy(alpha = 0.8f)
                                            } else {
                                                WatchlyGoldLight
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webViewRef?.stopLoading()
                webViewRef?.loadUrl("about:blank")
                webViewRef?.destroy()
            }
            webViewRef = null
        }
    }
}

/** How long a source may sit "loading" before it counts as dead. */
private const val LOAD_DEADLINE_MS = 60_000L

/**
 * True when a WebView load failure means the SOURCE itself is dead
 * (so the player should fail over) as opposed to navigation noise:
 * cancelled loads surface as [WebViewClient.ERROR_UNKNOWN] and
 * intent/scheme stunts as [WebViewClient.ERROR_UNSUPPORTED_SCHEME],
 * and failures for an abandoned URL belong to a load we already left.
 */
internal fun isFatalLoadError(
    errorCode: Int,
    isForMainFrame: Boolean,
    failingUrl: String?,
    activeUrl: String
): Boolean =
    isForMainFrame &&
        failingUrl != null && failingUrl == activeUrl &&
        errorCode != WebViewClient.ERROR_UNKNOWN &&
        errorCode != WebViewClient.ERROR_UNSUPPORTED_SCHEME

/** True when a main-frame HTTP status means the source is dead. */
internal fun isFatalHttpError(statusCode: Int, isForMainFrame: Boolean): Boolean =
    isForMainFrame && statusCode >= 400
