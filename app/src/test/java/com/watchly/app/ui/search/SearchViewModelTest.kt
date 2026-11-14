package com.watchly.app.ui.search

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.data.remote.AniListApi
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.TvMazeApi
import com.watchly.app.data.search.SearchGroups
import com.watchly.app.data.search.SearchHistoryRepository
import com.watchly.app.data.search.UnifiedSearch
import com.watchly.app.data.search.UnifiedSearchResult
import com.watchly.app.data.settings.TmdbKeyRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
 * Robolectric test for the REAL [SearchViewModel]. The unified search
 * and the trending-source calls are stubbed with MockK so no network
 * is touched. Debounce timing is driven by the test dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(UnifiedSearch)
        mockkObject(TvMazeApi)
        mockkObject(AniListApi)
        mockkObject(ArchiveApi)
        coEvery { TvMazeApi.fetchTrendingShows(any()) } returns emptyList()
        coEvery { AniListApi.fetchTrendingAnime(any()) } returns emptyList()
        coEvery { ArchiveApi.fetchMoviesPage(any(), any(), any(), any()) } returns emptyList()
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
    fun `typing waits for the 300ms pause before searching`() = runTest(dispatcher) {
        coEvery { UnifiedSearch.search(any()) } returns UnifiedSearchResult(
            SearchGroups(shows = listOf(Title(name = "Breaking Bad", mediaType = MediaType.TV)))
        )

        val viewModel = SearchViewModel()
        advanceUntilIdle(); idle()

        viewModel.onQueryChange("breaking")
        advanceTimeBy(299); idle()
        assertFalse(viewModel.uiState.value.hasSearched)

        advanceTimeBy(1)
        advanceUntilIdle(); idle()

        val state = viewModel.uiState.value
        assertTrue(state.hasSearched)
        assertEquals(listOf("Breaking Bad"), state.groups.shows.map { it.name })
        assertEquals(listOf("breaking"), state.recentSearches)
    }

    @Test
    fun `blank query clears results and does not search`() = runTest(dispatcher) {
        coEvery { UnifiedSearch.search(any()) } returns UnifiedSearchResult(
            SearchGroups(shows = listOf(Title(name = "Show", mediaType = MediaType.TV)))
        )

        val viewModel = SearchViewModel()
        viewModel.onQueryChange("show")
        advanceTimeBy(500)
        advanceUntilIdle(); idle()
        assertTrue(viewModel.uiState.value.hasSearched)

        viewModel.clearQuery()
        advanceUntilIdle(); idle()

        val state = viewModel.uiState.value
        assertEquals("", state.query)
        assertFalse(state.hasSearched)
        assertTrue(state.groups.isEmpty)
    }

    @Test
    fun `failed providers are reported and other results still show`() = runTest(dispatcher) {
        coEvery { UnifiedSearch.search(any()) } returns UnifiedSearchResult(
            groups = SearchGroups(
                movies = listOf(Title(name = "Film", mediaType = MediaType.MOVIE))
            ),
            failedProviders = listOf("AniList")
        )

        val viewModel = SearchViewModel()
        viewModel.searchNow("film")
        advanceUntilIdle(); idle()

        val state = viewModel.uiState.value
        assertEquals(listOf("Film"), state.groups.movies.map { it.name })
        assertEquals(listOf("AniList"), state.failedProviders)
        assertTrue(state.partialFailureNote!!.contains("AniList"))
        assertFalse(state.isEmptyResult)
    }

    @Test
    fun `complete failure is a search failure not an empty result`() = runTest(dispatcher) {
        // Cycle 13: when every provider fails, the state must say
        // the search never happened — "No results" would claim the
        // title doesn't exist.
        coEvery { UnifiedSearch.search(any()) } returns UnifiedSearchResult(
            groups = SearchGroups(),
            failedProviders = listOf("TVmaze", "AniList", "Internet Archive"),
            allProvidersFailed = true
        )

        val viewModel = SearchViewModel()
        viewModel.searchNow("breaking")
        advanceUntilIdle(); idle()

        val state = viewModel.uiState.value
        assertTrue(state.hasSearched)
        assertTrue(state.isSearchFailed)
        assertEquals(null, state.partialFailureNote)
    }

    @Test
    fun `partial failure with empty results still names the failed sources`() =
        runTest(dispatcher) {
            // Cycle 13: the rest answered with nothing — a real
            // "no results", but the failed sources are still named.
            coEvery { UnifiedSearch.search(any()) } returns UnifiedSearchResult(
                groups = SearchGroups(),
                failedProviders = listOf("AniList"),
                allProvidersFailed = false
            )

            val viewModel = SearchViewModel()
            viewModel.searchNow("breaking")
            advanceUntilIdle(); idle()

            val state = viewModel.uiState.value
            assertFalse(state.isSearchFailed)
            assertTrue(state.isEmptyResult)
            assertTrue(state.partialFailureNote!!.contains("AniList"))
            assertTrue(state.partialFailureNote!!.contains("found nothing"))
        }

    @Test
    fun `retry after a complete failure runs the search again`() = runTest(dispatcher) {
        coEvery { UnifiedSearch.search(any()) } returns UnifiedSearchResult(
            groups = SearchGroups(),
            failedProviders = listOf("TVmaze"),
            allProvidersFailed = true
        )

        val viewModel = SearchViewModel()
        viewModel.searchNow("breaking")
        advanceUntilIdle(); idle()
        assertTrue(viewModel.uiState.value.isSearchFailed)

        coEvery { UnifiedSearch.search(any()) } returns UnifiedSearchResult(
            SearchGroups(shows = listOf(Title(name = "Breaking Bad", mediaType = MediaType.TV)))
        )
        viewModel.retrySearch()
        advanceUntilIdle(); idle()

        val state = viewModel.uiState.value
        assertFalse(state.isSearchFailed)
        assertEquals(listOf("Breaking Bad"), state.groups.shows.map { it.name })
    }

    @Test
    fun `trending searches come from live trending titles`() = runTest(dispatcher) {
        coEvery { TvMazeApi.fetchTrendingShows(any()) } returns listOf(
            Title(name = "Show A", mediaType = MediaType.TV)
        )
        coEvery { AniListApi.fetchTrendingAnime(any()) } returns listOf(
            Title(name = "Anime B", mediaType = MediaType.ANIME)
        )

        val viewModel = SearchViewModel()
        advanceUntilIdle(); idle()

        assertEquals(
            listOf("Show A", "Anime B"),
            viewModel.uiState.value.trendingSearches
        )
    }

    @Test
    fun `recent searches survive a new viewmodel when persisted`() = runTest(dispatcher) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(
            SearchHistoryRepository.PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        ).edit().clear().commit()
        val history = SearchHistoryRepository(context)
        coEvery { UnifiedSearch.search(any()) } returns UnifiedSearchResult(
            SearchGroups(shows = listOf(Title(name = "Show", mediaType = MediaType.TV)))
        )

        val first = SearchViewModel(history)
        first.searchNow("show")
        advanceUntilIdle(); idle()
        assertEquals(listOf("show"), first.uiState.value.recentSearches)

        // A fresh ViewModel (e.g. after reopening Search) loads them back.
        val second = SearchViewModel(history)
        advanceUntilIdle(); idle()
        assertEquals(listOf("show"), second.uiState.value.recentSearches)
    }

    @Test
    fun `saved tmdb key is passed to unified search`() = runTest(dispatcher) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val prefs = context.getSharedPreferences(
            TmdbKeyRepository.PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        val keys = TmdbKeyRepository(context)
        keys.setKey("test-key")
        coEvery { UnifiedSearch.search(any(), "test-key") } returns UnifiedSearchResult(
            SearchGroups(
                movies = listOf(
                    Title(tmdbId = 579974, name = "RRR", mediaType = MediaType.MOVIE)
                )
            )
        )

        val viewModel = SearchViewModel(tmdbKeys = keys)
        viewModel.searchNow("rrr")
        advanceUntilIdle(); idle()

        val state = viewModel.uiState.value
        assertEquals(listOf("RRR"), state.groups.movies.map { it.name })
        assertEquals(579974, state.groups.movies[0].tmdbId)
        coVerify { UnifiedSearch.search("rrr", "test-key") }
        prefs.edit().clear().commit()
    }

    @Test
    fun `no key means unified search gets no key`() = runTest(dispatcher) {
        coEvery { UnifiedSearch.search(any(), null) } returns UnifiedSearchResult(
            SearchGroups(shows = listOf(Title(name = "Show", mediaType = MediaType.TV)))
        )

        val viewModel = SearchViewModel()
        viewModel.searchNow("show")
        advanceUntilIdle(); idle()

        assertEquals(listOf("Show"), viewModel.uiState.value.groups.shows.map { it.name })
        coVerify { UnifiedSearch.search("show", null) }
    }

    // Cycle 14 (first-run audit): the screen's no-key note keys off
    // this state — without a saved key TMDB is not searched, and
    // the state must say so from the start, not only after a search.
    @Test
    fun `no saved key means hasTmdbKey is false`() = runTest(dispatcher) {
        val viewModel = SearchViewModel()
        assertFalse(viewModel.uiState.value.hasTmdbKey)
    }

    @Test
    fun `saved key means hasTmdbKey is true from the start`() = runTest(dispatcher) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val prefs = context.getSharedPreferences(
            TmdbKeyRepository.PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        val keys = TmdbKeyRepository(context)
        keys.setKey("test-key")

        val viewModel = SearchViewModel(tmdbKeys = keys)
        assertTrue(viewModel.uiState.value.hasTmdbKey)
        prefs.edit().clear().commit()
    }
}
