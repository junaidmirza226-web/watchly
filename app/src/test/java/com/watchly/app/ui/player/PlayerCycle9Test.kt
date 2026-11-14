package com.watchly.app.ui.player

import android.app.Application
import android.content.Context
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.SubtitleBackground
import com.watchly.app.data.model.SubtitleResult
import com.watchly.app.data.model.SubtitleTrack
import com.watchly.app.data.model.Title
import com.watchly.app.data.playback.AudioTrackOption
import com.watchly.app.data.playback.EngineState
import com.watchly.app.data.playback.PlaybackRepository
import com.watchly.app.data.playback.PlayerEngine
import com.watchly.app.data.playback.ResolvedPlayable
import com.watchly.app.data.playback.UpNextItem
import com.watchly.app.data.remote.OpenSubtitlesApi
import com.watchly.app.data.remote.SubtitleDownloadOutcome
import com.watchly.app.data.remote.SubtitleSearchOutcome
import com.watchly.app.data.settings.ApiProvider
import com.watchly.app.data.settings.ProviderKeysRepository
import com.watchly.app.data.settings.UiPreferencesRepository
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
 * Cycle 9 player pack, driven through the real [PlayerViewModel]
 * with a fake engine — Quality (§5.7), Audio (§5.6), subtitle
 * style (§6.2), subtitle sync (§6.3) and the notification "Next"
 * path (§5.3). Same harness as the earlier player suites.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerCycle9Test {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var playback: PlaybackRepository
    private lateinit var library: WatchlistRepository
    private lateinit var providerKeys: ProviderKeysRepository
    private lateinit var uiPrefs: UiPreferencesRepository
    private lateinit var subtitleDir: File
    private lateinit var fake: FakeEngine
    private var viewModel: PlayerViewModel? = null

    private val film = Title(
        archiveId = "nightofthelivingdead1968",
        name = "Night of the Living Dead",
        mediaType = MediaType.MOVIE,
        year = "1968"
    )
    private val file720 = PlayableFile(
        url = "https://archive.org/download/x/film-720p.mp4",
        fileName = "film-720p.mp4",
        format = "MPEG4",
        sizeBytes = 1_600_000_000L,
        qualityLabel = "720p MPEG4"
    )
    private val file360 = PlayableFile(
        url = "https://archive.org/download/x/film-360p.mp4",
        fileName = "film-360p.mp4",
        format = "MPEG4",
        sizeBytes = 400_000_000L,
        qualityLabel = "360p MPEG4"
    )

    private val english = AudioTrackOption(
        id = "0:0", label = "English — AAC Stereo",
        language = "en", isSelected = true
    )
    private val urdu = AudioTrackOption(
        id = "0:1", label = "Urdu — AAC Stereo",
        language = "ur", isSelected = false
    )

    /** Controllable engine: records every cycle-9 call. */
    private class FakeEngine : PlayerEngine {
        override val player: Player? = null
        private val _state = MutableStateFlow(EngineState())
        override val state: StateFlow<EngineState> = _state.asStateFlow()
        val sources = ArrayList<String>()
        var selectedAudioId: String? = null
        var audioSelectCalls = 0
        var attachedSubtitle: SubtitleTrack? = null
        var subtitleSet = false
        var nowPlayingTitle: String? = null

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

        override fun setSubtitle(track: SubtitleTrack?) {
            attachedSubtitle = track
            subtitleSet = true
        }

        override fun selectAudioTrack(id: String?) {
            selectedAudioId = id
            audioSelectCalls++
        }

        override fun setNowPlaying(title: String, subtitle: String?) {
            nowPlayingTitle = title
        }

        override fun release() = Unit
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        listOf(
            PlaybackRepository.PREFS_NAME,
            WatchlistRepository.PREFS_NAME,
            ProviderKeysRepository.PREFS_NAME,
            UiPreferencesRepository.PREFS_NAME
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
        playback = PlaybackRepository(context)
        library = WatchlistRepository(context)
        providerKeys = ProviderKeysRepository(context)
        uiPrefs = UiPreferencesRepository(context)
        subtitleDir = java.nio.file.Files.createTempDirectory("subs9").toFile()
        fake = FakeEngine()
        mockkObject(OpenSubtitlesApi)
        every { OpenSubtitlesApi.planSearch(any(), any()) } answers { callOriginal() }
    }

    @After
    fun tearDown() {
        viewModel?.close()
        viewModel = null
        unmockkAll()
        Dispatchers.resetMain()
        subtitleDir.deleteRecursively()
    }

    private fun createViewModel(
        request: PlaybackRequest = PlaybackRequest(film, listOf(file720, file360)),
        resolver: suspend (Title) -> ResolvedPlayable = { ResolvedPlayable.NOT_PLAYABLE }
    ): PlayerViewModel {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return PlayerViewModel(
            request = request,
            playback = playback,
            library = library,
            engineFactory = { fake },
            appContext = context,
            providerKeys = providerKeys,
            subtitleDir = subtitleDir,
            uiPrefs = uiPrefs,
            sourceResolver = resolver
        ).also { viewModel = it }
    }

    // ---- Quality (§5.7) ----

    @Test
    fun `starts from the remembered quality file`() = runTest(dispatcher) {
        playback.saveQualityUrl(film.stableKey, file360.url)
        val vm = createViewModel()
        advanceUntilIdle()
        assertEquals(listOf(file360.url), fake.sources)
        assertEquals(1, vm.uiState.value.fileIndex)
        assertEquals(film.name, fake.nowPlayingTitle)
    }

    @Test
    fun `switchQuality keeps the position and remembers the pick`() =
        runTest(dispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()
            fake.emit(
                EngineState(isPlaying = true, positionMs = 60_000, durationMs = 120_000)
            )
            advanceUntilIdle()

            vm.switchQuality(1)
            advanceUntilIdle()

            assertEquals(file360.url, fake.sources.last())
            assertEquals(1, vm.uiState.value.fileIndex)
            // The seek back to the exact position was issued.
            assertEquals(60_000L, fake.state.value.positionMs)
            assertEquals(file360.url, playback.getQualityUrl(film.stableKey))
        }

    @Test
    fun `switchQuality to the playing file is a no-op`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        val before = fake.sources.size
        vm.switchQuality(0)
        advanceUntilIdle()
        assertEquals(before, fake.sources.size)
        assertNull(playback.getQualityUrl(film.stableKey))
    }

    // ---- Audio (§5.6) ----

    @Test
    fun `audio tracks flow into state and a pick reaches the engine`() =
        runTest(dispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()
            fake.emit(EngineState(audioTracks = listOf(english, urdu)))
            advanceUntilIdle()
            assertEquals(2, vm.uiState.value.audioTracks.size)

            vm.selectAudioTrack(urdu.id)
            assertEquals(urdu.id, fake.selectedAudioId)
            assertEquals("ur", playback.getAudioLanguage(film.stableKey))
            assertEquals(
                true,
                vm.uiState.value.audioTracks.first { it.id == urdu.id }.isSelected
            )
        }

    @Test
    fun `saved audio language is applied when the tracks arrive`() =
        runTest(dispatcher) {
            playback.saveAudioLanguage(film.stableKey, "ur")
            val vm = createViewModel()
            advanceUntilIdle()
            fake.emit(EngineState(audioTracks = listOf(english, urdu)))
            advanceUntilIdle()
            assertEquals(urdu.id, fake.selectedAudioId)
            assertEquals(1, fake.audioSelectCalls)
        }

    @Test
    fun `no saved language means no forced audio choice`() = runTest(dispatcher) {
        createViewModel()
        advanceUntilIdle()
        fake.emit(EngineState(audioTracks = listOf(english, urdu)))
        advanceUntilIdle()
        assertEquals(0, fake.audioSelectCalls)
    }

    // ---- Subtitle style (§6.2) ----

    @Test
    fun `subtitle style changes persist through UiPreferences`() =
        runTest(dispatcher) {
            val vm = createViewModel()
            vm.setSubtitleTextSize(24f)
            vm.setSubtitleBackground(SubtitleBackground.SOLID)
            val state = vm.uiState.value
            assertEquals(24f, state.subtitleStyle.textSizeSp)
            assertEquals(SubtitleBackground.SOLID, state.subtitleStyle.background)
            val fresh = UiPreferencesRepository(
                ApplicationProvider.getApplicationContext<Application>()
            ).subtitleStyle()
            assertEquals(24f, fresh.textSizeSp)
            assertEquals(SubtitleBackground.SOLID, fresh.background)
        }

    // ---- Subtitle sync (§6.3) ----

    @Test
    fun `offset re-attaches the subtitle shifted and persists`() =
        runTest(dispatcher) {
            val knight = Title(
                imdbId = "tt0468569",
                name = "The Dark Knight",
                mediaType = MediaType.MOVIE,
                year = "2008"
            )
            val result = SubtitleResult(
                fileId = 42, language = "en",
                releaseName = "The.Dark.Knight.2008.BluRay"
            )
            providerKeys.setKey(ApiProvider.OPENSUBTITLES, "os-key")
            coEvery { OpenSubtitlesApi.search(any(), any()) } returns
                SubtitleSearchOutcome.Found(listOf(result))
            coEvery { OpenSubtitlesApi.downloadLink(any(), any()) } returns
                SubtitleDownloadOutcome.Ready(
                    "https://cdn.example.com/knight.srt", "knight.srt"
                )
            coEvery { OpenSubtitlesApi.downloadFileBytes(any()) } returns
                "1\n00:00:01,000 --> 00:00:03,000\nHello.".toByteArray()

            val vm = createViewModel(
                PlaybackRequest(knight, listOf(file720))
            )
            vm.openSubtitles()
            advanceUntilIdle()
            vm.pickSubtitle(result)
            advanceUntilIdle()
            assertTrue(fake.subtitleSet)
            assertTrue(fake.attachedSubtitle!!.url.endsWith("knight.srt"))

            vm.setSubtitleOffsetMs(500)
            advanceUntilIdle()

            val shiftedUrl = fake.attachedSubtitle!!.url
            assertTrue(shiftedUrl.contains("syncp500"))
            val shiftedFile = File(java.net.URI(shiftedUrl))
            assertTrue(shiftedFile.isFile)
            assertTrue(
                shiftedFile.readText()
                    .contains("00:00:01,500 --> 00:00:03,500")
            )
            // The original cached file keeps its true timings.
            assertTrue(
                File(subtitleDir, "knight.srt").readText()
                    .contains("00:00:01,000 --> 00:00:03,000")
            )
            assertEquals(500L, vm.uiState.value.subtitleOffsetMs)
            assertEquals(500L, playback.getSubtitleOffsetMs(knight.stableKey))

            // Back to zero re-attaches the original file.
            vm.setSubtitleOffsetMs(0)
            assertTrue(fake.attachedSubtitle!!.url.endsWith("knight.srt"))
        }

    @Test
    fun `offset is clamped to the spec range`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.setSubtitleOffsetMs(99_000)
        assertEquals(5_000L, vm.uiState.value.subtitleOffsetMs)
        vm.setSubtitleOffsetMs(-99_000)
        assertEquals(-5_000L, vm.uiState.value.subtitleOffsetMs)
    }

    // ---- Next from system controls (§5.3) ----

    @Test
    fun `hasNext reflects the queue and skipToNext starts it at once`() =
        runTest(dispatcher) {
            val episode2 = Title(
                archiveId = "series-ep2",
                name = "Episode 2",
                mediaType = MediaType.TV
            )
            val vm = createViewModel(
                request = PlaybackRequest(
                    title = film,
                    files = listOf(file720),
                    upNext = listOf(UpNextItem(episode2))
                ),
                resolver = { title ->
                    if (title.archiveId == "series-ep2") {
                        ResolvedPlayable(files = listOf(file360))
                    } else {
                        ResolvedPlayable.NOT_PLAYABLE
                    }
                }
            )
            advanceUntilIdle()
            assertTrue(vm.uiState.value.hasNext)
            assertEquals(1, vm.uiState.value.upNextQueueSize)

            vm.skipToNext()
            advanceUntilIdle()

            // Started immediately — no countdown offer was shown.
            assertNull(vm.uiState.value.upNext)
            assertEquals(episode2, vm.uiState.value.title)
            assertEquals(file360.url, fake.sources.last())
            assertEquals(false, vm.uiState.value.hasNext)
            assertNotNull(fake.nowPlayingTitle)
        }

    @Test
    fun `no queue means no next`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.hasNext)
        vm.skipToNext()
        advanceUntilIdle()
        assertEquals(film, vm.uiState.value.title)
    }
}
