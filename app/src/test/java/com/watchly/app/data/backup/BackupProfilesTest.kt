package com.watchly.app.data.backup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.downloads.DownloadSettingsRepository
import com.watchly.app.data.library.ListsRepository
import com.watchly.app.data.library.ProfilesRepository
import com.watchly.app.data.library.RatingsRepository
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.data.playback.PlaybackRepository
import com.watchly.app.data.settings.UiPreferencesRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Backup & export with profiles (cycle 12, §8 + §15.2). The
 * backup carries the profile list and EVERY profile's data
 * files; restoring on a "fresh install" (only Profile 1 exists
 * locally, none of the backup's other profiles) brings all of
 * them back with their data and the right active profile.
 * Pre-profile backups (cycle 10 format, no profiles store)
 * still restore — into Profile 1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupProfilesTest {

    private lateinit var context: Application
    private lateinit var backup: BackupRepository
    private lateinit var profiles: ProfilesRepository

    private val film = Title(
        tmdbId = 101, name = "Backup Film", mediaType = MediaType.MOVIE,
        year = "2020"
    )
    private val show = Title(
        tvmazeId = 202, name = "Backup Show", mediaType = MediaType.TV,
        year = "2021"
    )

    private val globalFiles = listOf(
        ProfilesRepository.PREFS_NAME,
        UiPreferencesRepository.PREFS_NAME,
        DownloadSettingsRepository.PREFS_NAME
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        backup = BackupRepository(context)
        profiles = ProfilesRepository(context)
        wipeEverything(emptyList())
    }

    /** Clears the metadata + globals + the given profiles' files. */
    private fun wipeEverything(profileIds: List<String>) {
        val names = ArrayList<String>(globalFiles)
        for (id in profileIds + ProfilesRepository.DEFAULT_PROFILE_ID) {
            val suffix = ProfilesRepository.fileSuffixFor(id)
            names += WatchlistRepository.PREFS_NAME + suffix
            names += PlaybackRepository.PREFS_NAME + suffix
            names += RatingsRepository.PREFS_NAME + suffix
        }
        names.distinct().forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    @Test
    fun `export carries every profile and a fresh install restore brings them all back`() {
        // Populate: default profile has the film; a second
        // profile ("Kids") has the show, its own list and rating;
        // Kids is the ACTIVE profile at export time.
        val defaultId = ProfilesRepository.DEFAULT_PROFILE_ID
        WatchlistRepository(context, defaultId).toggleWatchlist(film)
        RatingsRepository(context, defaultId).setRating(film.stableKey, 8)
        val kids = profiles.createProfile("Kids", 3)!!
        WatchlistRepository(context, kids.id).toggleWatchlist(show)
        val kidsList = ListsRepository(context, kids.id).createList("Cartoon time")
        ListsRepository(context, kids.id).addTitle(kidsList.id, show)
        RatingsRepository(context, kids.id).setRating(show.stableKey, 5)
        profiles.setActiveProfile(kids.id)

        val json = backup.buildBackupJson(exportedAtMs = 1000)
        // The file names the profiles store and Kids' own files.
        assertTrue(json.contains(ProfilesRepository.PREFS_NAME))
        assertTrue(
            json.contains(
                WatchlistRepository.PREFS_NAME + kids.fileSuffix
            )
        )
        assertTrue(json.contains("Kids"))

        // Fresh install: metadata gone, every data file wiped.
        wipeEverything(listOf(kids.id))
        assertEquals(1, profiles.profiles().size)
        assertFalse(WatchlistRepository(context, kids.id).isInWatchlist(show))

        val result = backup.restoreFromJson(json)
        assertTrue(result.storesRestored >= 4)

        // Both profiles are back, with names, data and the
        // exported active profile.
        val restored = profiles.profiles()
        assertEquals(2, restored.size)
        assertEquals("Kids", restored.first { it.id == kids.id }.name)
        assertEquals(kids.id, profiles.activeProfileId())
        assertTrue(WatchlistRepository(context, defaultId).isInWatchlist(film))
        assertEquals(8, RatingsRepository(context, defaultId).ratingFor(film.stableKey))
        assertTrue(WatchlistRepository(context, kids.id).isInWatchlist(show))
        assertEquals(
            listOf("Cartoon time"),
            ListsRepository(context, kids.id).getLists().map { it.name }
        )
        assertEquals(5, RatingsRepository(context, kids.id).ratingFor(show.stableKey))
        // And the plain active-profile stores now read Kids' data.
        assertTrue(WatchlistRepository(context).isInWatchlist(show))

        // Round-trip: a second export equals the first.
        val second = backup.buildBackupJson(exportedAtMs = 2000)
        assertEquals(
            JSONObject(json).apply { remove("exportedAt") }.toString(),
            JSONObject(second).apply { remove("exportedAt") }.toString()
        )
    }

    @Test
    fun `a pre-profile backup restores into Profile 1`() {
        // The cycle-10 format: legacy stores only, no profiles.
        val watchlistJson =
            "[{\"key\":\"tmdb:101\",\"name\":\"Backup Film\"," +
                "\"poster\":null,\"year\":\"2020\",\"type\":\"MOVIE\"}]"
        val stores = JSONObject()
            .put(
                "watchly_library",
                JSONObject().put(
                    "watchlist",
                    JSONObject().put("t", "s").put("v", watchlistJson)
                )
            )
            .put(
                "watchly_ratings",
                JSONObject().put(
                    "tmdb:101",
                    JSONObject().put("t", "i").put("v", 7)
                )
            )
        val json = JSONObject()
            .put("app", "watchly")
            .put("format", 1)
            .put("stores", stores)
            .toString()
        backup.restoreFromJson(json)
        assertEquals(1, profiles.profiles().size)
        assertEquals("Profile 1", profiles.activeProfile().name)
        assertTrue(WatchlistRepository(context).isInWatchlist(film))
        assertEquals(7, RatingsRepository(context).ratingFor(film.stableKey))
    }

    @Test
    fun `restore refuses to write non-user stores named like profiles data`() {
        // A file may name the scoped families (they restore), but
        // caches / key stores / download state are never written,
        // however they are named.
        val json = """
            {"app":"watchly","format":1,"stores":{
              "watchly_settings":{"tmdb_key":{"t":"s","v":"stolen"}},
              "watchly_download_state":{"x":{"t":"s","v":"y"}},
              "watchly_omdb_cache":{"z":{"t":"s","v":"w"}},
              "watchly_ratings":{"tmdb:5":{"t":"i","v":6}}
            }}
        """.trimIndent()
        val result = backup.restoreFromJson(json)
        assertEquals(1, result.storesRestored)
        assertEquals(6, RatingsRepository(context).ratingFor("tmdb:5"))
        assertEquals(
            "",
            context.getSharedPreferences("watchly_settings", Context.MODE_PRIVATE)
                .getString("tmdb_key", "")
        )
    }
}
