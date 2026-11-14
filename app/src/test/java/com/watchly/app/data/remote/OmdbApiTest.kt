package com.watchly.app.data.remote

import com.watchly.app.data.model.OmdbRatings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the OMDb parser and cache policy (cycle 4). Fixtures
 * mirror the live response verified 2026-11-06 for The Dark Knight
 * (IMDb 9.1 in the connector check; the API's own payload shape —
 * imdbRating / imdbVotes / Metascore fields plus a Ratings array —
 * is what is pinned here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OmdbApiTest {

    private val darkKnight = """
        {
          "Title": "The Dark Knight",
          "Year": "2008",
          "Rated": "PG-13",
          "Metascore": "84",
          "imdbRating": "9.0",
          "imdbVotes": "2,900,000",
          "imdbID": "tt0468569",
          "Ratings": [
            {"Source": "Internet Movie Database", "Value": "9.0/10"},
            {"Source": "Rotten Tomatoes", "Value": "94%"},
            {"Source": "Metacritic", "Value": "84/100"}
          ],
          "Response": "True"
        }
    """.trimIndent()

    @Test
    fun `parses the full ratings bundle`() {
        val ratings = OmdbApi.parseResponse(darkKnight)

        assertEquals(9.0, ratings!!.imdbRating!!, 0.001)
        assertEquals("2,900,000", ratings.imdbVotes)
        assertEquals(94, ratings.rottenTomatoesPercent)
        assertEquals(84, ratings.metacriticScore)
        assertEquals("PG-13", ratings.rated)
        assertTrue(ratings.hasAnyRating)
    }

    @Test
    fun `a failed response parses to null`() {
        // Wrong key, unknown id and spent quota all look like this.
        assertNull(
            OmdbApi.parseResponse("""{"Response":"False","Error":"Invalid API key!"}""")
        )
        assertNull(
            OmdbApi.parseResponse("""{"Response":"False","Error":"Request limit reached!"}""")
        )
        assertNull(OmdbApi.parseResponse("not json"))
    }

    @Test
    fun `N A fields become nulls instead of fake zeros`() {
        val body = """
            {
              "Rated": "N/A", "Metascore": "N/A",
              "imdbRating": "N/A", "imdbVotes": "N/A",
              "Ratings": [], "Response": "True"
            }
        """.trimIndent()
        // Nothing displayable at all -> null, so the UI hides the strip.
        assertNull(OmdbApi.parseResponse(body))
    }

    /** In-memory cache for the policy tests. */
    private class FakeCache : OmdbCache {
        var entry: Pair<OmdbRatings, Long>? = null
        var puts = 0
        override fun get(imdbId: String) = entry
        override fun put(imdbId: String, ratings: OmdbRatings, fetchedAtMs: Long) {
            entry = ratings to fetchedAtMs
            puts++
        }
    }

    @Test
    fun `a fresh cache entry is served without any network call`() = runTest {
        val cache = FakeCache()
        val cached = OmdbRatings(imdbRating = 9.0, rottenTomatoesPercent = 94)
        cache.entry = cached to 1_000_000L

        // now is 1 hour after the fetch: well inside the 7-day TTL.
        // The key is deliberately bogus — if the code touched the
        // network it could not produce the cached values.
        val result = OmdbApi.getRatings(
            apiKey = "bogus-key",
            imdbId = "tt0468569",
            cache = cache,
            nowMs = 1_000_000L + 3_600_000L
        )

        assertEquals(cached, result)
        assertEquals(0, cache.puts)
    }
}
