package com.watchly.app.ui.home

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.PlayabilityFilter
import com.watchly.app.data.model.Title
import com.watchly.app.data.remote.AniListApi
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.TvMazeApi
import com.watchly.app.data.remote.YouTubeApi
import com.watchly.app.data.settings.UiPreferencesRepository
import io.mockk.coEvery
import io.mockk.every
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase D Home curation (user points 1 + 3):
 *  - the hero leads with a PLAYABLE title, not an info-only one;
 *  - the shared playability filter applies to Home's rows;
 *  - a failed backbone row is marked failed (retry-able), not
 *    silently empty.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeCurationTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var uiPrefs: UiPreferencesRepository

    private val playableFilm = Title(
        archiveId = "some_film",
        name = "Playable Film",
        mediaType = MediaType.MOVIE,
        year = "1940",
        posterUrl = "https://archive.org/services/img/some_film"
    )
    private val infoShow = Title(
        tvmazeId = 169,
        name = "Info Show",
        mediaType = MediaType.TV,
        year = "2008",
        posterUrl = "https://static.tvmaze.com/show.jpg"
    )
    private val infoAnime = Title(
        anilistId = 5,
        name = "Info Anime",
        mediaType = MediaType.ANIME,
        year = "2024",
        posterUrl = "https://cdn.anilist.co/anime.jpg"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(
            UiPreferencesRepository.PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        ).edit().clear().commit()
        uiPrefs = UiPreferencesRepository(context)
        mockkObject(TvMazeApi)
        mockkObject(AniListApi)
        mockkObject(ArchiveApi)
        mockkObject(YouTubeApi)
        every { YouTubeApi.CHANNELS } returns emptyList()
        coEvery {
            ArchiveApi.fetchCollectionPage(any(), any(), any(), any())
        } returns emptyList()
        coEvery {
            ArchiveApi.fetchMoviesPage(any(), any(), any(), any())
        } returns listOf(playableFilm)
        coEvery { TvMazeApi.fetchTrendingShows(any()) } returns listOf(infoShow)
        coEvery { AniListApi.fetchTrendingAnime(any()) } returns listOf(infoAnime)
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `the hero leads with a playable title`() = runTest(dispatcher) {
        val viewModel = HomeViewModel(uiPrefs = uiPrefs)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Playable Film", state.hero?.name)
        assertEquals("Playable Film", state.heroCandidates.first().name)
    }

    @Test
    fun `the playability filter applies to home rows at read time`() =
        runTest(dispatcher) {
            val viewModel = HomeViewModel(uiPrefs = uiPrefs)
            advanceUntilIdle()

            viewModel.setPlayabilityFilter(PlayabilityFilter.PLAYABLE_ONLY)
            var state = viewModel.uiState.value
            assertEquals(listOf(playableFilm), state.visibleMovies)
            assertTrue(state.visibleShows.isEmpty())
            assertTrue(state.visibleAnime.isEmpty())
            // Persisted for the other screens.
            assertEquals(
                PlayabilityFilter.PLAYABLE_ONLY,
                uiPrefs.playabilityFilter()
            )

            viewModel.setPlayabilityFilter(PlayabilityFilter.INFO_ONLY)
            state = viewModel.uiState.value
            assertTrue(state.visibleMovies.isEmpty())
            assertEquals(listOf(infoShow), state.visibleShows)
        }

    @Test
    fun `a failed shows load marks the section failed, not just empty`() =
        runTest(dispatcher) {
            coEvery { TvMazeApi.fetchTrendingShows(any()) } throws
                java.io.IOException("offline")

            val viewModel = HomeViewModel(uiPrefs = uiPrefs)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.visibleShows.isEmpty())
            assertTrue("TV shows" in state.failedSections)
            assertTrue("Movies" !in state.failedSections)
        }
}
