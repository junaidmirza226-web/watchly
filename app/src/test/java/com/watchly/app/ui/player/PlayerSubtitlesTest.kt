package com.watchly.app.ui.player

import android.app.Application
import android.content.Context
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.SubtitleResult
import com.watchly.app.data.model.SubtitleTrack
import com.watchly.app.data.model.Title
import com.watchly.app.data.playback.EngineState
import com.watchly.app.data.playback.PlaybackRepository
import com.watchly.app.data.playback.PlayerEngine
import com.watchly.app.data.remote.OpenSubtitlesApi
import com.watchly.app.data.remote.SubtitleDownloadOutcome
import com.watchly.app.data.remote.SubtitleSearchOutcome
import com.watchly.app.data.settings.ApiProvider
import com.watchly.app.data.settings.ProviderKeysRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cycle 4: the player's OpenSubtitles flow — no-key message,
 * search results, pick → download → attach, quota message, and
 * turn-off. The provider is mocked; the file write is real (temp
 * dir), the engine is a fake that records the attached track.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerSubtitlesTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var providerKeys: ProviderKeysRepository
    private lateinit var subtitleDir: File
    private lateinit var fake: FakeEngine

    private val film = Title(
        imdbId = "tt0468569",
        name = "The Dark Knight",
        mediaType = MediaType.MOVIE,
        year = "2008"
    )
    private val file = PlayableFile(
        url = "https://example.com/knight.mp4",
        fileName = "knight.mp4",
        format = "MPEG4",
        sizeBytes = 1_000L
    )
    private val result = SubtitleResult(
        fileId = 42,
        language = "en",
        releaseName = "The.Dark.Knight.2008.BluRay",
        downloadCount = 100
    )

    private class FakeEngine : PlayerEngine {
        override val player: Player? = null
        private val _state = MutableStateFlow(EngineState())
        override val state: StateFlow<EngineState> = _state.asStateFlow()
        var attachedSubtitle: SubtitleTrack? = null
        var subtitleSet = false
        override fun setSource(url: String) {}
        override fun play() {}
        override fun pause() {}
        override fun seekTo(positionMs: Long) {}
        override fun setSpeed(speed: Float) {}
        override fun setSubtitle(track: SubtitleTrack?) {
            attachedSubtitle = track
            subtitleSet = true
        }
        override fun release() {}
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(
            ProviderKeysRepository.PREFS_NAME, Context.MODE_PRIVATE
        ).edit().clear().commit()
        context.getSharedPreferences(
            PlaybackRepository.PREFS_NAME, Context.MODE_PRIVATE
        ).edit().clear().commit()
        context.getSharedPreferences(
            WatchlistRepository.PREFS_NAME, Context.MODE_PRIVATE
        ).edit().clear().commit()
        providerKeys = ProviderKeysRepository(context)
        subtitleDir = java.nio.file.Files.createTempDirectory("subs").toFile()
        fake = FakeEngine()
        mockkObject(OpenSubtitlesApi)
        every { OpenSubtitlesApi.planSearch(any(), any()) } answers { callOriginal() }
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
        subtitleDir.deleteRecursively()
    }

    private fun viewModel(): PlayerViewModel {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return PlayerViewModel(
            request = PlaybackRequest(film, listOf(file)),
            playback = PlaybackRepository(context),
            library = WatchlistRepository(context),
            engineFactory = { fake },
            appContext = context,
            providerKeys = providerKeys,
            subtitleDir = subtitleDir
        )
    }

    @Test
    fun `without a key the picker explains where to add it`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.openSubtitles()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.subtitlesOpen)
        assertTrue(state.subtitleMessage!!.contains("API Keys"))
        assertTrue(state.subtitleResults.isEmpty())
    }

    @Test
    fun `search results show, picking downloads and attaches the track`() =
        runTest(dispatcher) {
            providerKeys.setKey(ApiProvider.OPENSUBTITLES, "os-key")
            coEvery { OpenSubtitlesApi.search(any(), any()) } returns
                SubtitleSearchOutcome.Found(listOf(result))
            coEvery { OpenSubtitlesApi.downloadLink(any(), any()) } returns
                SubtitleDownloadOutcome.Ready(
                    "https://cdn.example.com/knight.srt", "knight.srt"
                )
            coEvery { OpenSubtitlesApi.downloadFileBytes(any()) } returns
                "1\n00:00:01,000 --> 00:00:02,000\nHello.".toByteArray()

            val viewModel = viewModel()
            viewModel.openSubtitles()
            advanceUntilIdle()
            assertEquals(listOf(result), viewModel.uiState.value.subtitleResults)

            viewModel.pickSubtitle(result)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("The.Dark.Knight.2008.BluRay", state.activeSubtitleLabel)
            assertEquals(false, state.subtitlesOpen)
            assertTrue(fake.subtitleSet)
            assertEquals("application/x-subrip", fake.attachedSubtitle?.mimeType)
            assertEquals("en", fake.attachedSubtitle?.language)
            // The file really landed in the cache dir.
            assertTrue(File(subtitleDir, "knight.srt").exists())

            viewModel.clearSubtitles()
            assertNull(fake.attachedSubtitle)
            assertNull(viewModel.uiState.value.activeSubtitleLabel)
        }

    @Test
    fun `quota spent shows the come-back-tomorrow message`() = runTest(dispatcher) {
        providerKeys.setKey(ApiProvider.OPENSUBTITLES, "os-key")
        coEvery { OpenSubtitlesApi.search(any(), any()) } returns
            SubtitleSearchOutcome.QuotaReached

        val viewModel = viewModel()
        viewModel.openSubtitles()
        advanceUntilIdle()

        assertEquals(
            PlayerViewModel.QUOTA_MESSAGE,
            viewModel.uiState.value.subtitleMessage
        )
    }
}
