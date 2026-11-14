package com.watchly.app.ui.browse

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.PlayabilityFilter
import com.watchly.app.data.model.Title
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.TvMazeApi
import com.watchly.app.data.settings.UiPreferencesRepository
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
import org.robolectric.annotation.Config

/**
 * Cycle 5 (Phase B): Browse genre chips — the choice is persisted
 * per tab, restored by the next ViewModel, and composes with the
 * playability filter (a title must pass BOTH to stay visible).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BrowseGenreFilterTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var uiPrefs: UiPreferencesRepository

    private val drama = Title(
        tvmazeId = 1, name = "Drama Show",
        mediaType = MediaType.TV, year = "2020", rating = 8.0,
        genres = listOf("Drama")
    )
    private val comedy = Title(
        tvmazeId = 2, name = "Comedy Show",
        mediaType = MediaType.TV, year = "2021", rating = 7.0,
        genres = listOf("Comedy")
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
        coEvery { TvMazeApi.fetchShowsPage(any()) } returns listOf(drama, comedy)
        // Cycle 7: the Shows tab also merges the Archive's
        // classic-TV collection; stubbed empty here — these tests
        // are about TVmaze genre behaviour.
        mockkObject(ArchiveApi)
        coEvery {
            ArchiveApi.fetchCollectionPage(any(), any(), any())
        } returns emptyList()
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `genre choice persists per tab and is restored by a fresh view model`() =
        runTest(dispatcher) {
            val viewModel = BrowseViewModel(kind = BrowseKind.SHOWS, uiPrefs = uiPrefs)
            advanceUntilIdle()
            assertEquals(2, viewModel.uiState.value.visibleTitles.size)

            viewModel.selectGenre("Drama")
            advanceUntilIdle()
            assertEquals(
                listOf("Drama Show"),
                viewModel.uiState.value.visibleTitles.map { it.name }
            )
            assertEquals("Drama", uiPrefs.genreFor("shows"))
            // Another tab's saved genre is untouched.
            assertNull(uiPrefs.genreFor("movies"))

            // The next ViewModel for the tab starts on the saved genre.
            val fresh = BrowseViewModel(kind = BrowseKind.SHOWS, uiPrefs = uiPrefs)
            assertEquals("Drama", fresh.uiState.value.selectedGenre)
            advanceUntilIdle()
            assertEquals(
                listOf("Drama Show"),
                fresh.uiState.value.visibleTitles.map { it.name }
            )
        }

    @Test
    fun `genre and playability filters compose`() = runTest(dispatcher) {
        val viewModel = BrowseViewModel(kind = BrowseKind.SHOWS, uiPrefs = uiPrefs)
        advanceUntilIdle()

        // Both shows are catalogue-only: PLAYABLE_ONLY hides them
        // whatever the genre says.
        viewModel.selectGenre("Drama")
        viewModel.setPlayabilityFilter(PlayabilityFilter.PLAYABLE_ONLY)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.visibleTitles.isEmpty())
        assertTrue(viewModel.uiState.value.isEmpty)

        // INFO_ONLY + Drama leaves exactly the drama.
        viewModel.setPlayabilityFilter(PlayabilityFilter.INFO_ONLY)
        advanceUntilIdle()
        assertEquals(
            listOf("Drama Show"),
            viewModel.uiState.value.visibleTitles.map { it.name }
        )

        // Clearing both from the empty state restores the full grid.
        viewModel.clearAllFilters()
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.visibleTitles.size)
        assertNull(uiPrefs.genreFor("shows"))
        assertEquals(PlayabilityFilter.ALL, uiPrefs.playabilityFilter())
    }
}
