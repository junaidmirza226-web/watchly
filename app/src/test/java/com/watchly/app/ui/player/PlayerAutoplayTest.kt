package com.watchly.app.ui.player

import android.app.Application
import android.os.Looper
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
import com.watchly.app.data.playback.UpNextItem
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Cycle 5 (Phase C): autoplay-next in the real PlayerViewModel,
 * driven by a fake engine + a scripted source resolver. Covers the
 * offer, the countdown hand-off, Cancel, Play Now, the settings
 * toggle, info-only skipping, and unresolvable-item skipping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerAutoplayTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var playback: PlaybackRepository
    private lateinit var library: WatchlistRepository
    private lateinit var fake: FakeEngine
    private var viewModel: PlayerViewModel? = null

    private val episode1 = Title(
        archiveId = "series-ep1", name = "Series Episode 1",
        mediaType = MediaType.TV, year = "1960"
    )
    private val episode2 = Title(
        archiveId = "series-ep2", name = "Series Episode 2",
        mediaType = MediaType.TV, year = "1960"
    )
    private val episode3 = Title(
        archiveId = "series-ep3", name = "Series Episode 3",
        mediaType = MediaType.TV, year = "1960"
    )
    private val catalogueOnly = Title(
        tmdbId = 42, name = "Catalogue Only",
        mediaType = MediaType.TV, year = "1960"
    )
    private val file1 = PlayableFile("https://x/ep1.mp4", "ep1.mp4")
    private val file2 = PlayableFile("https://x/ep2.mp4", "ep2.mp4")
    private val file3 = PlayableFile("https://x/ep3.mp4", "ep3.mp4")

    private class FakeEngine : PlayerEngine {
        override val player: Player? = null
        private val _state = MutableStateFlow(EngineState())
        override val state: StateFlow<EngineState> = _state.asStateFlow()
        val sources = ArrayList<String>()

        fun emit(state: EngineState) {
            _state.value = state
        }

        override fun setSource(url: String) {
            sources.add(url)
            _state.value = EngineState()
        }

        override fun play() {
            _state.value = _state.value.copy(isPlaying = true)
        }

        override fun pause() {
            _state.value = _state.value.copy(isPlaying = false)
        }

        override fun seekTo(positionMs: Long) {
            _state.value = _state.value.copy(positionMs = positionMs)
        }

        override fun setSpeed(speed: Float) = Unit
        override fun setSubtitle(track: SubtitleTrack?) = Unit
        override fun release() = Unit
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        listOf(PlaybackRepository.PREFS_NAME, WatchlistRepository.PREFS_NAME)
            .forEach { name ->
                context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                    .edit().clear().commit()
            }
        playback = PlaybackRepository(context)
        library = WatchlistRepository(context)
        fake = FakeEngine()
    }

    @After
    fun tearDown() {
        viewModel?.close()
        viewModel = null
        Dispatchers.resetMain()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun createViewModel(
        queue: List<UpNextItem>,
        autoplay: Boolean = true,
        resolver: suspend (Title) -> ResolvedPlayable = { title ->
            when (title.archiveId) {
                "series-ep2" -> ResolvedPlayable(files = listOf(file2))
                "series-ep3" -> ResolvedPlayable(files = listOf(file3))
                else -> ResolvedPlayable.NOT_PLAYABLE
            }
        }
    ): PlayerViewModel {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return PlayerViewModel(
            request = PlaybackRequest(
                title = episode1,
                files = listOf(file1),
                episodeLabel = "S1 E1",
                upNext = queue
            ),
            playback = playback,
            library = library,
            engineFactory = { fake },
            appContext = context,
            autoplayEnabled = { autoplay },
            sourceResolver = resolver
        ).also { viewModel = it }
    }

    /** Drives the fake engine to the end of episode 1. */
    private fun finishCurrent() {
        fake.emit(
            EngineState(
                isPlaying = false,
                positionMs = 3_600_000L,
                durationMs = 3_600_000L
            )
        )
    }

    @Test
    fun `finish offers the next playable item, skipping info-only`() =
        runTest(dispatcher) {
            createViewModel(
                queue = listOf(
                    UpNextItem(catalogueOnly, "S1 E2"),
                    UpNextItem(episode2, "S1 E2"),
                    UpNextItem(episode3, "S1 E3")
                )
            )
            advanceTimeBy(100); idle()
            finishCurrent()
            advanceTimeBy(100); idle()

            val state = viewModel!!.uiState.value
            assertEquals("Series Episode 2", state.upNext?.title?.name)
            assertEquals("S1 E2", state.upNext?.episodeLabel)
            assertEquals(PlayerViewModel.COUNTDOWN_SECONDS, state.upNextSecondsLeft)
            // The finished episode is marked watched, as before.
            assertTrue(library.isWatched(episode1))
        }

    @Test
    fun `countdown expiry starts the next item`() = runTest(dispatcher) {
        createViewModel(queue = listOf(UpNextItem(episode2, "S1 E2")))
        advanceTimeBy(100); idle()
        finishCurrent()
        advanceTimeBy(100); idle()
        assertEquals("Series Episode 2", viewModel!!.uiState.value.upNext?.title?.name)

        advanceTimeBy(PlayerViewModel.COUNTDOWN_SECONDS * 1_000L + 500); idle()

        val state = viewModel!!.uiState.value
        assertNull(state.upNext)
        assertEquals("Series Episode 2", state.title.name)
        assertEquals("S1 E2", state.episodeLabel)
        assertTrue(fake.sources.contains(file2.url))
    }

    @Test
    fun `cancel dismisses the offer and nothing else plays`() =
        runTest(dispatcher) {
            createViewModel(queue = listOf(UpNextItem(episode2, "S1 E2")))
            advanceTimeBy(100); idle()
            finishCurrent()
            advanceTimeBy(100); idle()

            viewModel!!.cancelUpNext()
            advanceTimeBy(PlayerViewModel.COUNTDOWN_SECONDS * 1_000L + 1_000); idle()

            val state = viewModel!!.uiState.value
            assertNull(state.upNext)
            assertEquals("Series Episode 1", state.title.name)
            assertEquals(listOf(file1.url), fake.sources)
        }

    @Test
    fun `play now skips the countdown`() = runTest(dispatcher) {
        createViewModel(queue = listOf(UpNextItem(episode2, "S1 E2")))
        advanceTimeBy(100); idle()
        finishCurrent()
        advanceTimeBy(100); idle()

        viewModel!!.playUpNextNow()
        advanceTimeBy(100); idle()

        assertEquals("Series Episode 2", viewModel!!.uiState.value.title.name)
        assertTrue(fake.sources.contains(file2.url))
    }

    @Test
    fun `autoplay off means no offer`() = runTest(dispatcher) {
        createViewModel(
            queue = listOf(UpNextItem(episode2, "S1 E2")),
            autoplay = false
        )
        advanceTimeBy(100); idle()
        finishCurrent()
        advanceTimeBy(1_000); idle()

        assertNull(viewModel!!.uiState.value.upNext)
        assertEquals(listOf(file1.url), fake.sources)
    }

    @Test
    fun `an item that fails to resolve is skipped for the next resolvable one`() =
        runTest(dispatcher) {
            // Episode 2 is badged playable but its files don't load
            // (the resolver says NOT_PLAYABLE): episode 3 must win.
            createViewModel(
                queue = listOf(
                    UpNextItem(episode2, "S1 E2"),
                    UpNextItem(episode3, "S1 E3")
                ),
                resolver = { title ->
                    if (title.archiveId == "series-ep3") {
                        ResolvedPlayable(files = listOf(file3))
                    } else {
                        ResolvedPlayable.NOT_PLAYABLE
                    }
                }
            )
            advanceTimeBy(100); idle()
            finishCurrent()
            advanceTimeBy(100); idle()

            assertEquals("Series Episode 3", viewModel!!.uiState.value.upNext?.title?.name)
        }

    @Test
    fun `no queue means no offer`() = runTest(dispatcher) {
        createViewModel(queue = emptyList())
        advanceTimeBy(100); idle()
        finishCurrent()
        advanceTimeBy(500); idle()

        assertNull(viewModel!!.uiState.value.upNext)
        assertTrue(library.isWatched(episode1))
    }
}
