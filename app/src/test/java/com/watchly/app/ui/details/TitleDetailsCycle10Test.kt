package com.watchly.app.ui.details
import com.watchly.app.data.settings.TestKeys

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.downloads.DownloadEngine
import com.watchly.app.data.downloads.DownloadEnvironment
import com.watchly.app.data.downloads.DownloadEnvironmentProvider
import com.watchly.app.data.downloads.DownloadRecord
import com.watchly.app.data.downloads.DownloadRules
import com.watchly.app.data.downloads.DownloadSettingsRepository
import com.watchly.app.data.downloads.DownloadStateStore
import com.watchly.app.data.downloads.DownloadWorkScheduler
import com.watchly.app.data.downloads.DownloadsRepository
import com.watchly.app.data.downloads.EngineDownloadState
import com.watchly.app.data.library.RatingsRepository
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.Title
import com.watchly.app.data.model.WatchProviders
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.TmdbApi
import com.watchly.app.data.remote.TvMazeApi
import com.watchly.app.data.settings.TmdbKeyRepository
import com.watchly.app.data.settings.UiPreferencesRepository
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Cycle 10 details depth: the official-trailer state, "More like
 * this", the personal rating, and the Data Saver download default.
 * Same harness as TitleDetailsTmdbDownloadsTest (scripted phone
 * environment, fake scheduler — see that file for the rationale).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TitleDetailsCycle10Test {

    private class FakeEngine : DownloadEngine {
        override fun enqueue(url: String, fileName: String, notificationTitle: String): Long = 42L
        override fun query(downloadId: Long): EngineDownloadState =
            EngineDownloadState(DownloadRules.CODE_RUNNING, 10, 100, 0)
        override fun remove(downloadId: Long) = Unit
        override fun localPathFor(fileName: String): String = "/tmp/$fileName"
    }

    private class FakeScheduler : DownloadWorkScheduler {
        var startCount = 0
        val startedRecords = mutableListOf<DownloadRecord>()
        override fun start(record: DownloadRecord) {
            startCount++
            startedRecords.add(record)
        }
        override fun stop(recordId: String) = Unit
    }

    private val dispatcher = StandardTestDispatcher()
    private lateinit var library: WatchlistRepository
    private lateinit var keyRepo: TmdbKeyRepository
    private lateinit var ratings: RatingsRepository
    private lateinit var uiPrefs: UiPreferencesRepository
    private lateinit var scheduler: FakeScheduler
    private lateinit var downloads: DownloadsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        listOf(
            WatchlistRepository.PREFS_NAME,
            TmdbKeyRepository.PREFS_NAME,
            DownloadsRepository.PREFS_NAME,
            DownloadSettingsRepository.PREFS_NAME,
            RatingsRepository.PREFS_NAME,
            UiPreferencesRepository.PREFS_NAME
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
        library = WatchlistRepository(context)
        keyRepo = TmdbKeyRepository(context)
        ratings = RatingsRepository(context)
        uiPrefs = UiPreferencesRepository(context)
        scheduler = FakeScheduler()
        downloads = DownloadsRepository(
            context,
            FakeEngine(),
            DownloadSettingsRepository(context),
            object : DownloadEnvironmentProvider {
                override fun current() = DownloadEnvironment(
                    network = com.watchly.app.data.downloads.NetworkKind.WIFI,
                    batteryPercent = 80,
                    batteryCharging = false,
                    freeBytes = 50_000_000_000L
                )
            },
            scheduler,
            DownloadStateStore(context)
        )
        mockkObject(TvMazeApi)
        mockkObject(ArchiveApi)
        mockkObject(TmdbApi)
        TestKeys.clearEmbeddedDefaults()
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Stubs the always-called TMDB endpoints for one movie. */
    private fun stubTmdbMovie(tmdbId: Int, stub: Title) {
        coEvery { TmdbApi.fetchMovieDetails("user-key", tmdbId) } returns stub
        coEvery { TmdbApi.fetchMovieCast("user-key", tmdbId) } returns emptyList()
        coEvery {
            TmdbApi.fetchWatchProviders("user-key", MediaType.MOVIE, tmdbId, any())
        } returns WatchProviders("US", null, emptyList())
    }

    @Test
    fun `trailer and recommendations land in state when tmdb answers`() =
        runTest(dispatcher) {
            keyRepo.setKey("user-key")
            val stub = Title(
                tmdbId = 579974, name = "RRR", mediaType = MediaType.MOVIE,
                year = "2022"
            )
            stubTmdbMovie(579974, stub)
            coEvery {
                TmdbApi.fetchTrailerKey("user-key", MediaType.MOVIE, 579974)
            } returns "dQw4w9WgXcQ"
            coEvery {
                TmdbApi.fetchRecommendations("user-key", MediaType.MOVIE, 579974, any())
            } returns listOf(
                Title(tmdbId = 201, name = "Similar Film", mediaType = MediaType.MOVIE),
                Title(tmdbId = 202, name = "Another Film", mediaType = MediaType.MOVIE)
            )

            val viewModel = TitleDetailsViewModel(
                stub, library, keyRepo, downloads,
                ratings = ratings, uiPrefs = uiPrefs
            )
            advanceUntilIdle(); idle()

            val state = viewModel.uiState.value
            assertEquals("dQw4w9WgXcQ", state.trailerYoutubeId)
            assertEquals(
                listOf("Similar Film", "Another Film"),
                state.recommendations.map { it.name }
            )
        }

    @Test
    fun `no trailer and no recommendations when tmdb has none`() =
        runTest(dispatcher) {
            keyRepo.setKey("user-key")
            val stub = Title(
                tmdbId = 42, name = "Obscure", mediaType = MediaType.MOVIE
            )
            stubTmdbMovie(42, stub)
            coEvery {
                TmdbApi.fetchTrailerKey("user-key", MediaType.MOVIE, 42)
            } returns null
            coEvery {
                TmdbApi.fetchRecommendations("user-key", MediaType.MOVIE, 42, any())
            } returns emptyList()

            val viewModel = TitleDetailsViewModel(
                stub, library, keyRepo, downloads,
                ratings = ratings, uiPrefs = uiPrefs
            )
            advanceUntilIdle(); idle()

            // Honest absence: null trailer (no button) and an empty
            // row (no section) — and no error on the page.
            assertNull(viewModel.uiState.value.trailerYoutubeId)
            assertTrue(viewModel.uiState.value.recommendations.isEmpty())
            assertNull(viewModel.uiState.value.errorMessage)
        }

    @Test
    fun `without a tmdb key the extras stay absent`() = runTest(dispatcher) {
        val stub = Title(
            tmdbId = 42, name = "Obscure", mediaType = MediaType.MOVIE
        )
        val viewModel = TitleDetailsViewModel(
            stub, library, keyRepo, downloads,
            ratings = ratings, uiPrefs = uiPrefs
        )
        advanceUntilIdle(); idle()
        assertNull(viewModel.uiState.value.trailerYoutubeId)
        assertTrue(viewModel.uiState.value.recommendations.isEmpty())
    }

    @Test
    fun `personal rating previews, commits, persists and clears`() =
        runTest(dispatcher) {
            val stub = Title(
                tmdbId = 7, name = "Rate Me", mediaType = MediaType.MOVIE
            )
            val viewModel = TitleDetailsViewModel(
                stub, library, keyRepo, downloads,
                ratings = ratings, uiPrefs = uiPrefs
            )
            advanceUntilIdle(); idle()
            assertNull(viewModel.uiState.value.personalRating)

            // Dragging previews only — the store is untouched until
            // the slider settles.
            viewModel.onPersonalRatingPreview(8)
            assertEquals(8, viewModel.uiState.value.personalRating)
            assertNull(ratings.ratingFor(stub.stableKey))

            viewModel.onPersonalRatingCommitted()
            assertEquals(8, ratings.ratingFor(stub.stableKey))

            // A fresh ViewModel (a later visit) shows the rating.
            val revisited = TitleDetailsViewModel(
                stub, library, keyRepo, downloads,
                ratings = ratings, uiPrefs = uiPrefs
            )
            advanceUntilIdle(); idle()
            assertEquals(8, revisited.uiState.value.personalRating)

            // Back to 0 = not rated, persisted as a clear.
            revisited.onPersonalRatingPreview(0)
            revisited.onPersonalRatingCommitted()
            assertNull(revisited.uiState.value.personalRating)
            assertNull(ratings.ratingFor(stub.stableKey))
        }

    @Test
    fun `data saver download starts the efficient file without a picker`() =
        runTest(dispatcher) {
            val film = Title(
                archiveId = "somefilm_2020",
                name = "Some Film",
                mediaType = MediaType.MOVIE
            )
            // ArchiveApi's contract: efficient-first order.
            val efficient = PlayableFile(
                url = "https://archive.org/download/x/efficient.mp4",
                fileName = "efficient.mp4",
                format = "h.264",
                sizeBytes = 300L,
                qualityLabel = "h.264 SD"
            )
            val big = PlayableFile(
                url = "https://archive.org/download/x/big.mp4",
                fileName = "big.mp4",
                format = "h.264",
                sizeBytes = 900L,
                qualityLabel = "h.264 HD"
            )
            coEvery { ArchiveApi.fetchPlayableFiles(any()) } returns
                listOf(efficient, big)
            uiPrefs.setDataSaverEnabled(true)

            val viewModel = TitleDetailsViewModel(
                film, library, keyRepo, downloads,
                ratings = ratings, uiPrefs = uiPrefs
            )
            advanceUntilIdle(); idle()

            viewModel.onDownloadRequested()
            // No picker, exactly one start, and it is the efficient
            // (first) file — with the note in the confirmation.
            assertNull(viewModel.uiState.value.qualityChoices)
            assertEquals(1, scheduler.startCount)
            assertEquals("efficient.mp4", scheduler.startedRecords[0].fileName)
            assertTrue(
                viewModel.uiState.value.infoMessage!!.contains("Data Saver")
            )
        }

    @Test
    fun `without data saver the quality picker still opens`() =
        runTest(dispatcher) {
            val film = Title(
                archiveId = "somefilm_2020",
                name = "Some Film",
                mediaType = MediaType.MOVIE
            )
            val efficient = PlayableFile(
                url = "https://archive.org/download/x/efficient.mp4",
                fileName = "efficient.mp4",
                format = "h.264",
                sizeBytes = 300L,
                qualityLabel = "h.264 SD"
            )
            val big = PlayableFile(
                url = "https://archive.org/download/x/big.mp4",
                fileName = "big.mp4",
                format = "h.264",
                sizeBytes = 900L,
                qualityLabel = "h.264 HD"
            )
            coEvery { ArchiveApi.fetchPlayableFiles(any()) } returns
                listOf(efficient, big)

            val viewModel = TitleDetailsViewModel(
                film, library, keyRepo, downloads,
                ratings = ratings, uiPrefs = uiPrefs
            )
            advanceUntilIdle(); idle()

            viewModel.onDownloadRequested()
            assertEquals(0, scheduler.startCount)
            assertEquals(2, viewModel.uiState.value.qualityChoices!!.size)
        }
}
