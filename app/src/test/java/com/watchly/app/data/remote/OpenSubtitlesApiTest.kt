package com.watchly.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the OpenSubtitles planner + parsers (cycle 4). The
 * search fixture mirrors the live response shape verified
 * 2026-11-06 (data[].attributes with files[0].file_id).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenSubtitlesApiTest {

    @Test
    fun `movie plan uses the numeric imdb id`() {
        val plan = OpenSubtitlesApi.planSearch("tt0468569", null)

        assertEquals(468569, plan!!.imdbNumericId)
        assertNull(plan.seasonNumber)
        assertNull(plan.episodeNumber)
    }

    @Test
    fun `episode plan adds season and episode from the label`() {
        val plan = OpenSubtitlesApi.planSearch("tt0903747", "S1 E4")

        assertEquals(903747, plan!!.imdbNumericId)
        assertEquals(1, plan.seasonNumber)
        assertEquals(4, plan.episodeNumber)
    }

    @Test
    fun `no imdb id means no plan`() {
        assertNull(OpenSubtitlesApi.planSearch(null, "S1 E4"))
        assertNull(OpenSubtitlesApi.planSearch("not-an-id", null))
        assertNull(OpenSubtitlesApi.planSearch("", null))
    }

    private val searchBody = """
        {
          "total_pages": 1, "total_count": 2,
          "data": [
            {"id": "1", "type": "subtitle", "attributes": {
              "language": "en",
              "download_count": 52341,
              "ratings": {"rating": 8.5, "votes": 12},
              "hearing_impaired": false,
              "release": "The.Dark.Knight.2008.1080p.BluRay.x264",
              "files": [{"file_id": 123456, "file_name": "dark.knight.srt"}]
            }},
            {"id": "2", "type": "subtitle", "attributes": {
              "language": "en",
              "download_count": 1024,
              "hearing_impaired": true,
              "release": "The Dark Knight 2008 DVDRip",
              "files": [{"file_id": 123999, "file_name": "dk.sdh.srt"}]
            }},
            {"id": "3", "type": "subtitle", "attributes": {
              "language": "en", "files": []
            }}
          ]
        }
    """.trimIndent()

    @Test
    fun `parses search results and skips file-less entries`() {
        val results = OpenSubtitlesApi.parseSearchResponse(searchBody)

        assertEquals(2, results.size)
        val first = results[0]
        assertEquals(123456, first.fileId)
        assertEquals("en", first.language)
        assertEquals(52341, first.downloadCount)
        assertEquals(8.5, first.rating!!, 0.001)
        assertEquals(false, first.hearingImpaired)
        assertEquals(
            "The.Dark.Knight.2008.1080p.BluRay.x264",
            first.displayLabel
        )
        assertEquals(true, results[1].hearingImpaired)
    }

    @Test
    fun `parses the download link exchange`() {
        val ready = OpenSubtitlesApi.parseDownloadResponse(
            """{"link": "https://cdn.example.com/f.srt", "file_name": "f.srt", "requests": 1}"""
        )
        assertEquals(
            SubtitleDownloadOutcome.Ready("https://cdn.example.com/f.srt", "f.srt"),
            ready
        )
        assertEquals(
            SubtitleDownloadOutcome.Failed,
            OpenSubtitlesApi.parseDownloadResponse("""{"message": "nope"}""")
        )
    }

    @Test
    fun `quota wording is recognised in error bodies`() {
        assertTrue(OpenSubtitlesApi.looksLikeQuota("Your download limit has been reached"))
        assertTrue(OpenSubtitlesApi.looksLikeQuota("Daily quota exceeded"))
        assertTrue(!OpenSubtitlesApi.looksLikeQuota("Invalid API key"))
    }

    @Test
    fun `malformed search parses to empty`() {
        assertTrue(OpenSubtitlesApi.parseSearchResponse("{}").isEmpty())
        assertTrue(OpenSubtitlesApi.parseSearchResponse("garbage").isEmpty())
    }
}
