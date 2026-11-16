/*
 * Watchly — "playback never fails": when a cloud embed page dies the
 * player walks the ranked server list on its own, remembers the source
 * that worked, and only gives up (with Retry) when everything failed.
 */
package com.watchly.app.ui.player

import android.app.Application
import android.content.Context
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.SubtitleTrack
import com.watchly.app.data.model.Title
import com.watchly.app.data.playback.EngineState
import com.watchly.app.data.playback.PlaybackRepository
import com.watchly.app.data.playback.PlayerEngine
import com.watchly.app.data.playback.ResolvedPlayable
import com.watchly.app.data.settings.TestKeys
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerEmbedFailoverTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var fake: FakeEngine

    // No tmdbId/imdbId/archiveId/localUri: start() skips the instant
    // fast-path and takes the injectable resolver branch.
    private val show = Title(
        tvmazeId = 9,
        name = "Fallback Show",
        mediaType = MediaType.TV,
        year = "2024"
    )

    private val embedA = PlayableFile(
        url = "https://alpha.example/embed", fileName = "Alpha", format = "EMBED",
        qualityLabel = "Alpha"
    )
    private val embedB = PlayableFile(
        url = "https://beta.example/embed", fileName = "Beta", format = "EMBED",
        qualityLabel = "Beta"
    )
    private val embedC = PlayableFile(
        url = "https://gamma.example/embed", fileName = "Gamma", format = "EMBED",
        qualityLabel = "Gamma"
    )
    private val direct = PlayableFile(
        url = "https://cdn.example/video.mp4", fileName = "video.mp4",
        format = "MPEG4", sizeBytes = 1_000L
    )

    private class FakeEngine : PlayerEngine {
        override val player: Player? = null
        private val _state = MutableStateFlow(EngineState())
        override val state: StateFlow<EngineState> = _state.asStateFlow()
        var lastSource: String? = null
        override fun setSource(url: String) {
            lastSource = url
        }
        override fun play() {}
        override fun pause() {}
        override fun seekTo(positionMs: Long) {}
        override fun setSpeed(speed: Float) {}
        override fun setSubtitle(track: SubtitleTrack?) {}
        override fun release() {}
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(
            PlaybackRepository.PREFS_NAME, Context.MODE_PRIVATE
        ).edit().clear().commit()
        context.getSharedPreferences(
            WatchlistRepository.PREFS_NAME, Context.MODE_PRIVATE
        ).edit().clear().commit()
        TestKeys.clearEmbeddedDefaults()
        fake = FakeEngine()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(resolved: ResolvedPlayable): PlayerViewModel {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return PlayerViewModel(
            request = PlaybackRequest(show, emptyList()),
            playback = PlaybackRepository(context),
            library = WatchlistRepository(context),
            engineFactory = { fake },
            appContext = context,
            mainResolver = { _, _, _ -> resolved }
        )
    }

    @Test
    fun deadEmbedWalksEveryServerThenErrorsAndRetryRestartsFresh() =
        runTest(dispatcher) {
            val vm = viewModel(
                ResolvedPlayable(files = listOf(embedA, embedB, embedC), webEmbedUrl = embedA.url)
            )
            advanceUntilIdle()
            assertEquals(embedA.url, vm.uiState.value.webEmbedUrl)

            vm.embedSourceFailed()
            var state = vm.uiState.value
            assertEquals(embedB.url, state.webEmbedUrl)
            assertEquals("This source failed — trying the next one…", state.failoverNote)
            assertNull(state.errorMessage)

            vm.embedSourceFailed()
            assertEquals(embedC.url, vm.uiState.value.webEmbedUrl)

            vm.embedSourceFailed()
            state = vm.uiState.value
            assertNotNull(state.errorMessage)
            assertNull(state.failoverNote)

            // Retry = a fresh pass from the top of the list.
            vm.retryEmbeds()
            state = vm.uiState.value
            assertEquals(embedA.url, state.webEmbedUrl)
            assertNull(state.errorMessage)
            assertTrue(state.embedReloadTick > 0)
        }

    @Test
    fun manualServerPickIsRememberedForTheNextSession() = runTest(dispatcher) {
        val resolved =
            ResolvedPlayable(files = listOf(embedA, embedB, embedC), webEmbedUrl = embedA.url)
        val first = viewModel(resolved)
        advanceUntilIdle()
        first.selectWebServer(embedC)
        assertEquals(embedC.url, first.uiState.value.webEmbedUrl)
        // A manual pick forgives past failures too.
        first.embedSourceFailed()
        first.selectWebServer(embedC)
        assertEquals(embedC.url, first.uiState.value.webEmbedUrl)

        val second = viewModel(resolved)
        advanceUntilIdle()
        assertEquals(embedC.url, second.uiState.value.webEmbedUrl)
    }

    @Test
    fun exhaustedCloudServersFallBackToDirectSources() = runTest(dispatcher) {
        val vm = viewModel(
            ResolvedPlayable(files = listOf(embedA, direct), webEmbedUrl = embedA.url)
        )
        advanceUntilIdle()

        vm.embedSourceFailed()
        val state = vm.uiState.value
        assertNull(state.webEmbedUrl)
        assertEquals(listOf(direct), state.files)
        assertEquals(0, state.fileIndex)
        assertEquals(direct.url, fake.lastSource)
    }
}
