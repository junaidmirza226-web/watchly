package com.watchly.app.ui.home

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.data.remote.AniListApi
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.TvMazeApi
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric test for the REAL [HomeViewModel] from the app (adapted from
 * the staged reference test, which targeted a different ViewModel API).
 *
 * The ViewModel talks to the keyless TVmaze / AniList / Internet Archive
 * API objects; all three are stubbed with MockK objects so no network is
 * touched. Still a JVM test — runs with testDebugUnitTest, no emulator —
 * but with a real Application, Looper, resources and manifest from
 * Robolectric.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeViewModelRobolectricTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main; swap in a test dispatcher
        // we can advance deterministically.
        Dispatchers.setMain(dispatcher)
        mockkObject(TvMazeApi)
        mockkObject(AniListApi)
        mockkObject(ArchiveApi)
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `starts loading, then shows movies, shows and anime rows`() = runTest(dispatcher) {
        coEvery { ArchiveApi.fetchMoviesPage(any(), any(), any(), any()) } returns listOf(
            Title(name = "Night of the Living Dead", mediaType = MediaType.MOVIE, year = "1968"),
        )
        coEvery { TvMazeApi.fetchTrendingShows() } returns listOf(
            Title(name = "Show A", mediaType = MediaType.TV, posterUrl = "https://x/a.jpg"),
        )
        coEvery { AniListApi.fetchTrendingAnime() } returns listOf(
            Title(name = "Anime B", mediaType = MediaType.ANIME),
        )

        val viewModel = HomeViewModel()
        // The init load is queued on the test dispatcher, not yet run:
        assertTrue(viewModel.uiState.value.isLoading)

        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        val state = viewModel.uiState.value
        assertEquals(false, state.isLoading)
        assertEquals(listOf("Night of the Living Dead"), state.movies.map { it.name })
        assertEquals(listOf("Show A"), state.shows.map { it.name })
        assertEquals(listOf("Anime B"), state.anime.map { it.name })
        assertNull(state.errorMessage)
        assertEquals("Show A", state.hero?.name)
    }

    @Test
    fun `all sources failing becomes a friendly full error`() = runTest(dispatcher) {
        coEvery { ArchiveApi.fetchMoviesPage(any(), any(), any(), any()) } throws IllegalStateException("offline")
        coEvery { TvMazeApi.fetchTrendingShows() } throws IllegalStateException("offline")
        coEvery { AniListApi.fetchTrendingAnime() } throws IllegalStateException("offline")

        val viewModel = HomeViewModel()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        val state = viewModel.uiState.value
        assertEquals(false, state.isLoading)
        assertTrue(state.isEmpty)
        assertEquals(
            "Couldn't load content. Check your connection and try again.",
            state.errorMessage,
        )
    }

    @Test
    fun `one source failing keeps the other rows and shows a partial error`() = runTest(dispatcher) {
        coEvery { ArchiveApi.fetchMoviesPage(any(), any(), any(), any()) } returns listOf(
            Title(name = "Night of the Living Dead", mediaType = MediaType.MOVIE),
        )
        coEvery { TvMazeApi.fetchTrendingShows() } returns listOf(
            Title(name = "Show A", mediaType = MediaType.TV),
        )
        coEvery { AniListApi.fetchTrendingAnime() } throws IllegalStateException("blocked")

        val viewModel = HomeViewModel()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        val state = viewModel.uiState.value
        assertEquals(listOf("Show A"), state.shows.map { it.name })
        assertTrue(state.anime.isEmpty())
        assertEquals("Couldn't load Anime. Showing what we could fetch.", state.errorMessage)

        viewModel.onErrorShown()
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `an application context is available under Robolectric`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("com.watchly.app", app.packageName)
    }
}
