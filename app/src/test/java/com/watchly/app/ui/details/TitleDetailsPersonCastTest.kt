package com.watchly.app.ui.details

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.model.CastMember
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.data.model.WatchProviders
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.ExternalIdCache
import com.watchly.app.data.remote.TmdbApi
import com.watchly.app.data.remote.TvMazeApi
import com.watchly.app.data.settings.TmdbKeyRepository
import io.mockk.coEvery
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
import java.io.IOException

/**
 * Cycle 11 (§3.3): which cast rows may open a person page.
 *  - a TMDB movie's cast comes from /movie/:id/credits WITH the
 *    TMDB person ids the page opens from;
 *  - a TMDB show's cast now comes from /tv/:id/credits too (it
 *    previously had no TMDB cast at all), also with ids;
 *  - a TVmaze show keeps its own TVmaze cast, whose members carry
 *    NO TMDB id — their cards stay inert, no page is invented.
 * All network seams are mocked; no live calls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TitleDetailsPersonCastTest {

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

    private val tmdbMovieCast = listOf(
        CastMember(
            personName = "N. T. Rama Rao Jr.",
            characterName = "Komaram Bheem",
            tmdbPersonId = 94724
        )
    )
    private val tmdbTvCast = listOf(
        CastMember(
            personName = "Bryan Cranston",
            characterName = "Walter White",
            tmdbPersonId = 17419
        )
    )
    private val tvmazeCast = listOf(
        CastMember(
            personName = "Bryan Cranston",
            characterName = "Walter White"
            // No tmdbPersonId: TVmaze cast never carries one.
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
        // Fail-soft defaults for every seam the details load can
        // touch, so an unstubbed call can never leak a real network
        // call into a test (the cycle-10 MockK lesson). Tests
        // override the seams they exercise.
        coEvery {
            TmdbApi.fetchTrailerKey(any(), any(), any())
        } returns null
        coEvery {
            TmdbApi.fetchRecommendations(any(), any(), any(), any())
        } returns emptyList()
        coEvery {
            TmdbApi.fetchWatchProviders(any(), any(), any(), any())
        } returns WatchProviders("US", null, emptyList())
        coEvery {
            TmdbApi.fetchMovieDetails(any(), any())
        } throws IOException("offline")
        coEvery { TmdbApi.fetchMovieCast(any(), any()) } returns emptyList()
        coEvery { TmdbApi.fetchTvCast(any(), any()) } returns emptyList()
        coEvery { TmdbApi.fetchTvDetails(any(), any()) } throws IOException("offline")
        coEvery { TmdbApi.fetchTvSeasonEpisodes(any(), any(), any()) } returns emptyList()
        coEvery {
            TmdbApi.findByImdbId(any(), any(), any())
        } returns null
        coEvery { TmdbApi.searchMulti(any(), any(), any()) } returns emptyList()
        coEvery { TvMazeApi.fetchShowDetails(any()) } throws
            IOException("offline")
        coEvery { TvMazeApi.fetchShowEpisodes(any()) } throws
            IOException("offline")
        coEvery { TvMazeApi.fetchShowCast(any()) } throws
            IOException("offline")
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `tmdb movie cast carries person ids`() = runTest(dispatcher) {
        val movie = Title(
            tmdbId = 579974,
            name = "RRR",
            mediaType = MediaType.MOVIE,
            year = "2022"
        )
        coEvery {
            TmdbApi.fetchMovieDetails("user-key", 579974)
        } returns movie
        coEvery {
            TmdbApi.fetchMovieCast("user-key", 579974)
        } returns tmdbMovieCast

        val viewModel = TitleDetailsViewModel(
            title = movie,
            library = library,
            tmdbKeys = tmdbKeys,
            externalIdCache = idCache
        )
        advanceUntilIdle()

        val cast = viewModel.uiState.value.cast
        assertEquals(tmdbMovieCast, cast)
        assertEquals(94724, cast[0].tmdbPersonId)
    }

    @Test
    fun `tmdb tv cast comes from the tv credits endpoint with person ids`() =
        runTest(dispatcher) {
            val show = Title(
                tmdbId = 1396,
                name = "Breaking Bad",
                mediaType = MediaType.TV,
                year = "2008"
            )
            coEvery {
                TmdbApi.fetchTvCast("user-key", 1396)
            } returns tmdbTvCast

            val viewModel = TitleDetailsViewModel(
                title = show,
                library = library,
                tmdbKeys = tmdbKeys,
                externalIdCache = idCache
            )
            advanceUntilIdle()

            val cast = viewModel.uiState.value.cast
            assertEquals(tmdbTvCast, cast)
            assertEquals(17419, cast[0].tmdbPersonId)
        }

    @Test
    fun `tvmaze cast wins for a tvmaze show and stays inert`() =
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
                TmdbApi.fetchTvCast("user-key", 1396)
            } returns tmdbTvCast
            coEvery { TvMazeApi.fetchShowCast(169) } returns tvmazeCast

            val viewModel = TitleDetailsViewModel(
                title = show,
                library = library,
                tmdbKeys = tmdbKeys,
                externalIdCache = idCache
            )
            advanceUntilIdle()

            val cast = viewModel.uiState.value.cast
            // The source's own cast is shown…
            assertEquals(tvmazeCast, cast)
            // …and none of it can open a person page: TVmaze rows
            // carry no TMDB person id, so the cards stay inert.
            assertNull(cast[0].tmdbPersonId)
        }
}
