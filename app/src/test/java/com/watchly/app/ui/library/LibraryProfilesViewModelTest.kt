package com.watchly.app.ui.library

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.downloads.DownloadEngine
import com.watchly.app.data.downloads.DownloadRules
import com.watchly.app.data.downloads.DownloadSettingsRepository
import com.watchly.app.data.downloads.DownloadStateStore
import com.watchly.app.data.downloads.DownloadWorkScheduler
import com.watchly.app.data.downloads.DownloadsRepository
import com.watchly.app.data.downloads.EngineDownloadState
import com.watchly.app.data.library.ListsRepository
import com.watchly.app.data.library.ProfilesRepository
import com.watchly.app.data.library.WatchlistRepository
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
 * Library profile actions (cycle 12, §8): the state exposes the
 * profiles + the active one; create adds without switching;
 * switch reports true ONLY for a real switch (the screen resets
 * navigation on it, so a false positive would bounce the user
 * for nothing); deleting the active profile reports true the
 * same way, deleting another refreshes in place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryProfilesViewModelTest {

    private class FakeEngine : DownloadEngine {
        override fun enqueue(url: String, fileName: String, title: String): Long = 7L
        override fun query(downloadId: Long): EngineDownloadState =
            EngineDownloadState(DownloadRules.CODE_PENDING, 0, -1, 0)

        override fun remove(downloadId: Long) = Unit
        override fun localPathFor(fileName: String): String = "/tmp/$fileName"
    }

    private class FakeWorkScheduler : DownloadWorkScheduler {
        override fun start(
            record: com.watchly.app.data.downloads.DownloadRecord
        ) = Unit

        override fun stop(recordId: String) = Unit
    }

    private lateinit var profiles: ProfilesRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        listOf(
            ProfilesRepository.PREFS_NAME,
            WatchlistRepository.PREFS_NAME,
            PlaybackRepository.PREFS_NAME,
            DownloadsRepository.PREFS_NAME,
            TmdbKeyRepository.PREFS_NAME,
            DownloadSettingsRepository.PREFS_NAME
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
        profiles = ProfilesRepository(context)
    }

    private fun viewModel(): LibraryViewModel {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return LibraryViewModel(
            watchlist = WatchlistRepository(context),
            playback = PlaybackRepository(context),
            downloads = DownloadsRepository(
                context,
                FakeEngine(),
                workScheduler = FakeWorkScheduler(),
                stateStore = DownloadStateStore(context)
            ),
            tmdbKeys = TmdbKeyRepository(context),
            userLists = ListsRepository(context),
            profilesRepo = profiles
        )
    }

    @Test
    fun `state shows Profile 1 as the only and active profile`() {
        val state = viewModel().uiState.value
        assertEquals(1, state.profiles.size)
        assertEquals("Profile 1", state.activeProfile?.name)
    }

    @Test
    fun `create adds a profile without switching to it`() {
        val vm = viewModel()
        vm.createProfile("Kids", 2)
        val state = vm.uiState.value
        assertEquals(2, state.profiles.size)
        assertEquals("Profile 1", state.activeProfile?.name)
        assertEquals(
            ProfilesRepository.DEFAULT_PROFILE_ID,
            profiles.activeProfileId()
        )
    }

    @Test
    fun `switch reports true only for a real switch`() {
        val vm = viewModel()
        vm.createProfile("Kids", 2)
        val kids = profiles.profiles().first { it.name == "Kids" }

        assertFalse(vm.switchProfile(ProfilesRepository.DEFAULT_PROFILE_ID))
        assertTrue(vm.switchProfile(kids.id))
        assertEquals(kids.id, profiles.activeProfileId())
        assertFalse(vm.switchProfile("missing"))
    }

    @Test
    fun `deleting the active profile reports the context change, deleting another does not`() {
        val vm = viewModel()
        vm.createProfile("Kids", 2)
        vm.createProfile("Guest", 4)
        val kids = profiles.profiles().first { it.name == "Kids" }
        val guest = profiles.profiles().first { it.name == "Guest" }

        // Deleting a non-active profile: no context change, the
        // state simply refreshes without it.
        assertFalse(vm.deleteProfile(guest.id))
        assertEquals(
            listOf("Profile 1", "Kids"),
            vm.uiState.value.profiles.map { it.name }
        )

        // Deleting the ACTIVE profile: context change (the
        // repository falls back to the first remaining profile).
        assertTrue(vm.switchProfile(kids.id))
        assertTrue(vm.deleteProfile(kids.id))
        assertEquals(
            ProfilesRepository.DEFAULT_PROFILE_ID,
            profiles.activeProfileId()
        )
    }
}
