package com.watchly.app.data.metadata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Pure JVM unit test — no Android framework, no emulator, no network.
 *
 * Copy to: app/src/test/java/com/watchly/app/data/metadata/
 * Run:     ./gradlew :app:testDebugUnitTest --tests "com.watchly.app.data.metadata.CanonicalIdMapperTest"
 *
 * Dependencies: see gradle-snippet.kts in this folder (JUnit 5).
 */
class CanonicalIdMapperTest {

    @Test
    fun `movie with a TMDB id maps to a tmdb canonical id`() {
        val canonical = CanonicalIdMapper.toCanonical(
            MediaType.MOVIE,
            ExternalIds(tmdbId = 603),
        )
        assertEquals("watchly:movie:tmdb:603", canonical)
    }

    @Test
    fun `tmdb wins over imdb and tvdb for movies`() {
        val canonical = CanonicalIdMapper.toCanonical(
            MediaType.MOVIE,
            ExternalIds(tmdbId = 603, imdbId = "tt0133093", tvdbId = 12345),
        )
        assertEquals("watchly:movie:tmdb:603", canonical)
    }

    @Test
    fun `imdb id is used when tmdb is missing, and is normalised`() {
        val canonical = CanonicalIdMapper.toCanonical(
            MediaType.TV,
            ExternalIds(imdbId = "  TT0944947 "),
        )
        assertEquals("watchly:tv:imdb:tt0944947", canonical)
    }

    @Test
    fun `blank imdb id counts as missing`() {
        val canonical = CanonicalIdMapper.toCanonical(
            MediaType.TV,
            ExternalIds(imdbId = "   ", tvdbId = 121361),
        )
        assertEquals("watchly:tv:tvdb:121361", canonical)
    }

    @Test
    fun `anilist wins over tmdb for anime`() {
        val canonical = CanonicalIdMapper.toCanonical(
            MediaType.ANIME,
            ExternalIds(anilistId = 21, tmdbId = 37854),
        )
        assertEquals("watchly:anime:anilist:21", canonical)
    }

    @Test
    fun `mal is used for anime when anilist is missing`() {
        val canonical = CanonicalIdMapper.toCanonical(
            MediaType.ANIME,
            ExternalIds(malId = 21, tmdbId = 37854),
        )
        assertEquals("watchly:anime:mal:21", canonical)
    }

    @Test
    fun `non-positive numeric ids count as missing`() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalIdMapper.toCanonical(MediaType.MOVIE, ExternalIds(tmdbId = 0))
        }
    }

    @Test
    fun `no ids at all is an error, not a made-up id`() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalIdMapper.toCanonical(MediaType.MOVIE, ExternalIds())
        }
    }

    @Test
    fun `parse round-trips a canonical id`() {
        val parsed = CanonicalIdMapper.parse("watchly:movie:tmdb:603")
        assertEquals(
            ParsedCanonicalId(MediaType.MOVIE, IdProvider.TMDB, "603"),
            parsed,
        )
        // And back again:
        val rebuilt = CanonicalIdMapper.toCanonical(
            parsed.mediaType,
            ExternalIds(tmdbId = parsed.externalId.toInt()),
        )
        assertEquals("watchly:movie:tmdb:603", rebuilt)
    }

    @Test
    fun `parse rejects malformed ids`() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalIdMapper.parse("tmdb:603")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalIdMapper.parse("watchly:book:tmdb:603")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalIdMapper.parse("watchly:movie:tmdb:")
        }
    }
}
