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

    private val dispatcher = StandardTestDispatcher()
    private lateinit var library: WatchlistRepository
    private lateinit var keyRepo: TmdbKeyRepository
    private lateinit var engine: FakeEngine
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
        library = WatchlistRepository(context)
        keyRepo = TmdbKeyRepository(context)
        engine = FakeEngine()
        downloads = DownloadsRepository(context, engine)
        mockkObject(TvMazeApi)
        mockkObject(ArchiveApi)
        mockkObject(TmdbApi)
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

            val viewModel = TitleDetailsViewModel(stub, library, keyRepo, downloads)
            advanceUntilIdle(); idle()

            val state = viewModel.uiState.value
            assertEquals(187, state.title.runtimeMinutes)
            assertEquals(listOf("Action", "Drama"), state.title.genres)
            assertEquals("tt8178634", state.title.imdbId)
            assertEquals(listOf("Ram Charan"), state.cast.map { it.personName })
            assertEquals("TMDB", state.ratingSourceLabel)
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

            assertEquals(1, engine.enqueueCount)
            assertEquals(1, downloads.getDownloads().size)
            val state = viewModel.uiState.value
            // Freshly enqueued: QUEUED is the honest starting state.
            assertEquals(DownloadStatus.QUEUED, state.downloadStatus)
            assertTrue(state.infoMessage!!.contains("Download started"))
            assertEquals("Downloading…", state.downloadButtonLabel)
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

            assertEquals(0, engine.enqueueCount)
            val message = viewModel.uiState.value.infoMessage!!
            assertTrue(message.contains("catalogue only"))
            assertTrue(message.contains("no file to save"))
        }
}
