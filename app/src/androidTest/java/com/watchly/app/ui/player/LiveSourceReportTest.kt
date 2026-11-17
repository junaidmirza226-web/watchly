package com.watchly.app.ui.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** TEMPORARY diagnostic: live sniff report from CI network. Always fails to surface the report. */
@RunWith(AndroidJUnit4::class)
class LiveSourceReportTest {
    @Test
    fun liveReport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val urls = listOf(
            "https://vidsrc.to/embed/movie/550",
            "https://vidsrc.me/embed/movie/550",
            "https://vidlink.pro/movie/550",
            "https://autoembed.co/movie/tmdb/550",
            "https://embed.su/embed/movie/550",
            "https://multiembed.mov/?video_id=550&tmdb=1"
        )
        val lines = urls.map { u ->
            val r = runBlocking { HeadlessStreamResolver.sniff(context, listOf(u)) }
            "LIVE $u -> ${r?.url ?: "NONE"}"
        }
        throw AssertionError("LIVE_REPORT_BEGIN\n" + lines.joinToString("\n") + "\nLIVE_REPORT_END")
    }
}
