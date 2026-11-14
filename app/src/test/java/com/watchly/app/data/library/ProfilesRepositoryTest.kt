package com.watchly.app.data.library

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.data.playback.PlaybackProgress
import com.watchly.app.data.playback.PlaybackRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Profiles (cycle 12, §8). Three promises are pinned here:
 *
 *  1. MIGRATION IS LOSSLESS: data written the pre-profile way
 *     (raw legacy files, no profile metadata anywhere) is fully
 *     visible under "Profile 1" the moment profiles exist — the
 *     seeded bytes are compared field by field, and the legacy
 *     files still hold them afterwards (nothing was copied).
 *  2. PROFILES ARE ISOLATED: each profile's watchlist, lists,
 *     watched state, progress, player choices and ratings live
 *     in its own files and never leak into another's.
 *  3. THE RULES HOLD: cap of [ProfilesRepository.MAX_PROFILES],
 *     the last profile can't be deleted, deleting the active one
 *     activates another and wipes its data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProfilesRepositoryTest {

    private lateinit var context: Application

    private val film = Title(
        tmdbId = 101, name = "Profile Film", mediaType = MediaType.MOVIE,
        year = "2020", posterUrl = "https://img.example/film.jpg"
    )
    private val show = Title(
        tvmazeId = 202, name = "Profile Show", mediaType = MediaType.TV,
        year = "2021"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Every file profiles can touch, wiped: metadata + the
        // legacy bases (suffix files are wiped per-test when made).
        listOf(
            ProfilesRepository.PREFS_NAME,
            WatchlistRepository.PREFS_NAME,
            PlaybackRepository.PREFS_NAME,
            RatingsRepository.PREFS_NAME
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    private fun prefs(name: String) =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // 1. Migration
    // ------------------------------------------------------------------

    @Test
    fun `pre-profile data is fully visible under Profile 1 after migration`() {
        // Seed EXACTLY what v1.3.0 wrote: raw legacy files, and —
        // crucially — no watchly_profiles file at all.
        prefs(WatchlistRepository.PREFS_NAME).edit()
            .putString(
                WatchlistRepository.KEY_WATCHLIST,
                """[{"key":"tmdb:101","name":"Profile Film",""" +
                    """"poster":"https://img.example/film.jpg",""" +
                    """"year":"2020","type":"MOVIE"},""" +
                    """{"key":"tvmaze:202","name":"Profile Show",""" +
                    """"poster":null,"year":"2021","type":"TV"}]"""
            )
            .putString(
                WatchlistRepository.KEY_WATCHED,
                """[{"key":"tvmaze:202","name":"Profile Show",""" +
                    """"poster":null,"year":"2021","type":"TV"}]"""
            )
            .putStringSet(
                WatchlistRepository.KEY_WATCHED_EPISODES,
                setOf("tvmaze:202:S1E2")
            )
            .putString(
                ListsRepository.KEY_LISTS,
                """[{"id":"list-1","name":"Weekend","items":[""" +
                    """{"key":"tmdb:101","name":"Profile Film",""" +
                    """"poster":"https://img.example/film.jpg",""" +
                    """"year":"2020","type":"MOVIE"}]}]"""
            )
            .commit()
        prefs(PlaybackRepository.PREFS_NAME).edit()
            .putString(
                "progress:tmdb:101",
                """{"name":"Profile Film",""" +
                    """"poster":"https://img.example/film.jpg",""" +
                    """"type":"MOVIE","episode":null,""" +
                    """"pos":45000,"dur":90000,"at":1700000000000}"""
            )
            .putFloat("speed", 1.5f)
            .putString("quality:tmdb:101", "https://files.example/film-hd.mp4")
            .commit()
        prefs(RatingsRepository.PREFS_NAME).edit()
            .putInt("tmdb:101", 8)
            .commit()
        assertNull(prefs(ProfilesRepository.PREFS_NAME).getString("profiles", null))

        // First contact with the profile world.
        val profiles = ProfilesRepository(context)
        val created = profiles.profiles()
        assertEquals(1, created.size)
        assertEquals("Profile 1", created[0].name)
        assertEquals(created[0], profiles.activeProfile())

        // Everything seeded is visible, identically, via the
        // ordinary (active-profile) stores.
        val watchlist = WatchlistRepository(context)
        assertEquals(
            listOf(show.stableKey, film.stableKey),
            watchlist.getWatchlist().map { it.stableKey }
        )
        assertTrue(watchlist.isWatched(show))
        assertTrue(watchlist.isEpisodeWatched(show, 1, 2))
        val lists = ListsRepository(context).getLists()
        assertEquals(1, lists.size)
        assertEquals("Weekend", lists[0].name)
        assertEquals(listOf(film.stableKey), lists[0].titles.map { it.stableKey })

        val playback = PlaybackRepository(context)
        val progress = playback.getProgress(film.stableKey)
        assertEquals(45_000L, progress?.positionMs)
        assertEquals(90_000L, progress?.durationMs)
        assertEquals(1.5f, playback.getSpeed())
        assertEquals(
            "https://files.example/film-hd.mp4",
            playback.getQualityUrl(film.stableKey)
        )
        assertEquals(8, RatingsRepository(context).ratingFor(film.stableKey))

        // And nothing was copied: the legacy files still hold the
        // data themselves (Profile 1's files ARE those files).
        assertTrue(
            prefs(WatchlistRepository.PREFS_NAME)
                .getString(WatchlistRepository.KEY_WATCHLIST, "")
                .orEmpty().contains("Profile Film")
        )
    }

    @Test
    fun `migration is idempotent and repairs a dangling active id`() {
        val profiles = ProfilesRepository(context)
        val first = profiles.profiles()
        assertEquals(first, profiles.profiles())
        prefs(ProfilesRepository.PREFS_NAME).edit()
            .putString(ProfilesRepository.KEY_ACTIVE, "no-such-profile")
            .commit()
        assertEquals(first[0], profiles.activeProfile())
    }

    // ------------------------------------------------------------------
    // 2. Isolation
    // ------------------------------------------------------------------

    @Test
    fun `each profile sees only its own library data`() {
        val profiles = ProfilesRepository(context)
        profiles.profiles() // migrate
        val kids = profiles.createProfile("Kids", 1)!!
        val defaultId = ProfilesRepository.DEFAULT_PROFILE_ID

        // Default profile data.
        WatchlistRepository(context, defaultId).toggleWatchlist(film)
        WatchlistRepository(context, defaultId).setWatched(film, true)
        val ownList = ListsRepository(context, defaultId).createList("Mine")
        ListsRepository(context, defaultId).addTitle(ownList.id, film)
        PlaybackRepository(context, defaultId).saveProgress(progress(30_000))
        PlaybackRepository(context, defaultId).saveQualityUrl(film.stableKey, "hd")
        RatingsRepository(context, defaultId).setRating(film.stableKey, 9)

        // Kids profile: completely different data.
        WatchlistRepository(context, kids.id).toggleWatchlist(show)
        val kidsPlayback = PlaybackRepository(context, kids.id)
        kidsPlayback.saveProgress(
            PlaybackProgress(
                key = show.stableKey, titleName = show.name, posterUrl = null,
                mediaType = MediaType.TV, episodeLabel = "S1 E1",
                positionMs = 5_000, durationMs = 60_000, updatedAtMs = 42
            )
        )
        RatingsRepository(context, kids.id).setRating(show.stableKey, 4)

        // Cross-checks: nothing leaks either way.
        val defaultWatch = WatchlistRepository(context, defaultId)
        assertTrue(defaultWatch.isInWatchlist(film))
        assertFalse(defaultWatch.isInWatchlist(show))
        assertEquals(1, ListsRepository(context, defaultId).getLists().size)
        assertTrue(ListsRepository(context, kids.id).getLists().isEmpty())
        assertNull(PlaybackRepository(context, kids.id).getProgress(film.stableKey))
        assertEquals(30_000L, PlaybackRepository(context, defaultId)
            .getProgress(film.stableKey)?.positionMs)
        assertEquals(9, RatingsRepository(context, defaultId).ratingFor(film.stableKey))
        assertNull(RatingsRepository(context, defaultId).ratingFor(show.stableKey))
        assertEquals(4, RatingsRepository(context, kids.id).ratingFor(show.stableKey))

        // The no-profileId stores follow the ACTIVE profile.
        assertFalse(WatchlistRepository(context).isInWatchlist(show))
        profiles.setActiveProfile(kids.id)
        assertTrue(WatchlistRepository(context).isInWatchlist(show))
        assertFalse(WatchlistRepository(context).isInWatchlist(film))
    }

    private fun progress(positionMs: Long) = PlaybackProgress(
        key = film.stableKey, titleName = film.name,
        posterUrl = film.posterUrl, mediaType = MediaType.MOVIE,
        episodeLabel = null, positionMs = positionMs,
        durationMs = 90_000, updatedAtMs = 1_700_000_000_000
    )

    // ------------------------------------------------------------------
    // 3. Rules
    // ------------------------------------------------------------------

    @Test
    fun `create is capped and later profiles get their own file suffix`() {
        val profiles = ProfilesRepository(context)
        profiles.profiles()
        assertEquals("", profiles.activeProfile().fileSuffix)
        val made = (1 until ProfilesRepository.MAX_PROFILES).map { i ->
            profiles.createProfile("P$i", i)!!
        }
        assertTrue(made.all { it.fileSuffix.startsWith("_") })
        assertEquals(
            ProfilesRepository.MAX_PROFILES,
            profiles.profiles().size
        )
        assertNull(profiles.createProfile("One too many", 0))
        // Suffixes are deterministic from the id alone.
        assertEquals(
            made[0].fileSuffix,
            ProfilesRepository.fileSuffixFor(made[0].id)
        )
        assertNotEquals(made[0].fileSuffix, made[1].fileSuffix)
    }

    @Test
    fun `rename validates and delete follows the rules`() {
        val profiles = ProfilesRepository(context)
        profiles.profiles()
        val guest = profiles.createProfile("Guest", 2)!!

        assertFalse(profiles.renameProfile(guest.id, "   "))
        assertFalse(profiles.renameProfile("missing", "Name"))
        assertTrue(profiles.renameProfile(guest.id, "Visitors"))
        assertEquals(
            "Visitors",
            profiles.profiles().first { it.id == guest.id }.name
        )

        // Give Guest data, make it active, delete it: the default
        // profile becomes active and Guest's data is gone.
        WatchlistRepository(context, guest.id).toggleWatchlist(film)
        profiles.setActiveProfile(guest.id)
        assertTrue(profiles.deleteProfile(guest.id))
        assertEquals(
            ProfilesRepository.DEFAULT_PROFILE_ID,
            profiles.activeProfileId()
        )
        assertFalse(WatchlistRepository(context, guest.id).isInWatchlist(film))

        // The last profile standing can't be deleted.
        assertFalse(profiles.deleteProfile(ProfilesRepository.DEFAULT_PROFILE_ID))
        assertFalse(profiles.deleteProfile("missing"))
        assertEquals(1, profiles.profiles().size)
    }

    @Test
    fun `setActiveProfile rejects unknown ids`() {
        val profiles = ProfilesRepository(context)
        profiles.profiles()
        assertFalse(profiles.setActiveProfile("missing"))
        assertEquals(
            ProfilesRepository.DEFAULT_PROFILE_ID,
            profiles.activeProfileId()
        )
    }
}
