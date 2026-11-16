package com.watchly.app.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "not out yet" truth rule: a movie/show with a future release date
 * must never present itself as playable. Unknown dates stay playable —
 * Play is only hidden when we KNOW the title is not out.
 */
class TitleReleaseTest {

    private val today = "2026-11-15"

    private fun movie(releaseDate: String? = null, year: String? = null) = Title(
        name = "Test",
        mediaType = MediaType.MOVIE,
        releaseDate = releaseDate,
        year = year
    )

    @Test
    fun futureReleaseDateMeansNotReleased() {
        assertFalse(movie(releaseDate = "2027-03-12").isReleased(today))
        assertFalse(movie(releaseDate = "2026-12-01").isReleased(today))
    }

    @Test
    fun todayOrPastReleaseDateMeansReleased() {
        assertTrue(movie(releaseDate = "2026-11-15").isReleased(today))
        assertTrue(movie(releaseDate = "2026-11-14").isReleased(today))
        assertTrue(movie(releaseDate = "1999-10-15").isReleased(today))
    }

    @Test
    fun yearOnlyFallbackStillGuardsFutureYears() {
        assertFalse(movie(year = "2027").isReleased(today))
        assertTrue(movie(year = "2026").isReleased(today))
        assertTrue(movie(year = "2020").isReleased(today))
    }

    @Test
    fun unknownDateMeansReleased() {
        assertTrue(movie().isReleased(today))
        assertTrue(movie(releaseDate = "", year = null).isReleased(today))
    }

    @Test
    fun futureFirstAirDateMeansShowNotReleased() {
        val show = Title(
            name = "Upcoming Show",
            mediaType = MediaType.TV,
            releaseDate = "2027-01-05",
            year = "2027"
        )
        assertFalse(show.isReleased(today))
    }

    @Test
    fun unairedEpisodesAreNotPlayable() {
        val unaired = Episode(season = 2, number = 5, airDate = "2026-12-20")
        val aired = Episode(season = 2, number = 4, airDate = "2026-11-10")
        val unknown = Episode(season = 2, number = 3)
        assertFalse(unaired.isAired(today))
        assertTrue(aired.isAired(today))
        assertTrue(unknown.isAired(today))
    }
}
