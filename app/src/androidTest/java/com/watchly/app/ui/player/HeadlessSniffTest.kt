/*
 * Watchly — headless stream resolver test (MovieBox-parity core).
 *
 * Loads a self-contained data: page whose <video> requests a fake
 * .m3u8 and proves the hidden WebView captures that exact URL — the
 * mechanism that turns "player page" into "direct file" for both
 * playback and download. No network is involved.
 */
package com.watchly.app.ui.player

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeadlessSniffTest {

    @Test
    fun capturesTheMediaUrlAPlayerPageRequests() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Real player pages fetch their playlist with JavaScript (hls.js
        // and friends) — that request is what the sniffer captures.
        val page = "data:text/html," +
            java.net.URLEncoder.encode(
                "<html><body><script>" +
                    "fetch('https://fake.test/movie/master.m3u8',{mode:'no-cors'});" +
                    "</script></body></html>",
                "UTF-8"
            )

        val sniffed = runBlocking {
            HeadlessStreamResolver.sniff(context, listOf(page))
        }

        assertNotNull("the sniffer must capture the fetched playlist", sniffed)
        assertEquals("https://fake.test/movie/master.m3u8", sniffed?.url)
    }

    @Test
    fun capturesDirectMp4VideoToo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val page = "data:text/html," +
            java.net.URLEncoder.encode(
                "<html><body><video autoplay preload=\"auto\" " +
                    "src=\"https://fake.test/movie/video.mp4\"></video></body></html>",
                "UTF-8"
            )

        val sniffed = runBlocking {
            HeadlessStreamResolver.sniff(context, listOf(page))
        }

        assertNotNull("the sniffer must capture the video src", sniffed)
        assertEquals("https://fake.test/movie/video.mp4", sniffed?.url)
    }

    @Test
    fun returnsNullForPageWithoutMedia() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val page = "data:text/html," +
            java.net.URLEncoder.encode(
                "<html><body><p>nothing to play</p></body></html>",
                "UTF-8"
            )

        val sniffed = runBlocking {
            HeadlessStreamResolver.sniff(context, listOf(page))
        }

        assertEquals(null, sniffed)
    }

    @Test
    fun webViewLivesOnTheMainThread() {
        // Guard: WebView may only be touched on a Looper thread; the
        // resolver's Dispatchers.Main hop is what makes sniff() safe.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val view = WebView(context)
            view.destroy()
        }
    }
}
