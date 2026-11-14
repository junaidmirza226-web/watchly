package com.watchly.app.ui.details

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.model.FanartArtwork
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.OmdbRatings
import com.watchly.app.data.model.Title
import com.watchly.app.data.remote.FanartApi
import com.watchly.app.data.remote.OmdbApi
import com.watchly.app.data.remote.TraktApi
import com.watchly.app.data.settings.ApiProvider
import com.watchly.app.data.settings.ProviderKeysRepository
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

/**
 * Cycle 4: Title Details enrichment — with the user's keys saved,
 * OMDb ratings, the Trakt community rating and Fanart artwork land
 * in state; without keys nothing is fetched and the page state
 * stays exactly as before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TitleDetailsProviderRatingsTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var providerKeys: ProviderKeysRepository
    private lateinit var library: WatchlistRepository

    private val knight = Title(
        tmdbId = 155,
        imdbId = "tt0468569",
        name = "The Dark Knight",
        mediaType = MediaType.MOVIE,
        year = "2008"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(
            ProviderKeysRepository.PREFS_NAME, Context.MODE_PRIVATE
        ).edit().clear().commit()
        context.getSharedPreferences(
            WatchlistRepository.PREFS_NAME, Context.MODE_PRIVATE
        ).edit().clear().commit()
        providerKeys = ProviderKeysRepository(context)
        library = WatchlistRepository(context)
        mockkObject(OmdbApi)
        mockkObject(TraktApi)
        mockkObject(FanartApi)
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `with all keys the ratings and artwork land in state`() = runTest(dispatcher) {
        providerKeys.setKey(ApiProvider.OMDB, "omdb-key")
        providerKeys.setKey(ApiProvider.TRAKT, "trakt-id")
        providerKeys.setKey(ApiProvider.FANART, "fanart-key")
        coEvery { OmdbApi.getRatings(any(), any(), any(), any()) } returns OmdbRatings(
            imdbRating = 9.0,
            imdbVotes = "2,900,000",
            rottenTomatoesPercent = 94,
            metacriticScore = 84,
            rated = "PG-13"
        )
        coEvery { TraktApi.fetchRating(any(), any(), any()) } returns 8.66
        coEvery { FanartApi.fetchArtwork(any(), any(), any()) } returns FanartArtwork(
            logoUrl = "https://assets.fanart.tv/logo.png",
            backdropUrl = "https://assets.fanart.tv/bg.jpg"
        )

        val viewModel = TitleDetailsViewModel(
            title = knight,
            library = library,
            providerKeys = providerKeys,
            omdbCache = null
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(94, state.omdbRatings?.rottenTomatoesPercent)
        assertEquals(9.0, state.omdbRatings!!.imdbRating!!, 0.001)
        assertEquals(8.66, state.traktRating!!, 0.001)
        assertEquals("https://assets.fanart.tv/logo.png", state.fanartLogoUrl)
        assertEquals("https://assets.fanart.tv/bg.jpg", state.fanartBackdropUrl)
    }

    @Test
    fun `without keys nothing is fetched and state stays plain`() = runTest(dispatcher) {
        // No stubs at all: any call into the mocked APIs would throw
        // (and be swallowed) — the assertions pin the visible result.
        val viewModel = TitleDetailsViewModel(
            title = knight,
            library = library,
            providerKeys = providerKeys,
            omdbCache = null
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.omdbRatings)
        assertNull(state.traktRating)
        assertNull(state.fanartLogoUrl)
        assertNull(state.fanartBackdropUrl)
    }

    @Test
    fun `a failing provider leaves the others intact`() = runTest(dispatcher) {
        providerKeys.setKey(ApiProvider.OMDB, "omdb-key")
        providerKeys.setKey(ApiProvider.TRAKT, "trakt-id")
        coEvery { OmdbApi.getRatings(any(), any(), any(), any()) } returns null
        coEvery { TraktApi.fetchRating(any(), any(), any()) } returns 7.5

        val viewModel = TitleDetailsViewModel(
            title = knight,
            library = library,
            providerKeys = providerKeys,
            omdbCache = null
        )
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.omdbRatings)
        assertEquals(7.5, viewModel.uiState.value.traktRating!!, 0.001)
    }
}
