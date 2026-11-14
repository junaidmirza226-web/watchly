package com.watchly.app.ui.home

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.data.remote.AniListApi
import com.watchly.app.data.remote.ArchiveApi
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Home industry rows (user request 2026-11-06): with the user's TMDB
 * key saved, Home shows one discover row per industry language;
 * without a key the rows stay hidden and Home is unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeTmdbIndustriesTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var keyRepo: TmdbKeyRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(TmdbKeyRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        keyRepo = TmdbKeyRepository(context)
        mockkObject(TvMazeApi)
        mockkObject(AniListApi)
        mockkObject(ArchiveApi)
        mockkObject(TmdbApi)
        mockkObject(com.watchly.app.data.remote.YouTubeApi)
        io.mockk.every {
            com.watchly.app.data.remote.YouTubeApi.CHANNELS
        } returns emptyList()
        coEvery {
            ArchiveApi.fetchCollectionPage(any(), any(), any(), any())
        } returns emptyList()

        coEvery { ArchiveApi.fetchMoviesPage(any(), any(), any(), any()) } returns emptyList()
        coEvery { TvMazeApi.fetchTrendingShows() } returns emptyList()
        coEvery { AniListApi.fetchTrendingAnime() } returns emptyList()
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `with a key home shows one row per industry that has titles`() =
        runTest(dispatcher) {
            keyRepo.setKey("user-key")
            // Only Hindi and English return titles; the other six
            // industries come back empty and must not appear as rows.
            coEvery {
                TmdbApi.discoverMovies(any(), eq("hi"), any(), any(), any())
            } returns listOf(
                Title(tmdbId = 1, name = "Hindi Hit", mediaType = MediaType.MOVIE)
            )
            coEvery {
                TmdbApi.discoverMovies(any(), eq("en"), any(), any(), any())
            } returns listOf(
                Title(tmdbId = 2, name = "English Hit", mediaType = MediaType.MOVIE)
            )
            // The remaining six industries return nothing.
            listOf("te", "ta", "ml", "pa", "bn", "ko").forEach { code ->
                coEvery {
                    TmdbApi.discoverMovies(any(), eq(code), any(), any(), any())
                } returns emptyList()
            }

            val viewModel = HomeViewModel(keyRepo)
            advanceUntilIdle(); idle()

            val state = viewModel.uiState.value
            assertTrue(state.hasTmdbKey)
            assertEquals(
                listOf("Bollywood", "Hollywood"),
                state.industryRows.map { it.industry.label }
            )
            assertEquals("Hindi Hit", state.industryRows[0].titles[0].name)
        }

    @Test
    fun `without a key industry rows stay hidden`() = runTest(dispatcher) {
        val viewModel = HomeViewModel(keyRepo)
        advanceUntilIdle(); idle()

        val state = viewModel.uiState.value
        assertFalse(state.hasTmdbKey)
        assertTrue(state.industryRows.isEmpty())
    }

    @Test
    fun `a failing industry source never breaks home`() = runTest(dispatcher) {
        keyRepo.setKey("user-key")
        coEvery {
            TmdbApi.discoverMovies(any(), any(), any(), any(), any())
        } throws IllegalStateException("tmdb down")

        val viewModel = HomeViewModel(keyRepo)
        advanceUntilIdle(); idle()

        val state = viewModel.uiState.value
        assertTrue(state.hasTmdbKey)
        assertTrue(state.industryRows.isEmpty())
        // The keyless rows failing/succeeding is covered elsewhere;
        // the point here: no crash and no TMDB error leaks into the
        // home error banner.
        assertTrue(state.errorMessage == null ||
            !state.errorMessage!!.contains("TMDB"))
    }
}
