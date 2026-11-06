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
 * Tests for the AniList GraphQL response parser, fed with a sample in
 * the documented AniList shape (the endpoint itself is unreachable
 * from this VM — Cloudflare 403 on a datacenter IP — so the sample is
 * hand-built from the query fields the app requests). org.json is an
 * Android-framework class, so this runs under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AniListApiTest {

    // Shape mirrors the TRENDING_QUERY fields: id, title{romaji,english,
    // native}, description, averageScore, seasonYear, coverImage, genres.
    private val sampleResponse = """
        {
          "data": {"Page": {"media": [
            {"id": 21, "title": {"romaji": "One Piece", "english": "One Piece", "native": "ONE PIECE"},
             "description": "Monkey D. Luffy sails the seas.",
             "averageScore": 87, "seasonYear": 1999,
             "coverImage": {"large": "https://s4.anilist.co/onepiece.jpg"},
             "genres": ["Action", "Adventure"]},
            {"id": 5114, "title": {"romaji": "Hagane no Renkinjutsushi", "english": null, "native": "鋼の錬金術師"},
             "description": "Two brothers search for the Philosopher's Stone.",
             "averageScore": null, "seasonYear": 2009,
             "coverImage": {"large": "https://s4.anilist.co/fma.jpg"},
             "genres": ["Action", "Drama"]},
            {"id": 9999, "title": {"romaji": null, "english": null, "native": null},
             "description": "No title at all.", "averageScore": 50, "seasonYear": 2020,
             "coverImage": {}, "genres": []}
          ]}}
        }
    """.trimIndent()

    @Test
    fun `parses media into anime titles with normalised scores`() {
        val titles = AniListApi.parseMediaList(sampleResponse)

        assertEquals(2, titles.size) // the title-less entry is skipped

        val first = titles[0]
        assertEquals(21, first.anilistId)
        assertEquals("One Piece", first.name) // english preferred
        assertEquals(MediaType.ANIME, first.mediaType)
        assertEquals(8.7, first.rating!!, 0.001) // 87 -> 8.7
        assertEquals("1999", first.year)
        assertEquals("https://s4.anilist.co/onepiece.jpg", first.posterUrl)
        assertEquals(listOf("Action", "Adventure"), first.genres)
        assertEquals("anilist:21", first.stableKey)

        val second = titles[1]
        assertEquals("Hagane no Renkinjutsushi", second.name) // romaji fallback
        assertNull(second.rating) // null score stays null
        assertEquals("2009", second.year)
    }

    @Test
    fun `empty or malformed bodies parse to an empty list`() {
        assertTrue(AniListApi.parseMediaList("{}").isEmpty())
        assertTrue(AniListApi.parseMediaList("""{"data": {"Page": {"media": []}}}""").isEmpty())
        assertTrue(AniListApi.parseMediaList("not json").isEmpty())
    }
}
