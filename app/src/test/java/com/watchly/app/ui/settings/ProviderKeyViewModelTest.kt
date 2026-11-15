package com.watchly.app.ui.settings
import com.watchly.app.data.settings.TestKeys

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.model.FanartArtwork
import com.watchly.app.data.model.OmdbRatings
import com.watchly.app.data.model.Title
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.remote.FanartApi
import com.watchly.app.data.remote.OmdbApi
import com.watchly.app.data.remote.OpenSubtitlesApi
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cycle 4: the shared test-and-save contract for the four new
 * provider key screens — a key is stored ONLY when its provider
 * accepts it, and removing a key clears it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProviderKeyViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var keys: ProviderKeysRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(
            ProviderKeysRepository.PREFS_NAME, Context.MODE_PRIVATE
        ).edit().clear().commit()
        keys = ProviderKeysRepository(context)
        TestKeys.clearEmbeddedDefaults()
        mockkObject(OmdbApi)
        mockkObject(TraktApi)
        mockkObject(OpenSubtitlesApi)
        mockkObject(FanartApi)
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `omdb key saves only when omdb accepts it`() = runTest(dispatcher) {
        coEvery { OmdbApi.fetchRatings(any(), any()) } returns
            OmdbRatings(imdbRating = 9.0)
        val viewModel = ProviderKeyViewModel(ApiProvider.OMDB, keys)
        viewModel.onInputChange("good-key")
        viewModel.testAndSave()
        advanceUntilIdle()

        assertTrue(keys.hasKey(ApiProvider.OMDB))
        assertTrue(viewModel.uiState.value.hasSavedKey)
        assertNull(viewModel.uiState.value.errorMessage)

        // A rejected key is NOT stored.
        keys.clearKey(ApiProvider.OMDB)
        coEvery { OmdbApi.fetchRatings(any(), any()) } returns null
        val viewModel2 = ProviderKeyViewModel(ApiProvider.OMDB, keys)
        viewModel2.onInputChange("bad-key")
        viewModel2.testAndSave()
        advanceUntilIdle()

        assertFalse(keys.hasKey(ApiProvider.OMDB))
        assertTrue(viewModel2.uiState.value.errorMessage != null)
    }

    @Test
    fun `trakt key saves when trending answers`() = runTest(dispatcher) {
        coEvery { TraktApi.fetchTrendingMovies(any(), any()) } returns listOf(
            Title(name = "Anything", mediaType = MediaType.MOVIE)
        )
        val viewModel = ProviderKeyViewModel(ApiProvider.TRAKT, keys)
        viewModel.onInputChange("client-id")
        viewModel.testAndSave()
        advanceUntilIdle()

        assertTrue(keys.hasKey(ApiProvider.TRAKT))
    }

    @Test
    fun `opensubtitles key saves when the ping answers`() = runTest(dispatcher) {
        coEvery { OpenSubtitlesApi.ping(any()) } returns true
        val viewModel = ProviderKeyViewModel(ApiProvider.OPENSUBTITLES, keys)
        viewModel.onInputChange("os-key")
        viewModel.testAndSave()
        advanceUntilIdle()

        assertTrue(keys.hasKey(ApiProvider.OPENSUBTITLES))
    }

    @Test
    fun `fanart key saves when artwork answers and remove clears`() =
        runTest(dispatcher) {
            coEvery { FanartApi.fetchArtwork(any(), any(), any()) } returns
                FanartArtwork(logoUrl = "https://assets.fanart.tv/logo.png")
            val viewModel = ProviderKeyViewModel(ApiProvider.FANART, keys)
            viewModel.onInputChange("fanart-key")
            viewModel.testAndSave()
            advanceUntilIdle()
            assertTrue(keys.hasKey(ApiProvider.FANART))

            viewModel.removeKey()
            assertFalse(keys.hasKey(ApiProvider.FANART))
            assertEquals(false, viewModel.uiState.value.hasSavedKey)
        }

    @Test
    fun `blank input is an error and saves nothing`() = runTest(dispatcher) {
        val viewModel = ProviderKeyViewModel(ApiProvider.OMDB, keys)
        viewModel.testAndSave()
        advanceUntilIdle()

        assertFalse(keys.hasKey(ApiProvider.OMDB))
        assertTrue(viewModel.uiState.value.errorMessage != null)
    }
}
