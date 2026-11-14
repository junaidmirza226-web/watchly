package com.watchly.app.data.search

import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.data.remote.AniListApi
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.TmdbApi
import com.watchly.app.data.remote.TvMazeApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric test for the REAL [UnifiedSearch]. All four providers
 * are stubbed with MockK so no network is touched. Proves TMDB joins
 * the search only when a key is given, merges with the Archive record
 * for the same film, and that a TMDB failure only narrows results.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnifiedSearchTest {

    private val tmdbFilm = Title(
        tmdbId = 12345,
        name = "Some Film",
        mediaType = MediaType.MOVIE,
        year = "2020",
        rating = 7.5
    )
    private val archiveFilm = Title(
        archiveId = "somefilm_2020",
        name = "Some Film",
        mediaType = MediaType.MOVIE,
        year = "2020"
    )
    private val show = Title(
        tvmazeId = 169,
        name = "Breaking Bad",
        mediaType = MediaType.TV,
        year = "2008"
    )

    @Before
    fun setUp() {
        mockkObject(TmdbApi)
        mockkObject(TvMazeApi)
        mockkObject(AniListApi)
        mockkObject(ArchiveApi)
        coEvery { TvMazeApi.searchShows(any()) } returns emptyList()
        coEvery { AniListApi.searchAnime(any()) } returns emptyList()
        coEvery { ArchiveApi.searchMovies(any()) } returns emptyList()
        coEvery { ArchiveApi.searchCollection(any(), any(), any()) } returns emptyList()
        coEvery { TmdbApi.searchMulti(any(), any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `with a key tmdb results are searched and merged with archive`() = runTest {
        coEvery { TmdbApi.searchMulti("test-key", "some film") } returns listOf(tmdbFilm)
        coEvery { ArchiveApi.searchMovies("some film") } returns listOf(archiveFilm)

        val result = UnifiedSearch.search("some film", "test-key")

        // Same film from TMDB and the Archive collapses into one row
        // that keeps the TMDB id (details enrichment) and the Archive
        // id (playable file), so the merged row stays playable.
        assertEquals(1, result.groups.movies.size)
        val merged = result.groups.movies[0]
        assertEquals(12345, merged.tmdbId)
        assertEquals("somefilm_2020", merged.archiveId)
        assertEquals(7.5, merged.rating!!, 0.01)
        assertTrue(result.failedProviders.isEmpty())
        coVerify(exactly = 1) { TmdbApi.searchMulti("test-key", "some film") }
    }

    @Test
    fun `without a key tmdb is never asked and other results still show`() = runTest {
        coEvery { TvMazeApi.searchShows("breaking") } returns listOf(show)

        val result = UnifiedSearch.search("breaking")

        coVerify(exactly = 0) { TmdbApi.searchMulti(any(), any()) }
        assertEquals(listOf("Breaking Bad"), result.groups.shows.map { it.name })
        assertTrue(result.failedProviders.isEmpty())
    }

    @Test
    fun `a failing tmdb only narrows the results`() = runTest {
        coEvery { TmdbApi.searchMulti(any(), any()) } throws
            IllegalStateException("TMDB HTTP 500")
        coEvery { TvMazeApi.searchShows("breaking") } returns listOf(show)

        val result = UnifiedSearch.search("breaking", "test-key")

        assertEquals(listOf("TMDB"), result.failedProviders)
        assertEquals(listOf("Breaking Bad"), result.groups.shows.map { it.name })
    }

    @Test
    fun `archive classic tv results land in the shows group`() = runTest {
        // Cycle 4: the Archive is also searched in its Classic TV
        // collection; those items are TV-typed and playable.
        val classic = Title(
            archiveId = "get-smart",
            name = "Get Smart",
            mediaType = MediaType.TV,
            year = "1965"
        )
        coEvery {
            ArchiveApi.searchCollection("get smart", ArchiveApi.CLASSIC_TV, any())
        } returns listOf(classic)

        val result = UnifiedSearch.search("get smart")

        assertEquals(listOf("Get Smart"), result.groups.shows.map { it.name })
        assertEquals("get-smart", result.groups.shows[0].archiveId)
        assertTrue(result.failedProviders.isEmpty())
    }

    @Test
    fun `blank query searches nothing`() = runTest {
        val result = UnifiedSearch.search("   ", "test-key")

        assertTrue(result.groups.isEmpty)
        coVerify(exactly = 0) { TmdbApi.searchMulti(any(), any()) }
        coVerify(exactly = 0) { TvMazeApi.searchShows(any()) }
    }

    @Test
    fun `every provider failing means the search never happened`() = runTest {
        // Cycle 13: all-failure must be distinguishable from a
        // genuine no-results answer, so the UI can show a
        // couldn't-search state with Retry instead of "No results".
        coEvery { TvMazeApi.searchShows(any()) } throws
            IllegalStateException("offline")
        coEvery { AniListApi.searchAnime(any()) } throws
            IllegalStateException("offline")
        coEvery { ArchiveApi.searchMovies(any()) } throws
            IllegalStateException("offline")
        coEvery { ArchiveApi.searchCollection(any(), any(), any()) } throws
            IllegalStateException("offline")

        val result = UnifiedSearch.search("breaking")

        assertTrue(result.groups.isEmpty)
        assertTrue(result.allProvidersFailed)
        assertTrue(result.failedProviders.contains("TVmaze"))
        assertTrue(result.failedProviders.contains("AniList"))
    }

    @Test
    fun `one answering provider means not all failed even when it finds nothing`() =
        runTest {
            coEvery { TvMazeApi.searchShows(any()) } throws
                IllegalStateException("offline")
            coEvery { AniListApi.searchAnime(any()) } throws
                IllegalStateException("offline")
            // The Archive answers (with an empty list): the search
            // DID happen, so the empty result is a real answer.
            val result = UnifiedSearch.search("breaking")

            assertTrue(result.groups.isEmpty)
            assertTrue(!result.allProvidersFailed)
            assertEquals(
                listOf("TVmaze", "AniList"),
                result.failedProviders
            )
        }
}
