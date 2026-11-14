package com.watchly.app.ui.browse

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.model.MediaType
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cycle 7 (user report 2026-11-07): Browse must distinguish
 * "still loading", "load FAILED" and "loaded, truly empty".
 * Before this cycle a failed load could surface as the plain
 * empty state — Archive/AniList parsers swallowed unparseable
 * bodies into empty lists, and the Shows tab published a
 * post-filter slice that hid the difference entirely.
 *
 * These tests pin the ViewModel-level truth the screen renders
 * from: errorMessage set + nothing loaded = the error state with
 * Retry; errorMessage null + nothing loaded = genuinely empty.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BrowseLoadStatesTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var uiPrefs: UiPreferencesRepository

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
        mockkObject(ArchiveApi)
        // The parser stays real so fixtures parse genuinely.
        every { ArchiveApi.parseSearchResponse(any(), any()) } answers {
            callOriginal()
        }
        mockkObject(AniListApi)
        mockkObject(YouTubeApi)
        // Anime extras are an enhancement, never the tab's error:
        // channels resolve, fetches return nothing.
        every { YouTubeApi.CHANNELS } returns emptyList()
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `shows tab reports failure when both sources fail`() = runTest(dispatcher) {
        coEvery { TvMazeApi.fetchShowsPage(any()) } throws
            RuntimeException("timeout")
        coEvery {
            ArchiveApi.fetchCollectionPage(any(), any(), any())
        } throws RuntimeException("timeout")

        val viewModel = BrowseViewModel(
            kind = BrowseKind.SHOWS,
            uiPrefs = uiPrefs
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(!state.isLoading)
        assertTrue(state.titles.isEmpty())
        assertNotNull(
            "a failed load must surface an error (Retry state), " +
                "not read as an empty catalogue",
            state.errorMessage
        )
    }

    @Test
    fun `shows tab is genuinely empty when both sources return nothing`() =
        runTest(dispatcher) {
            coEvery { TvMazeApi.fetchShowsPage(any()) } returns emptyList()
            coEvery {
                ArchiveApi.fetchCollectionPage(any(), any(), any())
            } returns emptyList()

            val viewModel = BrowseViewModel(
                kind = BrowseKind.SHOWS,
                uiPrefs = uiPrefs
            )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.titles.isEmpty())
            assertNull(state.errorMessage)
            assertTrue(state.isEmpty)
        }

    @Test
    fun `shows tab survives one source failing`() = runTest(dispatcher) {
        // TVmaze down, Archive up: the tab still fills (with the
        // playable source, no less) and notes the partial failure.
        coEvery { TvMazeApi.fetchShowsPage(any()) } throws
            RuntimeException("timeout")
        val archiveShows = ArchiveApi.parseSearchResponse(
            """
            {"response": {"docs": [
              {"identifier": "get-smart", "title": "Get Smart", "year": 1965}
            ]}}
            """.trimIndent(),
            MediaType.TV
        )
        coEvery {
            ArchiveApi.fetchCollectionPage(any(), any(), any())
        } returns archiveShows

        val viewModel = BrowseViewModel(
            kind = BrowseKind.SHOWS,
            uiPrefs = uiPrefs
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.titles.isNotEmpty())
        assertNotNull(state.errorMessage) // partial-failure note
    }

    @Test
    fun `anime tab reports failure when anilist fails`() = runTest(dispatcher) {
        coEvery {
            AniListApi.fetchAnimePage(any(), any(), any(), any())
        } throws RuntimeException("timeout")

        val viewModel = BrowseViewModel(
            kind = BrowseKind.ANIME,
            uiPrefs = uiPrefs
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.titles.isEmpty())
        assertNotNull(state.errorMessage)
    }

    @Test
    fun `movies tab without a key reports failure when archive fails`() =
        runTest(dispatcher) {
            coEvery {
                ArchiveApi.fetchMoviesPage(any(), any(), any(), any())
            } throws RuntimeException("timeout")

            val viewModel = BrowseViewModel(
                kind = BrowseKind.MOVIES,
                uiPrefs = uiPrefs
            )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.titles.isEmpty())
            assertNotNull(state.errorMessage)
        }
}
