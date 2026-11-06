package com.watchly.app.ui.player

import android.app.Application
import android.os.Looper
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.Title
import com.watchly.app.data.playback.EngineState
import com.watchly.app.data.playback.PlaybackProgress
import com.watchly.app.data.playback.PlaybackRepository
import com.watchly.app.data.playback.PlayerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric test for the REAL [PlayerViewModel] driven by a fake
 * engine — no ExoPlayer, no surface. Position saves are throttled
 * off the engine's own updates (no background ticker), so the tests
 * advance virtual time by fixed steps and finish cleanly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var playback: PlaybackRepository
    private lateinit var library: WatchlistRepository
    private lateinit var fake: FakePlayerEngine
    private var viewModel: PlayerViewModel? = null

    private val film = Title(
        archiveId = "nightofthelivingdead_201910",
        name = "Night of the Living Dead",
        mediaType = MediaType.MOVIE,
        year = "1968"
    )
    private val file1 = PlayableFile(
        url = "https://archive.org/download/x/film-720p.mp4",
        fileName = "film-720p.mp4",
        format = "MPEG4",
        sizeBytes = 1_600_000_000L
    )
    private val file2 = PlayableFile(
        url = "https://archive.org/download/x/film-360p.mp4",
        fileName = "film-360p.mp4",
        format = "MPEG4",
        sizeBytes = 400_000_000L
    )

    /** Controllable stand-in for ExoPlayer: the test drives its states. */
    private class FakePlayerEngine : PlayerEngine {
        override val player: Player? = null
        private val _state = MutableStateFlow(EngineState())
        override val state: StateFlow<EngineState> = _state.asStateFlow()
        val sources = ArrayList<String>()
        var speedSet: Float? = null
        var playCalls = 0
        var pauseCalls = 0

        fun emit(state: EngineState) {
            _state.value = state
        }

        override fun setSource(url: String) {
            sources.add(url)
            _state.value = EngineState()
        }

        override fun play() {
            playCalls++
            _state.value = _state.value.copy(isPlaying = true)
        }

        override fun pause() {
            pauseCalls++
            _state.value = _state.value.copy(isPlaying = false)
        }

        override fun seekTo(positionMs: Long) {
            _state.value = _state.value.copy(positionMs = positionMs)
        }

        override fun setSpeed(speed: Float) {
            speedSet = speed
        }

        override fun release() {}
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(
            PlaybackRepository.PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        ).edit().clear().commit()
        context.getSharedPreferences(
            WatchlistRepository.PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        ).edit().clear().commit()
        playback = PlaybackRepository(context)
        library = WatchlistRepository(context)
        fake = FakePlayerEngine()
    }

    @After
    fun tearDown() {
        // Release the fake engine so no ViewModel work outlives the test.
        viewModel?.close()
        viewModel = null
        Dispatchers.resetMain()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun createViewModel(files: List<PlayableFile> = listOf(file1, file2)): PlayerViewModel {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return PlayerViewModel(
            request = PlaybackRequest(film, files),
            playback = playback,
            library = library,
            engineFactory = { fake },
            appContext = context
        ).also { viewModel = it }
    }

    private fun savedProgress(positionMs: Long, durationMs: Long = 3_600_000L) {
        playback.saveProgress(
            PlaybackProgress(
                key = film.stableKey,
                titleName = film.name,
                posterUrl = null,
                mediaType = MediaType.MOVIE,
                episodeLabel = null,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAtMs = 1_000L
            )
        )
    }

    @Test
    fun `starts the first source and plays`() = runTest(dispatcher) {
        createViewModel()
        advanceTimeBy(100); idle()

        assertEquals(listOf(file1.url), fake.sources)
        assertEquals(1, fake.playCalls)
        assertEquals(1.0f, viewModel!!.uiState.value.speed)
    }

    @Test
    fun `resumes from a saved position between 5 and 95 percent`() = runTest(dispatcher) {
        savedProgress(1_800_000L)
        createViewModel()
        advanceTimeBy(100); idle()

        fake.emit(EngineState(isPlaying = true, durationMs = 3_600_000L))
        advanceTimeBy(100); idle()

        assertEquals(1_800_000L, viewModel!!.uiState.value.resumedFromMs)
        assertEquals(1_800_000L, fake.state.value.positionMs)
    }

    @Test
    fun `does not resume below 5 percent`() = runTest(dispatcher) {
        savedProgress(108_000L) // 3%
        createViewModel()
        advanceTimeBy(100); idle()

        fake.emit(EngineState(isPlaying = true, durationMs = 3_600_000L))
        advanceTimeBy(100); idle()

        assertNull(viewModel!!.uiState.value.resumedFromMs)
        assertEquals(0L, fake.state.value.positionMs)
    }

    @Test
    fun `a source error fails over to the next source`() = runTest(dispatcher) {
        createViewModel()
        advanceTimeBy(100); idle()
        fake.emit(EngineState(isPlaying = true, positionMs = 60_000L, durationMs = 3_600_000L))
        advanceTimeBy(100); idle()

        fake.emit(
            EngineState(
                isPlaying = false,
                positionMs = 60_000L,
                durationMs = 3_600_000L,
                error = "Couldn't reach this source. Check your connection."
            )
        )
        advanceTimeBy(100); idle()

        val state = viewModel!!.uiState.value
        assertEquals(1, state.fileIndex)
        assertEquals(listOf(file1.url, file2.url), fake.sources)
        assertNotNull(state.failoverNote)
        assertNull(state.errorMessage)
        assertEquals(2, fake.playCalls)
    }

    @Test
    fun `all sources failing shows the error and manual pick recovers`() =
        runTest(dispatcher) {
            createViewModel()
            advanceTimeBy(100); idle()

            fake.emit(EngineState(error = "Source 1 failed"))
            advanceTimeBy(100); idle()
            fake.emit(EngineState(error = "Source 2 failed"))
            advanceTimeBy(100); idle()

            val state = viewModel!!.uiState.value
            assertNotNull(state.errorMessage)
            assertEquals(setOf(0, 1), state.failedIndexes)

            viewModel!!.playSource(0)
            advanceTimeBy(100); idle()
            assertNull(viewModel!!.uiState.value.errorMessage)
            assertEquals(0, viewModel!!.uiState.value.fileIndex)
            assertEquals(file1.url, fake.sources.last())
        }

    @Test
    fun `speed change persists and reaches the engine`() = runTest(dispatcher) {
        createViewModel()
        advanceTimeBy(100); idle()

        viewModel!!.setSpeed(1.5f)
        assertEquals(1.5f, playback.getSpeed())
        assertEquals(1.5f, fake.speedSet)
        assertEquals(1.5f, viewModel!!.uiState.value.speed)
    }

    @Test
    fun `hold speed is temporary and restores the saved speed`() = runTest(dispatcher) {
        createViewModel()
        advanceTimeBy(100); idle()

        viewModel!!.setSpeed(1.5f)
        viewModel!!.holdSpeed(2.0f)
        assertTrue(viewModel!!.uiState.value.isSpeedHeld)
        assertEquals(2.0f, fake.speedSet)
        // The saved speed is untouched by a hold.
        assertEquals(1.5f, playback.getSpeed())

        viewModel!!.restoreSpeed()
        assertFalse(viewModel!!.uiState.value.isSpeedHeld)
        assertEquals(1.5f, fake.speedSet)
        assertEquals(1.5f, viewModel!!.uiState.value.speed)
    }

    @Test
    fun `pause saves progress and finishing marks watched`() = runTest(dispatcher) {
        createViewModel()
        advanceTimeBy(100); idle()

        fake.emit(EngineState(isPlaying = true, positionMs = 1_800_000L, durationMs = 3_600_000L))
        advanceTimeBy(100); idle()

        viewModel!!.togglePlayPause()
        assertEquals(1, fake.pauseCalls)
        assertEquals(1_800_000L, playback.getProgress(film.stableKey)?.positionMs)

        // Past 95%: finished — watched, and the resume point is gone.
        fake.emit(EngineState(isPlaying = true, positionMs = 3_500_000L, durationMs = 3_600_000L))
        advanceTimeBy(100); idle()

        assertTrue(library.isWatched(film))
        assertNull(playback.getProgress(film.stableKey))
        assertFalse(viewModel!!.uiState.value.isPlaying)
    }
}
