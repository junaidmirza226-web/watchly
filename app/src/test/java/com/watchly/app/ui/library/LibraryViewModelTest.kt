package com.watchly.app.ui.library

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.downloads.DownloadEngine
import com.watchly.app.data.downloads.DownloadRules
import com.watchly.app.data.downloads.DownloadsRepository
import com.watchly.app.data.downloads.EngineDownloadState
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.Title
import com.watchly.app.data.playback.PlaybackProgress
import com.watchly.app.data.playback.PlaybackRepository
import com.watchly.app.data.settings.TmdbKeyRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * My Library reads only local stores: TMDB key state, watchlist,
 * continue watching and downloads must all show up, and deleting a
 * download must remove it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryViewModelTest {

    private class FakeEngine : DownloadEngine {
        override fun enqueue(url: String, fileName: String, title: String): Long = 7L
        override fun query(downloadId: Long): EngineDownloadState =
            EngineDownloadState(DownloadRules.CODE_PENDING, 0, -1, 0)

        override fun remove(downloadId: Long) = Unit
        override fun localPathFor(fileName: String): String = "/tmp/$fileName"
    }

    private lateinit var watchlist: WatchlistRepository
    private lateinit var playback: PlaybackRepository
    private lateinit var downloads: DownloadsRepository
    private lateinit var keyRepo: TmdbKeyRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        listOf(
            WatchlistRepository.PREFS_NAME,
            PlaybackRepository.PREFS_NAME,
            DownloadsRepository.PREFS_NAME,
            TmdbKeyRepository.PREFS_NAME
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
        watchlist = WatchlistRepository(context)
        playback = PlaybackRepository(context)
        downloads = DownloadsRepository(context, FakeEngine())
        keyRepo = TmdbKeyRepository(context)
    }

    private fun viewModel() = LibraryViewModel(watchlist, playback, downloads, keyRepo)

    @Test
    fun `empty library shows no key and empty sections`() {
        val state = viewModel().uiState.value
        assertFalse(state.hasTmdbKey)
        assertTrue(state.watchlist.isEmpty())
        assertTrue(state.continueWatching.isEmpty())
        assertTrue(state.downloads.isEmpty())
    }

    @Test
    fun `sections reflect what the stores hold`() {
        val saved = Title(
            tvmazeId = 169,
            name = "Breaking Bad",
            mediaType = MediaType.TV,
            year = "2008"
        )
        watchlist.toggleWatchlist(saved)
        playback.saveProgress(
            PlaybackProgress(
                key = "archive:film1",
                titleName = "Some Film",
                posterUrl = null,
                mediaType = MediaType.MOVIE,
                episodeLabel = null,
                positionMs = 60_000,
                durationMs = 600_000,
                updatedAtMs = 123L
            )
        )
        val film = Title(
            archiveId = "film1",
            name = "Some Film",
            mediaType = MediaType.MOVIE
        )
        downloads.startDownload(
            film,
            PlayableFile("https://archive.org/download/film1/f.mp4", "f.mp4")
        )
        keyRepo.setKey("user-key")

        val state = viewModel().uiState.value
        assertTrue(state.hasTmdbKey)
        assertEquals(listOf("Breaking Bad"), state.watchlist.map { it.name })
        assertEquals(1, state.continueWatching.size)
        assertEquals(1, state.downloads.size)
        assertTrue(state.hasActiveDownloads)
    }

    @Test
    fun `delete download removes it from state`() {
        val film = Title(
            archiveId = "film1",
            name = "Some Film",
            mediaType = MediaType.MOVIE
        )
        downloads.startDownload(
            film,
            PlayableFile("https://archive.org/download/film1/f.mp4", "f.mp4")
        )
        val vm = viewModel()
        assertEquals(1, vm.uiState.value.downloads.size)

        vm.deleteDownload(vm.uiState.value.downloads[0])

        assertTrue(vm.uiState.value.downloads.isEmpty())
    }

    @Test
    fun `title reconstruction from stable keys restores provider ids`() {
        val tmdb = titleFromStableKey("tmdb:579974", "RRR", null, MediaType.MOVIE)
        assertEquals(579974, tmdb.tmdbId)
        val archive = titleFromStableKey("archive:film1", "F", null, MediaType.MOVIE)
        assertEquals("film1", archive.archiveId)
        val plain = titleFromStableKey("name:MOVIE:x", "X", null, MediaType.MOVIE)
        assertEquals("X", plain.name)
    }
}
