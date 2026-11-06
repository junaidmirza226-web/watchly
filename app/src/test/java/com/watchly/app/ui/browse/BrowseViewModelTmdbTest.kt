package com.watchly.app.ui.browse

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.browse.INDUSTRIES
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.TmdbApi
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
 * Movies tab source switching: without a TMDB key the tab serves the
 * public-domain Archive catalogue with the key prompt showing; with
 * a key, TMDB discover is the catalogue and industry chips filter by
 * original language.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BrowseViewModelTmdbTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var keyRepo: TmdbKeyRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(TmdbKeyRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        keyRepo = TmdbKeyRepository(context)
        mockkObject(TmdbApi)
        mockkObject(ArchiveApi)
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
    fun `without a key movies come from the archive and the prompt shows`() =
        runTest(dispatcher) {
            coEvery {
                ArchiveApi.fetchMoviesPage(any(), any(), any(), any())
            } returns listOf(
                Title(name = "Archive Film", mediaType = MediaType.MOVIE)
            )

            val viewModel = BrowseViewModel(BrowseKind.MOVIES, keyRepo)
            advanceUntilIdle(); idle()

            val state = viewModel.uiState.value
            assertFalse(state.tmdbActive)
            assertTrue(state.showTmdbPrompt)
            assertEquals(listOf("Archive Film"), state.titles.map { it.name })
            assertTrue(state.industries.isEmpty())
        }

    @Test
    fun `with a key movies come from tmdb discover`() = runTest(dispatcher) {
        keyRepo.setKey("user-key")
        coEvery {
            TmdbApi.discoverMovies(any(), any(), any(), any(), any())
        } returns listOf(
            Title(tmdbId = 1, name = "TMDB Film", mediaType = MediaType.MOVIE)
        )

        val viewModel = BrowseViewModel(BrowseKind.MOVIES, keyRepo)
        advanceUntilIdle(); idle()

        val state = viewModel.uiState.value
        assertTrue(state.tmdbActive)
        assertFalse(state.showTmdbPrompt)
        assertEquals(listOf("TMDB Film"), state.titles.map { it.name })
        assertEquals(INDUSTRIES, state.industries)
        assertTrue(state.genres.contains("Action"))
    }

    @Test
    fun `industry chip filters discover by original language`() = runTest(dispatcher) {
        keyRepo.setKey("user-key")
        coEvery {
            TmdbApi.discoverMovies(any(), isNull(), any(), any(), any())
        } returns listOf(
            Title(tmdbId = 1, name = "Any Film", mediaType = MediaType.MOVIE)
        )
        coEvery {
            TmdbApi.discoverMovies(any(), eq("hi"), any(), any(), any())
        } returns listOf(
            Title(tmdbId = 2, name = "Hindi Film", mediaType = MediaType.MOVIE)
        )

        val viewModel = BrowseViewModel(BrowseKind.MOVIES, keyRepo)
        advanceUntilIdle(); idle()
        assertEquals(listOf("Any Film"), viewModel.uiState.value.titles.map { it.name })

        viewModel.selectIndustry(INDUSTRIES.first { it.languageCode == "hi" })
        advanceUntilIdle(); idle()

        assertEquals(
            listOf("Hindi Film"),
            viewModel.uiState.value.titles.map { it.name }
        )
    }

    @Test
    fun `adding a key later switches the source on refresh`() = runTest(dispatcher) {
        coEvery {
            ArchiveApi.fetchMoviesPage(any(), any(), any(), any())
        } returns listOf(Title(name = "Archive Film", mediaType = MediaType.MOVIE))
        coEvery {
            TmdbApi.discoverMovies(any(), any(), any(), any(), any())
        } returns listOf(
            Title(tmdbId = 7, name = "TMDB Film", mediaType = MediaType.MOVIE)
        )

        val viewModel = BrowseViewModel(BrowseKind.MOVIES, keyRepo)
        advanceUntilIdle(); idle()
        assertFalse(viewModel.uiState.value.tmdbActive)

        keyRepo.setKey("late-key")
        viewModel.refreshKeyState()
        advanceUntilIdle(); idle()

        val state = viewModel.uiState.value
        assertTrue(state.tmdbActive)
        assertEquals(listOf("TMDB Film"), state.titles.map { it.name })
    }
}
