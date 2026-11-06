package com.watchly.app.data.remote

import com.watchly.app.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the Internet Archive response parser, fed with a saved
 * advancedsearch response (shape verified live 2026-11-06, see
 * sources-test/archive-search.json). org.json is an Android-framework
 * class, so this runs under Robolectric — still a JVM test, no emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchiveApiTest {

    // Trimmed real response: two normal docs, one missing `year`,
    // one missing `identifier` (skipped), one missing `title` (skipped).
    private val sampleResponse = """
        {
          "responseHeader": {"status": 0},
          "response": {
            "numFound": 28535, "start": 0,
            "docs": [
              {"identifier": "nightofthelivingdead_201910",
               "title": "Night Of The Living Dead", "year": 1968},
              {"identifier": "silent-thomas-graals-best-child",
               "title": "Thomas Graal's Best Child"},
              {"title": "No Identifier Here", "year": 2001},
              {"identifier": "no-title-here", "year": 2002}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `parses docs into movie titles with archive ids and posters`() {
        val titles = ArchiveApi.parseSearchResponse(sampleResponse)

        assertEquals(2, titles.size)

        val first = titles[0]
        assertEquals("nightofthelivingdead_201910", first.archiveId)
        assertEquals("Night Of The Living Dead", first.name)
        assertEquals("1968", first.year)
        assertEquals(MediaType.MOVIE, first.mediaType)
        assertEquals(
            "https://archive.org/services/img/nightofthelivingdead_201910",
            first.posterUrl
        )
        assertEquals("archive:nightofthelivingdead_201910", first.stableKey)

        val second = titles[1]
        assertEquals("silent-thomas-graals-best-child", second.archiveId)
        assertNull(second.year) // year is optional
    }

    @Test
    fun `empty or malformed bodies parse to an empty list`() {
        assertTrue(ArchiveApi.parseSearchResponse("{}").isEmpty())
        assertTrue(
            ArchiveApi.parseSearchResponse("""{"response": {"docs": []}}""").isEmpty()
        )
    }

    @Test
    fun `movie genre map covers the chips the Movies tab shows`() {
        assertTrue(ArchiveApi.MOVIE_GENRES.containsKey("Horror"))
        assertTrue(ArchiveApi.MOVIE_GENRES.containsKey("Sci-Fi"))
        assertTrue(ArchiveApi.MOVIE_GENRES.values.all { it.isNotBlank() })
    }

    // Trimmed real metadata (see sources-test/archive-item.json):
    // one MPEG4 mp4, one Ogg Video file, a thumbnail and a nameless row.
    private val sampleMetadata = """
        {
          "files": [
            {"name": "Night Of The Living Dead.mp4", "format": "MPEG4",
             "size": "1625672967"},
            {"name": "Night Of The Living Dead.ogv", "format": "Ogg Video",
             "size": "426616417"},
            {"name": "__ia_thumb.jpg", "format": "Item Tile", "size": "8377"},
            {"format": "MPEG4", "size": "123"}
          ]
        }
    """.trimIndent()

    @Test
    fun `parses metadata into playable video files largest first`() {
        val files = ArchiveApi.parseMetadataFiles(
            "nightofthelivingdead_201910",
            sampleMetadata
        )

        assertEquals(2, files.size) // thumbnails and nameless rows skipped

        val mp4 = files[0]
        assertEquals(
            "https://archive.org/download/nightofthelivingdead_201910/Night Of The Living Dead.mp4",
            mp4.url
        )
        assertEquals("MPEG4", mp4.format)
        assertEquals(1_625_672_967L, mp4.sizeBytes)
        assertEquals("1.6 GB", mp4.sizeLabel)

        val ogv = files[1]
        assertEquals("Ogg Video", ogv.format)
        assertEquals("426 MB", ogv.sizeLabel)
    }

    @Test
    fun `empty or malformed metadata parses to an empty list`() {
        assertTrue(ArchiveApi.parseMetadataFiles("x", "{}").isEmpty())
        assertTrue(ArchiveApi.parseMetadataFiles("x", "not json").isEmpty())
    }
}
