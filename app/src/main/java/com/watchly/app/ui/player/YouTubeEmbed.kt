/*
 * Watchly — official YouTube embedded player (cycle 4).
 *
 * YouTube titles never play through Media3: YouTube's terms only
 * allow playback through their own player, so this is a WebView
 * hosting the standard youtube.com/embed iframe for the video id.
 * No stream is scraped, proxied or re-hosted — YouTube serves and
 * controls playback (including its own ads/region rules) exactly as
 * it does in a browser.
 */
package com.watchly.app.ui.player

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.watchly.app.ui.theme.WatchlyGold
import com.watchly.app.ui.theme.WatchlyGoldLight

/**
 * Full player layout for one official-YouTube video: black top bar
 * (back + title) over the embedded player.
 */
@Composable
fun YouTubePlayerLayout(
    videoId: String,
    titleName: String,
    episodeLabel: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = WatchlyGold
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = titleName,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(episodeLabel, "Official YouTube")
                        .joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = WatchlyGoldLight
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            YouTubeEmbed(
                videoId = videoId,
                // TalkBack label for the WebView (Test Lab run 4:
                // content labeling) — a bare WebView is a black box.
                contentDescription = "YouTube video: $titleName",
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** The WebView hosting YouTube's official embed iframe. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubeEmbed(
    videoId: String,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                // The official player is JavaScript — it cannot run
                // (and YouTube will not serve it) without JS enabled.
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
                tag = videoId
                loadDataWithBaseURL(
                    "https://www.youtube.com",
                    embedHtml(videoId),
                    "text/html",
                    "utf-8",
                    null
                )
            }
        },
        update = { webView ->
            if (webView.tag != videoId) {
                webView.tag = videoId
                webView.loadDataWithBaseURL(
                    "https://www.youtube.com",
                    embedHtml(videoId),
                    "text/html",
                    "utf-8",
                    null
                )
            }
        },
        modifier = modifier.semantics {
            this.contentDescription = contentDescription
        }
    )
}

/** Minimal page whose only job is the official embed iframe. */
private fun embedHtml(videoId: String): String =
    """
    <!DOCTYPE html>
    <html>
    <head>
      <meta name="viewport" content="width=device-width, initial-scale=1.0">
      <style>
        html, body { margin: 0; padding: 0; background: #000; height: 100%; }
        iframe { position: absolute; inset: 0; width: 100%; height: 100%; border: 0; }
      </style>
    </head>
    <body>
      <iframe
        src="https://www.youtube.com/embed/$videoId?autoplay=1&rel=0&playsinline=1"
        allow="accelerometer; autoplay; encrypted-media; picture-in-picture"
        allowfullscreen>
      </iframe>
    </body>
    </html>
    """.trimIndent()
