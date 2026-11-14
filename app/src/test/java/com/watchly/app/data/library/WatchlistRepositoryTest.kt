package com.watchly.app.data.library

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric test for the SharedPreferences-backed watchlist /
 * watched store. No network, no emulator — just the real Android
 * SharedPreferences implementation under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WatchlistRepositoryTest {

    private lateinit var repo: WatchlistRepository

    private val film = Title(
        archiveId = "nightofthelivingdead_201910",
        name = "Night of the Living Dead",
        mediaType = MediaType.MOVIE,
        year = "1968"
    )
    private val show = Title(
        tvmazeId = 169,
        name = "Breaking Bad",
        mediaType = MediaType.TV,
        year = "2008"
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(
            WatchlistRepository.PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        ).edit().clear().commit()
        repo = WatchlistRepository(context)
    }

    @Test
    fun `toggle adds then removes from the watchlist`() {
        assertFalse(repo.isInWatchlist(film))

        assertTrue(repo.toggleWatchlist(film))
        assertTrue(repo.isInWatchlist(film))
        assertEquals(listOf("Night of the Living Dead"), repo.getWatchlist().map { it.name })

        assertFalse(repo.toggleWatchlist(film))
        assertFalse(repo.isInWatchlist(film))
        assertTrue(repo.getWatchlist().isEmpty())
    }

    @Test
    fun `watchlist returns most recently added first and restores ids`() {
        repo.toggleWatchlist(film)
        repo.toggleWatchlist(show)

        val list = repo.getWatchlist()
        assertEquals(listOf("Breaking Bad", "Night of the Living Dead"), list.map { it.name })
        assertEquals(169, list[0].tvmazeId)
        assertEquals("nightofthelivingdead_201910", list[1].archiveId)
    }

    @Test
    fun `a title without year or poster reads back with real nulls, never the string null`() {
        // Bug-named regression (Phase D audit): org.json's optString
        // returned the literal "null" for JSON-null fields, and the
        // user's Library showed a watchlist card subtitled "null"
        // with a broken poster. StoredTitle writes JSONObject.NULL
        // for missing values; reads must map that back to null.
        val undated = Title(
            tvmazeId = 42,
            name = "Undated Mystery Show",
            mediaType = MediaType.TV,
            year = null,
            posterUrl = null
        )
        repo.toggleWatchlist(undated)

        val readBack = repo.getWatchlist().single()
        assertEquals(null, readBack.year)
        assertEquals(null, readBack.posterUrl)
    }

    @Test
    fun `watched state is stored per title`() {
        assertFalse(repo.isWatched(show))
        repo.setWatched(show, true)
        assertTrue(repo.isWatched(show))
        assertFalse(repo.isWatched(film))

        repo.setWatched(show, false)
        assertFalse(repo.isWatched(show))
    }

    @Test
    fun `episode watched state toggles per episode`() {
        assertFalse(repo.isEpisodeWatched(show, 1, 1))

        assertTrue(repo.toggleEpisodeWatched(show, 1, 1))
        assertTrue(repo.isEpisodeWatched(show, 1, 1))
        assertFalse(repo.isEpisodeWatched(show, 1, 2))

        assertFalse(repo.toggleEpisodeWatched(show, 1, 1))
        assertFalse(repo.isEpisodeWatched(show, 1, 1))
    }
}
