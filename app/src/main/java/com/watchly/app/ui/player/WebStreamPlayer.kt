/*
 * Watchly — Web stream player layout for embedded video sources.
 *
 * Provides a seamless in-app streaming experience for providers that serve
 * web embeds (VidSrc, AutoEmbed, 2Embed, SmashyStream, etc.).
 * Includes hardware acceleration, full-screen playback, and clean back navigation.
 */
package com.watchly.app.ui.player

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.watchly.app.ui.theme.WatchlyGold
import com.watchly.app.ui.theme.WatchlyGoldLight

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebStreamPlayerLayout(
    streamUrl: String,
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
                    text = listOfNotNull(episodeLabel, "HD Cloud Stream").joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = WatchlyGoldLight
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                val url = request?.url?.toString() ?: return false
                                return !url.startsWith("http://") && !url.startsWith("https://")
                            }
                        }
                        loadUrl(streamUrl)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
