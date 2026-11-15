package com.watchly.app.ui.home
import com.watchly.app.data.settings.TestKeys

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.data.remote.AniListApi
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.TraktApi
import com.watchly.app.data.remote.TvMazeApi
import com.watchly.app.data.remote.YouTubeApi
import com.watchly.app.data.settings.ApiProvider
import com.watchly.app.data.settings.ProviderKeysRepository
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cycle 4: the Trending on Trakt Home row appears only with the
 * user's Trakt Client ID, and its items arrive deduped through the
 * canonical merge (the same TMDB id listed as movie + show merges
 * by type rules; duplicates within the feed collapse).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeTraktRowTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var providerKeys: ProviderKeysRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(
            ProviderKeysRepository.PREFS_NAME, Context.MODE_PRIVATE
        ).edit().clear().commit()
        providerKeys = ProviderKeysRepository(context)
        mockkObject(TvMazeApi)
        mockkObject(AniListApi)
        mockkObject(ArchiveApi)
        mockkObject(YouTubeApi)
        mockkObject(TraktApi)
        TestKeys.clearEmbeddedDefaults()
        coEvery { ArchiveApi.fetchMoviesPage(any(), any(), any(), any()) } returns emptyList()
        coEvery { TvMazeApi.fetchTrendingShows(any()) } returns emptyList()
        coEvery { AniListApi.fetchTrendingAnime(any()) } returns emptyList()
        coEvery { ArchiveApi.fetchCollectionPage(any(), any(), any(), any()) } returns emptyList()
        every { YouTubeApi.CHANNELS } returns emptyList()
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `without a trakt key there is no trakt row`() = runTest(dispatcher) {
        val viewModel = HomeViewModel(providerKeys = providerKeys)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.traktRow)
    }

    @Test
    fun `with a trakt key the trending row shows merged titles`() = runTest(dispatcher) {
        providerKeys.setKey(ApiProvider.TRAKT, "client-id-123")
        val knight = Title(
            tmdbId = 155, imdbId = "tt0468569",
            name = "The Dark Knight", mediaType = MediaType.MOVIE, year = "2008"
        )
        // Same canonical title twice in the feed (e.g. movies list
        // returned it once per endpoint) — the merge must collapse it.
        coEvery { TraktApi.fetchTrending(any(), any()) } returns listOf(knight, knight)

        val viewModel = HomeViewModel(providerKeys = providerKeys)
        advanceUntilIdle()

        val row = viewModel.uiState.value.traktRow
        assertEquals("Trending on Trakt", row?.heading)
        assertEquals(listOf("The Dark Knight"), row?.titles?.map { it.name })
    }
}
