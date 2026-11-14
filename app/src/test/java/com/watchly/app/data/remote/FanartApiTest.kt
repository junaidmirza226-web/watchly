package com.watchly.app.data.remote

import com.watchly.app.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the Fanart parser (cycle 4). Fixture shapes mirror the
 * live Dark Knight record verified 2026-11-06 (hdmovielogo /
 * moviebackground for films, hdtvlogo / showbackground for shows;
 * every entry has url, lang and likes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FanartApiTest {

    private val movieRecord = """
        {
          "name": "The Dark Knight",
          "tmdb_id": "155",
          "hdmovielogo": [
            {"id": "1", "url": "https://assets.fanart.tv/logo-de.png",
             "lang": "de", "likes": "40"},
            {"id": "2", "url": "https://assets.fanart.tv/logo-en.png",
             "lang": "en", "likes": "12"}
          ],
          "moviebackground": [
            {"id": "3", "url": "https://assets.fanart.tv/bg-a.jpg",
             "lang": "en", "likes": "30"},
            {"id": "4", "url": "https://assets.fanart.tv/bg-b.jpg",
             "lang": "en", "likes": "55"}
          ]
        }
    """.trimIndent()

    private val showRecord = """
        {
          "name": "Breaking Bad",
          "thetvdb_id": "81189",
          "hdtvlogo": [
            {"id": "9", "url": "https://assets.fanart.tv/bb-logo.png",
             "lang": "en", "likes": "20"}
          ]
        }
    """.trimIndent()

    @Test
    fun `english logo wins over a more-liked foreign one`() {
        val artwork = FanartApi.parseArtwork(movieRecord, MediaType.MOVIE)

        // A logo is text: the German wordmark would read wrong on an
        // English page, so lang "en" outranks raw likes.
        assertEquals("https://assets.fanart.tv/logo-en.png", artwork!!.logoUrl)
        // Backdrops have no text: most likes wins within English.
        assertEquals("https://assets.fanart.tv/bg-b.jpg", artwork.backdropUrl)
    }

    @Test
    fun `show records use the tv keys`() {
        val artwork = FanartApi.parseArtwork(showRecord, MediaType.TV)

        assertEquals("https://assets.fanart.tv/bb-logo.png", artwork!!.logoUrl)
        assertNull(artwork.backdropUrl)
    }

    @Test
    fun `empty and malformed records carry no artwork`() {
        val empty = FanartApi.parseArtwork("""{"name": "Obscure"}""", MediaType.MOVIE)
        assertEquals(true, empty!!.isEmpty)
        assertNull(FanartApi.parseArtwork("garbage", MediaType.MOVIE))
    }
}
