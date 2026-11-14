package com.watchly.app.data.remote

import com.watchly.app.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the Trakt parsers (cycle 4). Fixtures mirror the live
 * trending/ratings shapes verified 2026-11-06: trending wraps each
 * item in "movie"/"show" with an ids block carrying imdb + tmdb.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TraktApiTest {

    private val trendingMovies = """
        [
          {"watchers": 128, "movie": {
            "title": "The Dark Knight", "year": 2008,
            "ids": {"trakt": 16, "slug": "the-dark-knight-2008",
                    "imdb": "tt0468569", "tmdb": 155}
          }},
          {"watchers": 90, "movie": {
            "title": "Dune: Part Two", "year": 2024,
            "ids": {"trakt": 770, "imdb": "tt15239678", "tmdb": 693134}
          }},
          {"watchers": 3, "movie": {"title": "", "year": 2020,
            "ids": {"trakt": 999}}}
        ]
    """.trimIndent()

    private val trendingShows = """
        [
          {"watchers": 210, "show": {
            "title": "Breaking Bad", "year": 2008,
            "ids": {"trakt": 1388, "imdb": "tt0903747", "tmdb": 1396,
                    "tvdb": 81189}
          }}
        ]
    """.trimIndent()

    @Test
    fun `parses trending movies with canonical ids`() {
        val titles = TraktApi.parseTrending(trendingMovies, MediaType.MOVIE)

        assertEquals(2, titles.size) // untitled entry skipped
        val first = titles[0]
        assertEquals("The Dark Knight", first.name)
        assertEquals("2008", first.year)
        assertEquals(155, first.tmdbId)
        assertEquals("tt0468569", first.imdbId)
        assertEquals(MediaType.MOVIE, first.mediaType)
        assertEquals("tmdb:155", first.stableKey)
    }

    @Test
    fun `parses trending shows as TV`() {
        val titles = TraktApi.parseTrending(trendingShows, MediaType.TV)

        assertEquals(1, titles.size)
        assertEquals(MediaType.TV, titles[0].mediaType)
        assertEquals("tt0903747", titles[0].imdbId)
        assertEquals(1396, titles[0].tmdbId)
    }

    @Test
    fun `malformed trending parses to empty`() {
        assertEquals(0, TraktApi.parseTrending("[]", MediaType.MOVIE).size)
        assertEquals(0, TraktApi.parseTrending("garbage", MediaType.MOVIE).size)
    }

    @Test
    fun `parses the community rating`() {
        assertEquals(
            8.66421,
            TraktApi.parseRating("""{"rating": 8.66421, "votes": 12000}""")!!,
            0.00001
        )
        assertNull(TraktApi.parseRating("""{"rating": 0, "votes": 0}"""))
        assertNull(TraktApi.parseRating("""{}"""))
        assertNull(TraktApi.parseRating("garbage"))
    }
}
