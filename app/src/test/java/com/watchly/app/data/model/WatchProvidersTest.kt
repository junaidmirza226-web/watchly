package com.watchly.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the where-to-watch merge + region rules
 * (data/model/WatchProviders.kt). No Android classes involved.
 */
class WatchProvidersTest {

    private fun provider(name: String, category: WatchCategory) =
        WatchProvider(name = name, logoUrl = null, category = category)

    @Test
    fun `service in several lists appears once under its best category`() {
        val merged = combineWatchProviders(
            stream = listOf(provider("Netflix", WatchCategory.STREAM)),
            rent = listOf(provider("Apple TV", WatchCategory.RENT)),
            buy = listOf(
                provider("Apple TV", WatchCategory.BUY),
                provider("Google Play", WatchCategory.BUY)
            )
        )
        assertEquals(3, merged.size)
        assertEquals(
            WatchCategory.RENT,
            merged.first { it.name == "Apple TV" }.category
        )
    }

    @Test
    fun `dedupe matches names case-insensitively`() {
        val merged = combineWatchProviders(
            stream = listOf(provider("Netflix", WatchCategory.STREAM)),
            rent = listOf(provider("netflix", WatchCategory.RENT)),
            buy = emptyList()
        )
        assertEquals(1, merged.size)
        assertEquals(WatchCategory.STREAM, merged[0].category)
    }

    @Test
    fun `order is stream then rent then buy, names A to Z inside each`() {
        val merged = combineWatchProviders(
            stream = listOf(
                provider("Zee5", WatchCategory.STREAM),
                provider("Netflix", WatchCategory.STREAM)
            ),
            rent = listOf(provider("Apple TV", WatchCategory.RENT)),
            buy = listOf(provider("Amazon Video", WatchCategory.BUY))
        )
        assertEquals(
            listOf("Netflix", "Zee5", "Apple TV", "Amazon Video"),
            merged.map { it.name }
        )
    }

    @Test
    fun `empty lists merge to empty`() {
        assertTrue(
            combineWatchProviders(emptyList(), emptyList(), emptyList()).isEmpty()
        )
    }

    @Test
    fun `watch providers helpers report emptiness and filter by category`() {
        val watch = WatchProviders(
            region = "PK",
            watchPageUrl = "https://www.themoviedb.org/movie/1/watch?locale=PK",
            providers = listOf(
                provider("Netflix", WatchCategory.STREAM),
                provider("Apple TV", WatchCategory.RENT)
            )
        )
        assertTrue(!watch.isEmpty)
        assertEquals(
            listOf("Netflix"),
            watch.providersIn(WatchCategory.STREAM).map { it.name }
        )
        assertTrue(watch.providersIn(WatchCategory.BUY).isEmpty())
        assertTrue(
            WatchProviders("PK", null, emptyList()).isEmpty
        )
    }

    @Test
    fun `preferred region accepts a two-letter country in any case`() {
        assertEquals("PK", preferredWatchRegion("pk"))
        assertEquals("US", preferredWatchRegion("US"))
        assertEquals("GB", preferredWatchRegion(" gb "))
    }

    @Test
    fun `preferred region falls back to US for unusable values`() {
        assertEquals("US", preferredWatchRegion(null))
        assertEquals("US", preferredWatchRegion(""))
        assertEquals("US", preferredWatchRegion("USA"))
        assertEquals("US", preferredWatchRegion("P1"))
    }
}
