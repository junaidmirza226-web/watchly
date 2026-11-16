package com.watchly.app.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the two-layer ad shield: network-level blocking + DOM-level cleanup. */
class AdShieldTest {

    @Test
    fun adNetworksAndPopunderPathsAreBlockedAtRequestLevel() {
        assertTrue(isBlockedAdUrl("https://pagead2.googlesyndication.com/tag/js"))
        assertTrue(isBlockedAdUrl("https://cdn.popads.net/some.js"))
        assertTrue(isBlockedAdUrl("https://evil.example/popunder/launch"))
        assertTrue(isBlockedAdUrl("https://evil.example/ads.js?cb=1"))
        assertTrue(isBlockedAdUrl("https://evil.example/banner/show"))
    }

    @Test
    fun playerAndCdnHostsAreNeverBlocked() {
        assertFalse(isBlockedAdUrl("https://vidlink.pro/movie/155?autoplay=true"))
        assertFalse(isBlockedAdUrl("https://vidsrc.to/embed/movie/tt0468569"))
        assertFalse(isBlockedAdUrl("https://multiembed.mov/?video_id=155&tmdb=1"))
        assertFalse(isBlockedAdUrl("https://cdn.example/master.m3u8"))
    }

    @Test
    fun snifferOnlyPromotesRealMediaUrls() {
        assertTrue(isSniffableDirectMedia("https://cdn.example/streams/master.m3u8"))
        assertTrue(isSniffableDirectMedia("https://cdn.example/video/movie.mp4"))
        assertFalse(isSniffableDirectMedia("https://cdn.example/seg1.ts"))
        assertFalse(isSniffableDirectMedia("https://cdn.example/preview.mp4"))
        assertFalse(isSniffableDirectMedia("https://pagead2.googlesyndication.com/ad.mp4"))
    }

    @Test
    fun adCleanerJsKillsBannersButProtectsThePlayer() {
        // Anti-popup core:
        assertTrue(AD_CLEAN_JS.contains("window.open"))
        // DOM cleanup + re-application as the page mutates:
        assertTrue(AD_CLEAN_JS.contains("killAds"))
        assertTrue(AD_CLEAN_JS.contains("MutationObserver"))
        assertTrue(AD_CLEAN_JS.contains("'display', 'none'"))
        // The player itself is never hidden:
        assertTrue(AD_CLEAN_JS.contains("el.querySelector('video')"))
        // Sticky top bars (the "ads at the top of the video") collapse:
        assertTrue(AD_CLEAN_JS.contains("position === 'fixed'"))
    }
}
