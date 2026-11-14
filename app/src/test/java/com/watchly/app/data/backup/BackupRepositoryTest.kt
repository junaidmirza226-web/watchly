package com.watchly.app.data.backup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.downloads.DownloadSettingsRepository
import com.watchly.app.data.library.ListsRepository
import com.watchly.app.data.library.RatingsRepository
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.PlayabilityFilter
import com.watchly.app.data.model.SubtitleBackground
import com.watchly.app.data.model.Title
import com.watchly.app.data.playback.PlaybackProgress
import com.watchly.app.data.playback.PlaybackRepository
import com.watchly.app.data.settings.TmdbKeyRepository
import com.watchly.app.data.settings.UiPreferencesRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Backup & export (cycle 10, §15.2). The core promise — export →
 * wipe → import → IDENTICAL state — is proven at the repository
 * level for every store in the backup: watchlist + watched +
 * watched episodes, custom lists, continue-watching progress,
 * per-title player choices, settings, and personal ratings. A
 * second export after the restore must equal the first, which is
 * the strictest form of "lossless".
 *
 * Equally pinned: API keys are NEVER in the file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupRepositoryTest {

    private lateinit var context: Application
    private lateinit var backup: BackupRepository
    private lateinit var watchlist: WatchlistRepository
    private lateinit var lists: ListsRepository
    private lateinit var playback: PlaybackRepository
    private lateinit var uiPrefs: UiPreferencesRepository
    private lateinit var downloadSettings: DownloadSettingsRepository
    private lateinit var ratings: RatingsRepository

    private val film = Title(
        tmdbId = 101, name = "Backup Film", mediaType = MediaType.MOVIE,
        year = "2020", posterUrl = "https://img.example/film.jpg"
    )
    private val show = Title(
        tvmazeId = 202, name = "Backup Show", mediaType = MediaType.TV,
        year = "2021"
    )

    private val allFiles = listOf(
        WatchlistRepository.PREFS_NAME,
        PlaybackRepository.PREFS_NAME,
        UiPreferencesRepository.PREFS_NAME,
        DownloadSettingsRepository.PREFS_NAME,
        RatingsRepository.PREFS_NAME,
        TmdbKeyRepository.PREFS_NAME
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        allFiles.forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
        backup = BackupRepository(context)
        watchlist = WatchlistRepository(context)
        lists = ListsRepository(context)
        playback = PlaybackRepository(context)
        uiPrefs = UiPreferencesRepository(context)
        downloadSettings = DownloadSettingsRepository(context)
        ratings = RatingsRepository(context)
    }

    /** Fills every backed-up store with recognisable state. */
    private fun populate() {
        watchlist.toggleWatchlist(film)
        watchlist.toggleWatchlist(show)
        watchlist.setWatched(show, true)
        watchlist.toggleEpisodeWatched(show, 1, 2)
        val list = lists.createList("Weekend")
        lists.addTitle(list.id, film)
        lists.addTitle(list.id, show)
        playback.saveProgress(
            PlaybackProgress(
                key = film.stableKey,
                titleName = film.name,
                posterUrl = film.posterUrl,
                mediaType = MediaType.MOVIE,
                episodeLabel = null,
                positionMs = 45_000,
                durationMs = 90_000,
                updatedAtMs = 1_700_000_000_000
            )
        )
        playback.saveSpeed(1.5f)
        playback.saveQualityUrl(film.stableKey, "https://files.example/film-hd.mp4")
        playback.saveAudioLanguage(film.stableKey, "en")
        playback.saveSubtitleOffsetMs(film.stableKey, -500)
        uiPrefs.setPlayabilityFilter(PlayabilityFilter.PLAYABLE_ONLY)
        uiPrefs.setAutoplayNext(false)
        uiPrefs.setGenreFor("movies", "Drama")
        uiPrefs.setSubtitleTextSizeSp(24f)
        uiPrefs.setSubtitleBackground(SubtitleBackground.SOLID)
        uiPrefs.setDataSaverEnabled(true)
        downloadSettings.setWifiOnly(false)
        downloadSettings.setPauseOnLowBattery(false)
        downloadSettings.setStorageCapBytes(5_000_000_000L)
        ratings.setRating(film.stableKey, 8)
    }

    private fun wipeStores() {
        allFiles.forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    @Test
    fun `export wipe import restores identical state`() {
        populate()
        val json = backup.buildBackupJson(exportedAtMs = 1234)

        wipeStores()
        // The wipe really emptied things (guards a vacuous test).
        assertTrue(watchlist.getWatchlist().isEmpty())
        assertNull(ratings.ratingFor(film.stableKey))

        val result = backup.restoreFromJson(json)
        assertTrue(result.entriesRestored > 10)

        // Library: watchlist order + contents, watched, episodes.
        assertEquals(
            listOf(show.stableKey, film.stableKey),
            watchlist.getWatchlist().map { it.stableKey }
        )
        assertTrue(watchlist.isWatched(show))
        assertFalse(watchlist.isWatched(film))
        assertTrue(watchlist.isEpisodeWatched(show, 1, 2))
        val restoredLists = lists.getLists()
        assertEquals(1, restoredLists.size)
        assertEquals("Weekend", restoredLists[0].name)
        assertEquals(
            listOf(film.stableKey, show.stableKey),
            restoredLists[0].titles.map { it.stableKey }
        )

        // Playback: progress, speed, per-title player choices.
        val progress = playback.getProgress(film.stableKey)
        assertEquals(45_000L, progress?.positionMs)
        assertEquals(90_000L, progress?.durationMs)
        assertEquals(1_700_000_000_000L, progress?.updatedAtMs)
        assertEquals(1.5f, playback.getSpeed())
        assertEquals(
            "https://files.example/film-hd.mp4",
            playback.getQualityUrl(film.stableKey)
        )
        assertEquals("en", playback.getAudioLanguage(film.stableKey))
        assertEquals(-500L, playback.getSubtitleOffsetMs(film.stableKey))

        // Settings + ratings.
        assertEquals(PlayabilityFilter.PLAYABLE_ONLY, uiPrefs.playabilityFilter())
        assertFalse(uiPrefs.autoplayNext())
        assertEquals("Drama", uiPrefs.genreFor("movies"))
        assertEquals(24f, uiPrefs.subtitleStyle().textSizeSp)
        assertEquals(SubtitleBackground.SOLID, uiPrefs.subtitleStyle().background)
        assertTrue(uiPrefs.dataSaverEnabled())
        assertFalse(downloadSettings.settings().wifiOnly)
        assertFalse(downloadSettings.settings().pauseOnLowBattery)
        assertEquals(5_000_000_000L, downloadSettings.settings().storageCapBytes)
        assertEquals(8, ratings.ratingFor(film.stableKey))
    }

    @Test
    fun `second export after restore equals the first`() {
        populate()
        val first = backup.buildBackupJson(exportedAtMs = 1000)
        wipeStores()
        backup.restoreFromJson(first)
        val second = backup.buildBackupJson(exportedAtMs = 2000)
        // exportedAt legitimately differs; everything else must be
        // the same JSON, key for key and value for value.
        val a = JSONObject(first).apply { remove("exportedAt") }
        val b = JSONObject(second).apply { remove("exportedAt") }
        assertEquals(a.toString(), b.toString())
    }

    @Test
    fun `restore replaces rather than merging`() {
        populate()
        val json = backup.buildBackupJson(exportedAtMs = 1000)
        // Post-backup change: a new watchlist entry that the
        // backup does not know must NOT survive the restore.
        val extra = Title(tmdbId = 999, name = "Extra", mediaType = MediaType.MOVIE)
        watchlist.toggleWatchlist(extra)
        ratings.setRating(extra.stableKey, 1)
        backup.restoreFromJson(json)
        assertFalse(watchlist.isInWatchlist(extra))
        assertNull(ratings.ratingFor(extra.stableKey))
        assertTrue(watchlist.isInWatchlist(film))
    }

    @Test
    fun `api keys are never in the backup`() {
        TmdbKeyRepository(context).setKey("super-secret-tmdb-key")
        populate()
        val json = backup.buildBackupJson(exportedAtMs = 1000)
        assertFalse(json.contains("super-secret-tmdb-key"))
        assertFalse(json.contains(TmdbKeyRepository.PREFS_NAME))
    }

    @Test
    fun `stream round trip matches the string round trip`() {
        populate()
        val out = ByteArrayOutputStream()
        backup.writeBackupTo(out, exportedAtMs = 1000)
        wipeStores()
        val result = backup.readBackupFrom(
            ByteArrayInputStream(out.toByteArray())
        )
        assertTrue(result.entriesRestored > 10)
        assertTrue(watchlist.isInWatchlist(film))
        assertEquals(8, ratings.ratingFor(film.stableKey))
    }

    @Test
    fun `non backup files are rejected before anything is written`() {
        populate()
        val before = backup.buildBackupJson(exportedAtMs = 1)
        assertRejected("this is not json at all")
        assertRejected("""{"app":"other","format":1,"stores":{}}""")
        assertRejected("""{"app":"watchly","format":99,"stores":{}}""")
        assertRejected("""{"app":"watchly","format":1}""")
        assertRejected("""{"app":"watchly","format":1,"stores":{}}""")
        // Nothing was touched by the rejected imports.
        val after = backup.buildBackupJson(exportedAtMs = 2)
        assertEquals(
            JSONObject(before).apply { remove("exportedAt") }.toString(),
            JSONObject(after).apply { remove("exportedAt") }.toString()
        )
    }

    @Test
    fun `unknown stores keys and value types are ignored`() {
        val json = """
            {"app":"watchly","format":1,"stores":{
              "watchly_ratings":{
                "tmdb:5":{"t":"i","v":6},
                "tmdb:6":{"t":"mystery","v":1}
              },
              "watchly_from_the_future":{"x":{"t":"s","v":"y"}},
              "watchly_library":{
                "watchlist":{"t":"s","v":"[]"},
                "not_a_real_key":{"t":"s","v":"nope"}
              }
            }}
        """.trimIndent()
        val result = backup.restoreFromJson(json)
        assertEquals(6, ratings.ratingFor("tmdb:5"))
        assertNull(ratings.ratingFor("tmdb:6"))
        assertEquals(2, result.entriesRestored)
    }

    private fun assertRejected(json: String) {
        try {
            backup.restoreFromJson(json)
            fail("Expected BackupFormatException for: $json")
        } catch (expected: BackupFormatException) {
            // expected
        }
    }
}
