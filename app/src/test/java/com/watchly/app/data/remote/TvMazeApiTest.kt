package com.watchly.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the TVmaze episode / cast parsers, fed with trimmed real
 * responses (shape verified live 2026-11-06, see
 * sources-test/tvmaze-episodes.json). org.json is an Android-framework
 * class, so this runs under Robolectric — still a JVM test, no emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TvMazeApiTest {

    // Trimmed real episodes: a normal one, one without a summary, and
    // one missing `number` (skipped).
    private val sampleEpisodes = """
        [
          {"id": 12192, "name": "Pilot", "season": 1, "number": 1,
           "airdate": "2008-01-20", "runtime": 60,
           "summary": "<p>A chemistry teacher turns to crime.</p>",
           "image": {"medium": "https://static.tvmaze.com/m.jpg",
                     "original": "https://static.tvmaze.com/o.jpg"}},
          {"id": 12193, "name": "Cat's in the Bag...", "season": 1, "number": 2,
           "airdate": "2008-01-27", "runtime": 58, "summary": null, "image": null},
          {"id": 12194, "name": "No Number", "season": 1,
           "airdate": "2008-02-03", "runtime": 60}
        ]
    """.trimIndent()

    private val sampleCast = """
        [
          {"person": {"id": 14245, "name": "Bryan Cranston",
                      "image": {"medium": "https://static.tvmaze.com/bc.jpg"}},
           "character": {"id": 45529, "name": "Walter White"}},
          {"person": {"id": 14246, "name": "Aaron Paul", "image": null},
           "character": {"id": 45530, "name": "Jesse Pinkman"}},
          {"person": {"name": ""}, "character": {"name": "Nobody"}}
        ]
    """.trimIndent()

    @Test
    fun `parses episodes with season number airdate runtime and overview`() {
        val episodes = TvMazeApi.parseEpisodes(sampleEpisodes)

        assertEquals(2, episodes.size)

        val pilot = episodes[0]
        assertEquals(12192, pilot.id)
        assertEquals(1, pilot.season)
        assertEquals(1, pilot.number)
        assertEquals("Pilot", pilot.name)
        assertEquals("2008-01-20", pilot.airDate)
        assertEquals(60, pilot.runtimeMinutes)
        assertEquals("A chemistry teacher turns to crime.", pilot.overview)
        assertEquals("https://static.tvmaze.com/o.jpg", pilot.imageUrl)
        assertEquals("S1 E1", pilot.label)

        val second = episodes[1]
        assertEquals("Cat's in the Bag...", second.name)
        assertNull(second.overview) // null summary -> null overview
        assertNull(second.imageUrl) // null image -> null image
        assertEquals(58, second.runtimeMinutes)
    }

    @Test
    fun `empty or malformed episode bodies parse to an empty list`() {
        assertTrue(TvMazeApi.parseEpisodes("[]").isEmpty())
        assertTrue(TvMazeApi.parseEpisodes("{}").isEmpty())
    }

    @Test
    fun `parses cast with person character and photo`() {
        val cast = TvMazeApi.parseCast(sampleCast)

        assertEquals(2, cast.size)
        assertEquals("Bryan Cranston", cast[0].personName)
        assertEquals("Walter White", cast[0].characterName)
        assertEquals("https://static.tvmaze.com/bc.jpg", cast[0].imageUrl)
        assertEquals("Aaron Paul", cast[1].personName)
        assertEquals("Jesse Pinkman", cast[1].characterName)
        assertNull(cast[1].imageUrl)
    }

    @Test
    fun `empty cast body parses to an empty list`() {
        assertTrue(TvMazeApi.parseCast("[]").isEmpty())
    }

    @Test
    fun `shows page parses for the trending fallback catalogue`() {
        // Phase D: fetchTrendingShows falls back to /shows page 0
        // when the day's schedule is thin or unreachable.
        val body = """
            [
              {"id": 1, "name": "Low Rated", "rating": {"average": 5.0},
               "premiered": "2020-01-01", "genres": ["Drama"]},
              {"id": 2, "name": "High Rated", "rating": {"average": 9.0},
               "premiered": "2021-06-01", "genres": ["Comedy"]}
            ]
        """.trimIndent()

        val titles = TvMazeApi.parseShowsPage(body)

        assertEquals(listOf("Low Rated", "High Rated"), titles.map { it.name })
        assertEquals(listOf(1, 2), titles.map { it.tvmazeId })
    }

    @Test
    fun `schedule parse dedupes shows and sorts by rating`() {
        val episode = { id: Int, name: String, rating: Double -> 
            """{"_embedded": {"show": {"id": $id, "name": "$name",
               "rating": {"average": $rating}, "premiered": "2020-01-01",
               "genres": []}}}"""
        }
        val body = "[" +
            episode(7, "Twice Aired", 7.0) + "," +
            episode(7, "Twice Aired", 7.0) + "," +
            episode(8, "Top Show", 9.5) +
            "]"

        val titles = TvMazeApi.parseSchedule(body)

        assertEquals(listOf("Top Show", "Twice Aired"), titles.map { it.name })
    }
}
