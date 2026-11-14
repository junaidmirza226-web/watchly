package com.watchly.app.ui.library

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.downloads.DownloadEngine
import com.watchly.app.data.downloads.DownloadRules
import com.watchly.app.data.downloads.DownloadsRepository
import com.watchly.app.data.downloads.EngineDownloadState
import com.watchly.app.data.library.ListsRepository
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

    /** Cycle 5: records WorkManager scheduling without WorkManager. */
    private class FakeWorkScheduler :
        com.watchly.app.data.downloads.DownloadWorkScheduler {
        override fun start(
            record: com.watchly.app.data.downloads.DownloadRecord
        ) = Unit

        override fun stop(recordId: String) = Unit
    }

    private lateinit var watchlist: WatchlistRepository
    private lateinit var playback: PlaybackRepository
    private lateinit var downloads: DownloadsRepository
    private lateinit var keyRepo: TmdbKeyRepository
    private lateinit var userLists: ListsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        listOf(
            WatchlistRepository.PREFS_NAME,
            PlaybackRepository.PREFS_NAME,
            DownloadsRepository.PREFS_NAME,
            TmdbKeyRepository.PREFS_NAME,
            com.watchly.app.data.downloads.DownloadSettingsRepository.PREFS_NAME
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
        watchlist = WatchlistRepository(context)
        playback = PlaybackRepository(context)
        downloads = DownloadsRepository(
            context,
            FakeEngine(),
            workScheduler = FakeWorkScheduler(),
            stateStore = com.watchly.app.data.downloads.DownloadStateStore(context)
        )
        keyRepo = TmdbKeyRepository(context)
        userLists = ListsRepository(context)
    }

    private fun viewModel() =
        LibraryViewModel(watchlist, playback, downloads, keyRepo, userLists)

    @Test
    fun `empty library shows no key and empty sections`() {
        val state = viewModel().uiState.value
        assertFalse(state.hasTmdbKey)
        assertTrue(state.watchlist.isEmpty())
        assertTrue(state.continueWatching.isEmpty())
        assertTrue(state.downloads.isEmpty())
        assertTrue(state.lists.isEmpty())
    }

    @Test
    fun `sections keep identity across refresh - watchlist is not a continue watching entry`() {
        // Cycle 8 (eat-your-own-dogfood for the emulator failure
        // chain in DetailsNavigationTest): the sections are keyed by
        // disjoint stable identities — Continue Watching entries by
        // PlaybackProgress.key, Watchlist cards by Title.stableKey —
        // and refresh() rebuilds all sections atomically from the
        // repos. If a refresh ever mixed the two section models,
        // this fails deterministically here instead of as a flaky
        // emulator tree anomaly.
        val context = ApplicationProvider.getApplicationContext<Application>()
        val film = Title(
            tmdbId = 777,
            name = "Identity Film",
            mediaType = MediaType.MOVIE
        )
        watchlist.toggleWatchlist(film)
        playback.saveProgress(
            PlaybackProgress(
                key = film.stableKey,
                titleName = film.name,
                posterUrl = null,
                mediaType = MediaType.MOVIE,
                episodeLabel = null,
                positionMs = 30_000,
                durationMs = 3_600_000,
                updatedAtMs = 1_000
            )
        )
        val vm = viewModel()
        vm.refresh()
        val state = vm.uiState.value
        assertEquals(listOf("Identity Film"), state.watchlist.map { it.name })
        assertEquals(1, state.continueWatching.size)
        assertEquals(film.stableKey, state.continueWatching[0].key)
    }

    @Test
    fun `download settings show defaults and changes persist`() {
        // Cycle 8: the §7.3 download rules had persisted + enforced
        // since cycle 2 but had no UI. The Library state now carries
        // them, and the setters write through to the same store the
        // download scheduler reads.
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settingsRepo =
            com.watchly.app.data.downloads.DownloadSettingsRepository(context)
        val vm = LibraryViewModel(
            watchlist, playback, downloads, keyRepo, userLists,
            downloadSettingsRepo = settingsRepo
        )

        val initial = vm.uiState.value.downloadSettings
        assertTrue(initial.wifiOnly)
        assertTrue(initial.pauseOnLowBattery)
        assertEquals(
            com.watchly.app.data.downloads.DownloadSettings
                .DEFAULT_STORAGE_CAP_BYTES,
            initial.storageCapBytes
        )

        vm.setDownloadWifiOnly(false)
        vm.setDownloadPauseOnLowBattery(false)
        vm.setDownloadStorageCap(5_000_000_000L)

        val updated = vm.uiState.value.downloadSettings
        assertFalse(updated.wifiOnly)
        assertFalse(updated.pauseOnLowBattery)
        assertEquals(5_000_000_000L, updated.storageCapBytes)
        // Persisted: a fresh read of the store agrees with the state.
        assertEquals(updated, settingsRepo.settings())
    }

    @Test
    fun `data saver toggle shows in state and persists`() {
        // Cycle 10 (§14.1): the Library state carries the toggle,
        // and the setter writes through to the same preference the
        // image interceptor and the download path read.
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(
            com.watchly.app.data.settings.UiPreferencesRepository.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
        val prefs = com.watchly.app.data.settings.UiPreferencesRepository(context)
        val vm = LibraryViewModel(
            watchlist, playback, downloads, keyRepo, userLists,
            uiPrefs = prefs
        )

        assertFalse(vm.uiState.value.dataSaver)
        vm.setDataSaver(true)
        assertTrue(vm.uiState.value.dataSaver)
        assertTrue(prefs.dataSaverEnabled())
        vm.setDataSaver(false)
        assertFalse(vm.uiState.value.dataSaver)
        assertFalse(prefs.dataSaverEnabled())
    }

    @Test
    fun `user lists appear in the library state`() {
        val weekend = userLists.createList("Weekend")
        userLists.addTitle(weekend.id, Title(
            archiveId = "film1",
            name = "Some Film",
            mediaType = MediaType.MOVIE,
            year = "1970"
        ))
        userLists.createList("Best Anime")

        val state = viewModel().uiState.value
        assertEquals(listOf("Weekend", "Best Anime"), state.lists.map { it.name })
        assertEquals(1, state.lists[0].size)
        assertEquals("Some Film", state.lists[0].titles[0].name)
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
