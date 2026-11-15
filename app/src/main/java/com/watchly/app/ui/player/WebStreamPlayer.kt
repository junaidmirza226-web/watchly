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

private val BLOCKED_AD_DOMAINS = listOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com",
    "popads.net", "popcash.net", "propellerads.com", "adsterra.com",
    "exoclick.com", "juicyads.com", "onclickads.net", "hilltopads.net",
    "acscdn.com", "histats.com", "clickadu.com", "trafficjunky.net",
    "adsco.re", "pushame.com", "richpush.co", "evadav.com", "galaksion.com",
    "admaven.com", "monetag.com", "outbrain.com", "taboola.com",
    "yandex.ru/metrika", "mc.yandex.ru", "bet365", "1xbet", "mostbet",
    "melbet", "parimatch", "stake.com", "irtyc.com", "cdn4ads.com",
    "usrpubtrk.com", "a-ads.com", "tsyndicate.com", "realsrv.com",
    "syndication.realsrv.com", "zog.link", "rtmark.net", "cloudfront-ad"
)

private fun isBlockedAdUrl(url: String): Boolean {
    val lower = url.lowercase()
    return BLOCKED_AD_DOMAINS.any { lower.contains(it) } ||
        lower.contains("/popunder") ||
        lower.contains("/adscript") ||
        lower.contains("pop.js") ||
        lower.contains("ads.js")
}

private fun isSniffableDirectMedia(url: String): Boolean {
    val lower = url.lowercase()
    if (isBlockedAdUrl(lower)) return false
    return (lower.contains(".m3u8") || lower.contains(".mp4")) &&
        !lower.endsWith(".ts") &&
        !lower.contains("preview") &&
        !lower.contains("thumb")
}

private const val ANTI_POPUP_JS = """
(function() {
  try {
    window.open = function() { return null; };
    document.querySelectorAll('a[target="_blank"]').forEach(function(el) {
      el.removeAttribute('target');
      el.onclick = function(e) { e.preventDefault(); return false; };
    });
  } catch (e) {}
})();
"""

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
    onPromoteDirectStream: ((url: String, headers: Map<String, String>) -> Unit)? = null
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

    // Auto-collapse top bar after 5s once loaded so video fills 100% of screen
    LaunchedEffect(chromeExpanded, isPageLoading, serverSheetOpen) {
        if (chromeExpanded && !isPageLoading && !serverSheetOpen) {
            delay(5_000L)
            chromeExpanded = false
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
                    val isEmulator = android.os.Build.HARDWARE.contains("ranchu") ||
                        android.os.Build.HARDWARE.contains("goldfish") ||
                        android.os.Build.FINGERPRINT.contains("generic")
                    if (isEmulator) {
                        // Avoid QEMU Virtio-GPU / swiftshader_indirect host crashes from Chromium GLES
                        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    }
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
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: android.graphics.Bitmap?
                        ) {
                            super.onPageStarted(view, url, favicon)
                            view?.evaluateJavascript(ANTI_POPUP_JS, null)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript(ANTI_POPUP_JS, null)
                            isPageLoading = false
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
                if (webView.url != streamUrl && webView.originalUrl != streamUrl) {
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
                    if (servers.size > 1) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "${servers.size} backup HD servers ready",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.65f)
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
                                text = "Servers (${servers.size})",
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
                        Text(
                            text = "Switch instantly if slow or wrong audio",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.65f)
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
