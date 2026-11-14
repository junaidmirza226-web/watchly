/*
 * Watchly — in-app browser (Phase D).
 *
 * Where-to-watch used to throw the user into the external browser;
 * now the provider's watch page opens HERE, in a simple WebView
 * screen with Watchly's own chrome, so the user stays in the app
 * and one Back press returns to the title. The URL is handed over
 * through [WatchPageStore] (same pattern as TitleDetailsStore);
 * only http/https URLs from TMDB's link field ever reach it.
 */
package com.watchly.app.ui.browser

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.watchly.app.ui.theme.WatchlyGold
import java.net.URI

/** In-memory handoff for the URL the browser screen should open. */
object WatchPageStore {

    private var pending: String? = null

    fun open(url: String) {
        pending = url
    }

    fun take(): String? = pending

    fun clear() {
        pending = null
    }
}

/** True for http/https URLs — the only schemes this screen loads. */
internal fun isWebUrl(url: String): Boolean =
    url.startsWith("https://") || url.startsWith("http://")

@Composable
fun InAppBrowserScreen(
    url: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember { mutableStateOf("") }
    val host = remember(url) {
        runCatching { URI(url).host }.getOrNull() ?: url
    }

    // Back walks the page history first, then leaves the screen.
    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = WatchlyGold
                )
            }
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pageTitle.ifBlank { host },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (pageTitle.isNotBlank()) {
                    Text(
                        text = host,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            pageTitle = view?.title.orEmpty()
                        }
                    }
                    loadUrl(url)
                    webView = this
                }
            },
            // A WebView is a black box to TalkBack without a
            // label (Test Lab run 4: content labeling) — say what
            // it holds.
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Web page: $host" }
        )
    }
}
