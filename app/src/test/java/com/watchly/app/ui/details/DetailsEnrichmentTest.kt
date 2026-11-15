package com.watchly.app.ui.details
import com.watchly.app.data.settings.TestKeys

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.Title
import com.watchly.app.data.model.WatchCategory
import com.watchly.app.data.model.WatchProvider
import com.watchly.app.data.model.WatchProviders
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.ExternalIdCache
import com.watchly.app.data.remote.TmdbApi
import com.watchly.app.data.remote.TvMazeApi
import com.watchly.app.data.settings.TmdbKeyRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase D details enrichment:
 *  - a TVmaze title with an IMDb id resolves to TMDB (find route,
 *    cached) so it can show Where to Watch (user point 7b);
 *  - an Archive title with no ids gets a TMDB poster by name+year
 *    matching, WITHOUT losing its Archive id (user points 2 + 7a).
 * All network seams are mocked; no live calls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DetailsEnrichmentTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var library: WatchlistRepository
    private lateinit var tmdbKeys: TmdbKeyRepository

    private class FakeExternalIdCache : ExternalIdCache {
        val map = HashMap<String, Int>()
        override fun tmdbIdFor(imdbId: String): Int? = map[imdbId]
        override fun putTmdbId(imdbId: String, tmdbId: Int) {
            map[imdbId] = tmdbId
        }
    }

    private lateinit var idCache: FakeExternalIdCache

    private val providers = WatchProviders(
        region = "US",
        watchPageUrl = "https://www.themoviedb.org/movie/1/watch",
        providers = listOf(
            WatchProvider("ExampleFlix", null, WatchCategory.STREAM)
        )
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(
            WatchlistRepository.PREFS_NAME, Context.MODE_PRIVATE
        ).edit().clear().commit()
        context.getSharedPreferences(
            TmdbKeyRepository.PREFS_NAME, Context.MODE_PRIVATE
        ).edit().clear().commit()
        library = WatchlistRepository(context)
        tmdbKeys = TmdbKeyRepository(context)
        tmdbKeys.setKey("user-key")
        idCache = FakeExternalIdCache()
        mockkObject(TmdbApi)
        mockkObject(TvMazeApi)
        mockkObject(ArchiveApi)
        TestKeys.clearEmbeddedDefaults()
        // Cycle 10: details also asks TMDB for a trailer + "More like
        // this". Fail soft (none) so an unstubbed seam can never leak a
        // real network call into these tests.
        coEvery {
            TmdbApi.fetchTrailerKey(any(), any(), any())
        } returns null
        // Cycle 11: details now also asks TMDB for TV cast
        // (/tv/:id/credits) when a show resolves to a TMDB id.
        // Same fail-soft rule — an unstubbed call here leaks a real
        // network call that escapes the test's virtual time and
        // starves the state update these tests assert on.
        coEvery { TmdbApi.fetchTvCast(any(), any()) } returns emptyList()
        coEvery { TmdbApi.fetchMovieCast(any(), any()) } returns emptyList()
        coEvery { TmdbApi.fetchTvDetails(any(), any()) } throws java.io.IOException("offline")
        coEvery { TmdbApi.fetchTvSeasonEpisodes(any(), any(), any()) } returns emptyList()
        coEvery {
            TmdbApi.fetchRecommendations(any(), any(), any(), any())
        } returns emptyList()
        // TVmaze seams fail soft (offline) unless a test says otherwise.
        coEvery { TvMazeApi.fetchShowDetails(any()) } throws
            java.io.IOException("offline")
        coEvery { TvMazeApi.fetchShowEpisodes(any()) } throws
            java.io.IOException("offline")
        coEvery { TvMazeApi.fetchShowCast(any()) } throws
            java.io.IOException("offline")
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `tvmaze title resolves via imdb find and gains watch providers`() =
        runTest(dispatcher) {
            val show = Title(
                tvmazeId = 169,
                imdbId = "tt0903747",
                name = "Breaking Bad",
                mediaType = MediaType.TV,
                year = "2008"
            )
            coEvery {
                TmdbApi.findByImdbId("user-key", "tt0903747", MediaType.TV)
            } returns 1396
            coEvery {
                TmdbApi.fetchWatchProviders("user-key", MediaType.TV, 1396, any())
            } returns providers

            val viewModel = TitleDetailsViewModel(
                title = show,
                library = library,
                tmdbKeys = tmdbKeys,
                externalIdCache = idCache
            )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(providers, state.watchProviders)
            // The title keeps its own identity…
            assertEquals(169, state.title.tvmazeId)
            // …and the resolution is cached for next time.
            assertEquals(1396, idCache.map["tt0903747"])
        }

    @Test
    fun `a cached resolution skips the find call entirely`() =
        runTest(dispatcher) {
            val show = Title(
                tvmazeId = 169,
                imdbId = "tt0903747",
                name = "Breaking Bad",
                mediaType = MediaType.TV,
                year = "2008"
            )
            idCache.map["tt0903747"] = 1396
            coEvery {
                TmdbApi.fetchWatchProviders("user-key", MediaType.TV, 1396, any())
            } returns providers

            val viewModel = TitleDetailsViewModel(
                title = show,
                library = library,
                tmdbKeys = tmdbKeys,
                externalIdCache = idCache
            )
            advanceUntilIdle()

            assertEquals(providers, viewModel.uiState.value.watchProviders)
            coVerify(exactly = 0) {
                TmdbApi.findByImdbId(any(), any(), any())
            }
        }

    @Test
    fun `archive film gains a tmdb poster by name and year, keeping its archive id`() =
        runTest(dispatcher) {
            val film = Title(
                archiveId = "nightofthelivingdead_201910",
                name = "Night of the Living Dead",
                mediaType = MediaType.MOVIE,
                year = "1968"
            )
            val wrongYear = Title(
                tmdbId = 999,
                name = "Night of the Living Dead",
                mediaType = MediaType.MOVIE,
                year = "1990",
                posterUrl = "https://image.tmdb.org/t/p/w500/wrong.jpg"
            )
            val rightOne = Title(
                tmdbId = 10331,
                name = "Night of the Living Dead",
                mediaType = MediaType.MOVIE,
                year = "1968",
                posterUrl = "https://image.tmdb.org/t/p/w500/right.jpg",
                rating = 7.6
            )
            coEvery {
                TmdbApi.searchMulti("user-key", "Night of the Living Dead")
            } returns listOf(wrongYear, rightOne)
            coEvery {
                TmdbApi.fetchMovieDetails("user-key", 10331)
            } throws java.io.IOException("offline")
            coEvery {
                TmdbApi.fetchMovieCast("user-key", 10331)
            } returns emptyList()
            coEvery {
                TmdbApi.fetchWatchProviders("user-key", MediaType.MOVIE, 10331, any())
            } returns providers
            coEvery { ArchiveApi.fetchPlayableFiles(any()) } returns listOf(
                PlayableFile(
                    url = "https://archive.org/download/x/film.mp4",
                    fileName = "film.mp4",
                    format = "MPEG4",
                    sizeBytes = 10
                )
            )

            val viewModel = TitleDetailsViewModel(
                title = film,
                library = library,
                tmdbKeys = tmdbKeys,
                externalIdCache = idCache
            )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(
                "https://image.tmdb.org/t/p/w500/right.jpg",
                state.title.posterUrl
            )
            assertEquals(10331, state.title.tmdbId)
            // Identity + playability survive enrichment.
            assertEquals("nightofthelivingdead_201910", state.title.archiveId)
            assertEquals(providers, state.watchProviders)
        }

    @Test
    fun `no tmdb key means no resolution calls and the title is untouched`() =
        runTest(dispatcher) {
            tmdbKeys.clearKey()
            val film = Title(
                archiveId = "some_film",
                name = "Some Film",
                mediaType = MediaType.MOVIE,
                year = "1950"
            )
            coEvery { ArchiveApi.fetchPlayableFiles(any()) } returns emptyList()

            val viewModel = TitleDetailsViewModel(
                title = film,
                library = library,
                tmdbKeys = tmdbKeys,
                externalIdCache = idCache
            )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertNull(state.title.posterUrl)
            assertNull(state.watchProviders)
            coVerify(exactly = 0) { TmdbApi.searchMulti(any(), any()) }
            coVerify(exactly = 0) { TmdbApi.findByImdbId(any(), any(), any()) }
        }

    @Test
    fun `name year matching is strict about type name and year`() {
        val wanted = Title(
            name = "His New Job",
            mediaType = MediaType.MOVIE,
            year = "1915"
        )
        val tvSameName = Title(
            tmdbId = 1, name = "His New Job",
            mediaType = MediaType.TV, year = "1915"
        )
        val otherYear = Title(
            tmdbId = 2, name = "His New Job",
            mediaType = MediaType.MOVIE, year = "1931"
        )
        val punctuated = Title(
            tmdbId = 3, name = "His New Job!",
            mediaType = MediaType.MOVIE, year = "1915"
        )
        assertEquals(
            3,
            pickNameYearMatch(listOf(tvSameName, otherYear, punctuated), wanted)
                ?.tmdbId
        )
        assertNull(pickNameYearMatch(listOf(tvSameName, otherYear), wanted))
    }

    @Test
    fun `merge fills gaps and never overwrites the base title`() {
        val base = Title(
            archiveId = "a1",
            name = "Base Name",
            mediaType = MediaType.MOVIE,
            posterUrl = "https://example.org/own.jpg"
        )
        val extra = Title(
            tmdbId = 7,
            imdbId = "tt0000007",
            name = "Other Name",
            mediaType = MediaType.MOVIE,
            posterUrl = "https://example.org/tmdb.jpg",
            year = "1930",
            rating = 8.0
        )
        val merged = mergeFillMissing(base, extra)
        assertEquals("a1", merged.archiveId)
        assertEquals("Base Name", merged.name)
        assertEquals("https://example.org/own.jpg", merged.posterUrl)
        assertEquals(7, merged.tmdbId)
        assertEquals("tt0000007", merged.imdbId)
        assertEquals("1930", merged.year)
        assertEquals(8.0, merged.rating)
    }
}
