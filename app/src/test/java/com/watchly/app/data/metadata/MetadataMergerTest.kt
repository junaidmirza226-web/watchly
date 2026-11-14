package com.watchly.app.data.metadata

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pure JVM unit test for the metadata merge rules, plus one MockK example.
 *
 * Copy to: app/src/test/java/com/watchly/app/data/metadata/
 * Run:     ./gradlew :app:testDebugUnitTest --tests "com.watchly.app.data.metadata.MetadataMergerTest"
 *
 * Dependencies: see gradle-snippet.kts in this folder (JUnit 5 + MockK).
 * Prefer plain fakes for your own classes (see the Robolectric example);
 * MockK earns its place for interfaces/classes you do not own or that are
 * painful to fake by hand — the provider-client example at the bottom.
 */
class MetadataMergerTest {

    private val primary = TitleMetadata(
        title = "The Matrix",
        overview = null,
        posterPath = "/primary-poster.jpg",
        backdropPath = "   ", // blank must behave like missing
        releaseYear = 1999,
        voteAverage = 0.0, // 0.0 means "unknown" from this provider
        genres = listOf("Sci-Fi", "Action"),
        runtimeMinutes = 0,
    )

    private val fallback = TitleMetadata(
        title = "Matrix",
        overview = "A hacker discovers reality is a simulation.",
        posterPath = "/fallback-poster.jpg",
        backdropPath = "/fallback-backdrop.jpg",
        releaseYear = 1999,
        voteAverage = 8.7,
        genres = listOf("sci-fi", "Cyberpunk"),
        runtimeMinutes = 136,
    )

    @Test
    fun `primary wins where it has a real value`() {
        val merged = MetadataMerger.merge(primary, fallback)
        assertEquals("The Matrix", merged.title)
        assertEquals("/primary-poster.jpg", merged.posterPath)
        assertEquals(1999, merged.releaseYear)
    }

    @Test
    fun `fallback fills in null and blank primary values`() {
        val merged = MetadataMerger.merge(primary, fallback)
        assertEquals("A hacker discovers reality is a simulation.", merged.overview)
        assertEquals("/fallback-backdrop.jpg", merged.backdropPath)
    }

    @Test
    fun `zero rating and runtime do not overwrite real fallback values`() {
        val merged = MetadataMerger.merge(primary, fallback)
        assertEquals(8.7, merged.voteAverage)
        assertEquals(136, merged.runtimeMinutes)
    }

    @Test
    fun `genres are unioned primary-first without case duplicates`() {
        val merged = MetadataMerger.merge(primary, fallback)
        // "sci-fi" from the fallback is a duplicate of the primary's "Sci-Fi".
        assertEquals(listOf("Sci-Fi", "Action", "Cyberpunk"), merged.genres)
    }

    @Test
    fun `merging two empty records stays empty`() {
        val merged = MetadataMerger.merge(TitleMetadata(), TitleMetadata())
        assertNull(merged.title)
        assertNull(merged.voteAverage)
        assertEquals(emptyList<String>(), merged.genres)
    }

    // ------------------------------------------------------------------
    // MockK example: merging what two provider clients return.
    // In the real app, replace ProviderClient with the actual interface.
    // ------------------------------------------------------------------

    interface ProviderClient {
        fun fetch(tmdbId: Int): TitleMetadata
    }

    @Test
    fun `merge of two mocked provider responses`() {
        val tmdb = mockk<ProviderClient>()
        val tvdb = mockk<ProviderClient>()
        every { tmdb.fetch(603) } returns primary
        every { tvdb.fetch(603) } returns fallback

        val merged = MetadataMerger.merge(tmdb.fetch(603), tvdb.fetch(603))

        assertEquals("The Matrix", merged.title)
        assertEquals(8.7, merged.voteAverage)
    }
}
