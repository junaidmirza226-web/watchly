package com.watchly.app.ui.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** TEMPORARY diagnostic: a FAILING test here = the live source gave NO captured media. */
@RunWith(AndroidJUnit4::class)
class LiveSourceReportTest {
    private fun probe(url: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val r = runBlocking { HeadlessStreamResolver.sniff(context, listOf(url)) }
        assertNotNull("NO_CAPTURE $url", r)
    }

    @Test fun a_vidsrcTo() = probe("https://vidsrc.to/embed/movie/550")
    @Test fun b_vidsrcMe() = probe("https://vidsrc.me/embed/movie/550")
    @Test fun c_vidlink() = probe("https://vidlink.pro/movie/550")
    @Test fun d_autoembed() = probe("https://autoembed.co/movie/tmdb/550")
    @Test fun e_embedSu() = probe("https://embed.su/embed/movie/550")
    @Test fun f_multiembed() = probe("https://multiembed.mov/?video_id=550&tmdb=1")
}
