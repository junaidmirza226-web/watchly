package com.watchly.app.ui.browse

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.data.remote.CinemetaApi
import com.watchly.app.data.remote.TmdbApi
import com.watchly.app.data.settings.TestKeys
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real-device failure this pins: TMDB rejected/unreachable → the whole
 * tab used to die ("Something went wrong"). Now the keyless Cinemeta
 * catalogue rescues the tab and the filters refine it on-device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BrowseFallbackRescueTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        TestKeys.clearEmbeddedDefaults()
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun tmdbFailureRescuesFromCinemetaAndFiltersApplyOnDevice() = runTest(dispatcher) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(TmdbKeyRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        val keyRepo = TmdbKeyRepository(context)
        keyRepo.setKey("dead-key")

        mockkObject(TmdbApi)
        coEvery {
            TmdbApi.discoverMovies(any(), any(), any(), any(), any())
        } throws IllegalStateException("TMDB HTTP 401: Unauthorized")

        mockkObject(CinemetaApi)
        coEvery { CinemetaApi.fetchTrendingMovies(any()) } returns listOf(
            Title(tmdbId = 1, name = "Crime Hit", mediaType = MediaType.MOVIE, genres = listOf("Crime")),
            Title(tmdbId = 2, name = "Music Doc", mediaType = MediaType.MOVIE, genres = listOf("Music"))
        )

        val vm = BrowseViewModel(kind = BrowseKind.MOVIES, tmdbKeys = keyRepo)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf("Crime Hit", "Music Doc"), state.visibleTitles.map { it.name })
        assertTrue(state.fallbackMode)
        assertNull(state.errorMessage)

        // Genre filters refine the fallback pool on-device.
        vm.toggleGenre("Crime")
        advanceUntilIdle()
        assertEquals(listOf("Crime Hit"), vm.uiState.value.visibleTitles.map { it.name })
        assertTrue(vm.uiState.value.fallbackMode)
    }

    @Test
    fun tmdbFailureWithDeadFallbackStillReportsTheRealReason() = runTest(dispatcher) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(TmdbKeyRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        val keyRepo = TmdbKeyRepository(context)
        keyRepo.setKey("dead-key")

        mockkObject(TmdbApi)
        coEvery {
            TmdbApi.discoverMovies(any(), any(), any(), any(), any())
        } throws IllegalStateException("TMDB HTTP 401: Unauthorized")

        mockkObject(CinemetaApi)
        coEvery { CinemetaApi.fetchTrendingMovies(any()) } throws java.net.UnknownHostException("offline")

        val vm = BrowseViewModel(kind = BrowseKind.MOVIES, tmdbKeys = keyRepo)
        advanceUntilIdle()

        val message = vm.uiState.value.errorMessage
        assertTrue(message != null && message.contains("rejected"))
    }
}
