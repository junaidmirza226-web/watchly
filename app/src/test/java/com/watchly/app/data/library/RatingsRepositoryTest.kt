package com.watchly.app.data.library

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Personal ratings (cycle 10, §4.2): a 1–10 score per stable key,
 * stored locally. Out-of-range values must never persist — a
 * corrupt score rendering as a rating would be a lie on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RatingsRepositoryTest {

    private lateinit var ratings: RatingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(RatingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        ratings = RatingsRepository(context)
    }

    @Test
    fun `unrated title reads null`() {
        assertNull(ratings.ratingFor("tmdb:1"))
    }

    @Test
    fun `rating round trips and overwrites`() {
        ratings.setRating("tmdb:1", 8)
        assertEquals(8, ratings.ratingFor("tmdb:1"))
        ratings.setRating("tmdb:1", 3)
        assertEquals(3, ratings.ratingFor("tmdb:1"))
    }

    @Test
    fun `boundary ratings 1 and 10 are valid`() {
        ratings.setRating("tmdb:1", 1)
        ratings.setRating("tmdb:2", 10)
        assertEquals(1, ratings.ratingFor("tmdb:1"))
        assertEquals(10, ratings.ratingFor("tmdb:2"))
    }

    @Test
    fun `null and out of range clear instead of storing`() {
        ratings.setRating("tmdb:1", 7)
        ratings.setRating("tmdb:1", null)
        assertNull(ratings.ratingFor("tmdb:1"))

        ratings.setRating("tmdb:1", 0)
        assertNull(ratings.ratingFor("tmdb:1"))
        ratings.setRating("tmdb:1", 11)
        assertNull(ratings.ratingFor("tmdb:1"))
    }

    @Test
    fun `ratings are per title`() {
        ratings.setRating("tmdb:1", 9)
        assertNull(ratings.ratingFor("tmdb:2"))
    }
}
