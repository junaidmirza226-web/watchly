package com.watchly.app.ui.details

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.downloads.DownloadRules
import com.watchly.app.data.downloads.DownloadStatus
import com.watchly.app.data.downloads.DownloadEngine
import com.watchly.app.data.downloads.DownloadsRepository
import com.watchly.app.data.downloads.EngineDownloadState
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.model.CastMember
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.Title
import com.watchly.app.data.model.WatchProviders
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.TmdbApi
import com.watchly.app.data.remote.TvMazeApi
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Title Details additions: TMDB titles enrich from TMDB when the
 * user's key is saved, and the Download button starts a real
 * download for playable titles while explaining honestly why
 * catalogue-only titles can't be downloaded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TitleDetailsTmdbDownloadsTest {

    private class FakeEngine : DownloadEngine {
        var enqueueCount = 0
        override fun enqueue(url: String, fileName: String, notificationTitle: String): Long {
            enqueueCount++
            return 42L
        }

        override fun query(downloadId: Long): EngineDownloadState =
            EngineDownloadState(DownloadRules.CODE_RUNNING, 10, 100, 0)

        override fun remove(downloadId: Long) = Unit

        override fun localPathFor(fileName: String): String = "/tmp/$fileName"
    }

    /**
     * Cycle 5: NEW downloads start through the WorkManager
     * scheduler (Watchly's own engine), not the legacy
     * DownloadManager-era [FakeEngine] — the start signal to count
     * is the scheduler's, and [startedRecords] shows what was asked.
     */
    private class FakeScheduler :
        com.watchly.app.data.downloads.DownloadWorkScheduler {
        var startCount = 0
        val startedRecords =
            mutableListOf<com.watchly.app.data.downloads.DownloadRecord>()

        override fun start(
            record: com.watchly.app.data.downloads.DownloadRecord
        ) {
            startCount++
            startedRecords.add(record)
        }

        override fun stop(recordId: String) = Unit
    }

    private val dispatcher = StandardTestDispatcher()
    private lateinit var library: WatchlistRepository
    private lateinit var keyRepo: TmdbKeyRepository
    private lateinit var engine: FakeEngine
    private lateinit var scheduler: FakeScheduler
    private lateinit var downloads: DownloadsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        listOf(
            WatchlistRepository.PREFS_NAME,
            TmdbKeyRepository.PREFS_NAME,
            DownloadsRepository.PREFS_NAME
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
        // The §7.3-checked download start reads live phone state
        // through the DownloadEnvironmentProvider seam; Robolectric's
        // shadow phone reports cellular + a full disk, which the
        // rules would (correctly) refuse. Script a normal phone
        // through the seam instead (the policy itself is unit-tested
        // separately; DownloadsRepositoryTest covers refusals).
        context.getSharedPreferences(
            com.watchly.app.data.downloads.DownloadSettingsRepository.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
        library = WatchlistRepository(context)
        keyRepo = TmdbKeyRepository(context)
        engine = FakeEngine()
        scheduler = FakeScheduler()
        downloads = DownloadsRepository(
            context,
            engine,
            com.watchly.app.data.downloads.DownloadSettingsRepository(context),
            object : com.watchly.app.data.downloads.DownloadEnvironmentProvider {
                override fun current() =
                    com.watchly.app.data.downloads.DownloadEnvironment(
                        network = com.watchly.app.data.downloads.NetworkKind.WIFI,
                        batteryPercent = 80,
                        batteryCharging = false,
                        freeBytes = 50_000_000_000L
                    )
            },
            // Cycle 5: never touch real WorkManager under Robolectric.
            scheduler,
            com.watchly.app.data.downloads.DownloadStateStore(context)
        )
        mockkObject(TvMazeApi)
        mockkObject(ArchiveApi)
        mockkObject(TmdbApi)
        // Cycle 10: details also asks TMDB for a trailer + "More like
        // this". Fail soft (none) so an unstubbed seam can never leak a
        // real network call into these tests.
        coEvery {
            TmdbApi.fetchTrailerKey(any(), any(), any())
        } returns null
        coEvery {
            TmdbApi.fetchRecommendations(any(), any(), any(), any())
        } returns emptyList()
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
    fun `tmdb title enriches details and cast from tmdb when key saved`() =
        runTest(dispatcher) {
            keyRepo.setKey("user-key")
            val stub = Title(
                tmdbId = 579974,
                name = "RRR",
                mediaType = MediaType.MOVIE,
                year = "2022"
            )
            coEvery { TmdbApi.fetchMovieDetails("user-key", 579974) } returns stub.copy(
                overview = "Full overview.",
                runtimeMinutes = 187,
                genres = listOf("Action", "Drama"),
                rating = 7.8,
                imdbId = "tt8178634"
            )
            coEvery { TmdbApi.fetchMovieCast("user-key", 579974) } returns listOf(
                CastMember("Ram Charan", "Alluri Sitarama Raju")
            )
            // Where-to-watch is fetched in the same load; TMDB lists
            // nothing for this title in the stubbed region answer.
            coEvery {
                TmdbApi.fetchWatchProviders("user-key", MediaType.MOVIE, 579974, any())
            } returns WatchProviders("US", null, emptyList())

            val viewModel = TitleDetailsViewModel(stub, library, keyRepo, downloads)
            advanceUntilIdle(); idle()

            val state = viewModel.uiState.value
            assertEquals(187, state.title.runtimeMinutes)
            assertEquals(listOf("Action", "Drama"), state.title.genres)
            assertEquals("tt8178634", state.title.imdbId)
            assertEquals(listOf("Ram Charan"), state.cast.map { it.personName })
            assertEquals("TMDB", state.ratingSourceLabel)
            // Asked, and honestly empty — not null (null = never asked).
            assertEquals("US", state.watchProviders?.region)
            assertTrue(state.watchProviders!!.providers.isEmpty())
        }

    @Test
    fun `tmdb title without a key stays as passed`() = runTest(dispatcher) {
        val stub = Title(
            tmdbId = 579974,
            name = "RRR",
            mediaType = MediaType.MOVIE,
            year = "2022"
        )

        val viewModel = TitleDetailsViewModel(stub, library, keyRepo, downloads)
        advanceUntilIdle(); idle()

        val state = viewModel.uiState.value
        assertEquals("RRR", state.title.name)
        assertEquals(null, state.title.runtimeMinutes)
        assertTrue(state.cast.isEmpty())
        // No key → where-to-watch is never asked, section stays absent.
        assertEquals(null, state.watchProviders)
    }

    @Test
    fun `download button starts a real download for an archive film`() =
        runTest(dispatcher) {
            val film = Title(
                archiveId = "nightofthelivingdead_201910",
                name = "Night of the Living Dead",
                mediaType = MediaType.MOVIE,
                year = "1968"
            )
            coEvery { ArchiveApi.fetchPlayableFiles(any()) } returns listOf(
                PlayableFile(
                    url = "https://archive.org/download/x/film.mp4",
                    fileName = "film.mp4",
                    format = "MPEG4",
                    sizeBytes = 1_000L
                )
            )

            val viewModel = TitleDetailsViewModel(film, library, keyRepo, downloads)
            advanceUntilIdle(); idle()
            assertTrue(viewModel.uiState.value.hasPlayableSource)

            viewModel.onDownloadRequested()

            assertEquals(1, scheduler.startCount)
            assertEquals(1, downloads.getDownloads().size)
            val state = viewModel.uiState.value
            // Freshly enqueued: QUEUED is the honest starting state.
            assertEquals(DownloadStatus.QUEUED, state.downloadStatus)
            assertTrue(state.infoMessage!!.contains("Download started"))
            assertEquals("Downloading…", state.downloadButtonLabel)
        }

    @Test
    fun `download with two qualities opens the picker and starts the picked file`() =
        runTest(dispatcher) {
            val film = Title(
                archiveId = "nightofthelivingdead_201910",
                name = "Night of the Living Dead",
                mediaType = MediaType.MOVIE,
                year = "1968"
            )
            val hd = PlayableFile(
                url = "https://archive.org/download/x/hd.mp4",
                fileName = "hd.mp4",
                format = "h.264",
                sizeBytes = 900L,
                qualityLabel = "h.264 HD"
            )
            val sd = PlayableFile(
                url = "https://archive.org/download/x/sd.mp4",
                fileName = "sd.mp4",
                format = "MPEG4",
                sizeBytes = 300L,
                qualityLabel = "512Kb MPEG4"
            )
            coEvery { ArchiveApi.fetchPlayableFiles(any()) } returns listOf(sd, hd)

            val viewModel = TitleDetailsViewModel(film, library, keyRepo, downloads)
            advanceUntilIdle(); idle()

            // Tapping Download must NOT start anything yet — the
            // user picks a quality first (spec §7.1), best first.
            viewModel.onDownloadRequested()
            assertEquals(0, scheduler.startCount)
            assertEquals(
                listOf("hd.mp4", "sd.mp4"),
                viewModel.uiState.value.qualityChoices!!.map { it.fileName }
            )

            // Picking the smaller file starts exactly that download.
            viewModel.onQualityChosen(sd)
            assertEquals(1, scheduler.startCount)
            assertEquals(null, viewModel.uiState.value.qualityChoices)
            val record = downloads.recordForTitle(film.stableKey)!!
            assertEquals("sd.mp4", record.fileName)
            assertEquals("512Kb MPEG4", record.qualityLabel)
            assertTrue(
                viewModel.uiState.value.infoMessage!!.contains("Download started")
            )
        }

    @Test
    fun `dismissing the quality picker starts nothing`() = runTest(dispatcher) {
        val film = Title(
            archiveId = "nightofthelivingdead_201910",
            name = "Night of the Living Dead",
            mediaType = MediaType.MOVIE,
            year = "1968"
        )
        coEvery { ArchiveApi.fetchPlayableFiles(any()) } returns listOf(
            PlayableFile(
                url = "https://archive.org/download/x/a.mp4",
                fileName = "a.mp4", format = "h.264", sizeBytes = 10L
            ),
            PlayableFile(
                url = "https://archive.org/download/x/b.mp4",
                fileName = "b.mp4", format = "MPEG4", sizeBytes = 5L
            )
        )

        val viewModel = TitleDetailsViewModel(film, library, keyRepo, downloads)
        advanceUntilIdle(); idle()

        viewModel.onDownloadRequested()
        assertTrue(viewModel.uiState.value.qualityChoices != null)

        viewModel.onQualityPickerDismissed()
        assertEquals(null, viewModel.uiState.value.qualityChoices)
        assertEquals(0, scheduler.startCount)
        assertTrue(downloads.getDownloads().isEmpty())
    }

    @Test
    fun `download on a catalogue only title explains why honestly`() =
        runTest(dispatcher) {
            val show = Title(
                tvmazeId = 169,
                name = "Breaking Bad",
                mediaType = MediaType.TV,
                year = "2008"
            )
            coEvery { TvMazeApi.fetchShowDetails(any()) } returns show
            coEvery { TvMazeApi.fetchShowEpisodes(any()) } returns emptyList()
            coEvery { TvMazeApi.fetchShowCast(any()) } returns emptyList()

            val viewModel = TitleDetailsViewModel(show, library, keyRepo, downloads)
            advanceUntilIdle(); idle()

            viewModel.onDownloadRequested()

            assertEquals(0, scheduler.startCount)
            val message = viewModel.uiState.value.infoMessage!!
            assertTrue(message.contains("catalogue only"))
            assertTrue(message.contains("no file to save"))
        }
}
