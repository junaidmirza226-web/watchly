package com.watchly.app.data.playback

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric test for the real [PlaybackRepository] (SharedPreferences). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlaybackRepositoryTest {

    private lateinit var repository: PlaybackRepository

    private fun progress(
        key: String,
        positionMs: Long,
        durationMs: Long,
        updatedAtMs: Long
    ) = PlaybackProgress(
        key = key,
        titleName = "Title $key",
        posterUrl = null,
        mediaType = MediaType.MOVIE,
        episodeLabel = null,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAtMs = updatedAtMs
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(
            PlaybackRepository.PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        ).edit().clear().commit()
        repository = PlaybackRepository(context)
    }

    @Test
    fun `save and get round-trips a resume point`() {
        repository.saveProgress(progress("archive:x", 1_800_000L, 3_600_000L, 1_000L))

        val saved = repository.getProgress("archive:x")
        assertEquals(1_800_000L, saved?.positionMs)
        assertEquals(3_600_000L, saved?.durationMs)
        assertEquals("Title archive:x", saved?.titleName)
        assertEquals(0.5f, saved?.fraction)
    }

    @Test
    fun `unknown title has no resume point`() {
        assertNull(repository.getProgress("archive:never-played"))
    }

    @Test
    fun `clear removes the resume point`() {
        repository.saveProgress(progress("archive:x", 1_800_000L, 3_600_000L, 1_000L))
        repository.clearProgress("archive:x")
        assertNull(repository.getProgress("archive:x"))
    }

    @Test
    fun `recent progress is newest first and skips finished titles`() {
        repository.saveProgress(progress("archive:a", 100_000L, 3_600_000L, 1_000L))
        repository.saveProgress(progress("archive:b", 200_000L, 3_600_000L, 2_000L))
        // Finished (>95%): must not appear in Continue Watching.
        repository.saveProgress(progress("archive:c", 3_500_000L, 3_600_000L, 3_000L))

        val recent = repository.getRecentProgress()
        assertEquals(listOf("archive:b", "archive:a"), recent.map { it.key })
    }

    @Test
    fun `speed defaults to normal and snaps to an offered speed`() {
        assertEquals(1.0f, repository.getSpeed())
        repository.saveSpeed(1.5f)
        assertEquals(1.5f, repository.getSpeed())
        repository.saveSpeed(1.3f)
        assertEquals(1.25f, repository.getSpeed())
    }
}
