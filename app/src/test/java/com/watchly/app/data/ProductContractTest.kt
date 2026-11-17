/*
 * Watchly — product-decision contract tests (2026-11-17 pass).
 *
 * The user-facing rules pinned here:
 *  1. Downloads have NO artificial storage cap — the phone's free
 *     space is the only limit ("Why there's a storage cap thing in
 *     library?" — there isn't, any more).
 *  2. Playback starts from REAL multi-server players (VidLink,
 *     VidSrc, MultiEmbed …). The branded service entries (Netflix,
 *     Prime, Hotstar …) are last-resort fallbacks, never the first
 *     hop — a costume entry must not stall playback while real
 *     sources exist ("keeps loading without connecting and playing").
 */
package com.watchly.app.data

import com.watchly.app.data.downloads.DownloadSettings
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.data.remote.providers.OttServices
import com.watchly.app.data.remote.providers.StreamAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductContractTest {

    @Test
    fun downloadsHaveNoArtificialStorageCap() {
        assertEquals(Long.MAX_VALUE, DownloadSettings().storageCapBytes)
        assertEquals(Long.MAX_VALUE, DownloadSettings.DEFAULT_STORAGE_CAP_BYTES)
    }

    @Test
    fun realPlayersComeBeforeBrandedServiceEntries() {
        val sources = StreamAggregator.getInstantEmbedServers(
            title = Title(
                tmdbId = 550,
                name = "Fight Club",
                mediaType = MediaType.MOVIE
            )
        )
        assertTrue("instant server list must not be empty", sources.isNotEmpty())

        val brands = OttServices.ALL.map { it.displayName }.toSet()
        val firstBrandIndex = sources.indexOfFirst { it.providerName in brands }
        val lastRealIndex = sources.indexOfLast { it.providerName !in brands }

        assertTrue(
            "a branded entry must exist in the list at all",
            firstBrandIndex >= 0
        )
        assertTrue(
            "a real player entry must exist in the list at all",
            lastRealIndex >= 0
        )
        assertTrue(
            "every real player must come before every branded entry " +
                "(first brand at $firstBrandIndex, last real player at $lastRealIndex)",
            lastRealIndex < firstBrandIndex
        )
    }
}
