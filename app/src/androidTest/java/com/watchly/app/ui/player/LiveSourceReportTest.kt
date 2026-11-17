package com.watchly.app.ui.player

import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** TEMPORARY diagnostic: records every request + console error a live embed page makes. */
@RunWith(AndroidJUnit4::class)
class LiveSourceReportTest {
    private val reqs = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val logs = java.util.Collections.synchronizedList(mutableListOf<String>())

    private fun probe(url: String) {
        reqs.clear(); logs.clear()
        val inst = InstrumentationRegistry.getInstrumentation()
        var finished = ""
        inst.runOnMainSync {
            val wv = WebView(inst.targetContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
            wv.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    if (logs.size < 40) logs.add(m.messageLevel().name + ":" + m.message().take(160))
                    return true
                }
            }
            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                    if (reqs.size < 120) reqs.add(r.url.toString().take(220))
                    return null
                }
                override fun onPageFinished(v: WebView, u: String) { finished = u }
            }
            wv.layout(0, 0, 1080, 1920)
            wv.loadUrl(url)
            Handler(Looper.getMainLooper()).postDelayed({ wv.stopLoading(); wv.destroy() }, 20_000L)
        }
        Thread.sleep(22_000L)
        val msg = "DIAG $url\nFINISHED=$finished\nREQUESTS(${reqs.size}):\n" +
            reqs.distinct().joinToString("\n") + "\nCONSOLE:\n" + logs.joinToString("\n")
        fail(msg)
    }

    @Test fun a_vidsrcTo() = probe("https://vidsrc.to/embed/movie/550")
    @Test fun c_vidlink() = probe("https://vidlink.pro/movie/550")
    @Test fun e_embedSu() = probe("https://embed.su/embed/movie/550")
}
