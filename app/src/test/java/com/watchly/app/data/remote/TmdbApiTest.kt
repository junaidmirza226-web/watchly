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
 * Tests for the TMDB parsers, fed with trimmed responses in the real
 * TMDB v3 shape. org.json is an Android-framework class, so this runs
 * under Robolectric — still a JVM test, no emulator, no network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TmdbApiTest {

    // Discover-style results: a Hindi film with full fields, and one
    // with no poster, no votes and an unknown genre id (9999).
    private val sampleList = """
        {"page": 1, "total_pages": 5, "results": [
          {"id": 579974, "title": "RRR", "original_title": "RRR",
           "overview": "A fearless warrior rides to rescue a young girl.",
           "poster_path": "/nEufeZlyAOLqO2J2J2K1Y.jpg",
           "release_date": "2022-03-24", "vote_average": 7.8,
           "genre_ids": [28, 18, 9999]},
          {"id": 999, "title": "Obscure Film", "poster_path": null,
           "release_date": "", "vote_average": 0, "genre_ids": []}
        ]}
    """.trimIndent()

    private val sampleDetails = """
        {"id": 579974, "title": "RRR", "overview": "Full overview.",
         "poster_path": "/poster.jpg", "release_date": "2022-03-24",
         "vote_average": 7.8, "runtime": 187,
         "imdb_id": "tt8178634", "status": "Released",
         "genres": [{"id": 28, "name": "Action"}, {"id": 18, "name": "Drama"}]}
    """.trimIndent()

    private val sampleCredits = """
        {"id": 579974, "cast": [
          {"name": "N. T. Rama Rao Jr.", "character": "Komaram Bheem",
           "profile_path": "/ntr.jpg", "order": 0},
          {"name": "Ram Charan", "character": "Alluri Sitarama Raju",
           "profile_path": null, "order": 1},
          {"name": "", "character": "Ghost", "profile_path": null, "order": 2}
        ]}
    """.trimIndent()

    @Test
    fun `parses discover results into canonical titles`() {
        val titles = TmdbApi.parseMovieList(sampleList)

        assertEquals(2, titles.size)
        val rrr = titles[0]
        assertEquals(579974, rrr.tmdbId)
        assertEquals("RRR", rrr.name)
        assertEquals(MediaType.MOVIE, rrr.mediaType)
        assertEquals("2022", rrr.year)
        assertEquals(7.8, rrr.rating!!, 0.01)
        assertEquals(
            "https://image.tmdb.org/t/p/w500/nEufeZlyAOLqO2J2J2K1Y.jpg",
            rrr.posterUrl
        )
        // Unknown genre id 9999 is skipped, known ones map to names.
        assertEquals(listOf("Action", "Drama"), rrr.genres)
        assertEquals("tmdb:579974", rrr.stableKey)
    }

    @Test
    fun `missing poster rating and date stay null`() {
        val obscure = TmdbApi.parseMovieList(sampleList)[1]
        assertNull(obscure.posterUrl)
        assertNull(obscure.rating) // vote_average 0 means "no votes" on TMDB
        assertNull(obscure.year)
        assertTrue(obscure.genres.isEmpty())
    }

    @Test
    fun `parses full movie details with runtime genres and imdb id`() {
        val details = TmdbApi.parseMovieDetails(sampleDetails)!!

        assertEquals("RRR", details.name)
        assertEquals(187, details.runtimeMinutes)
        assertEquals(listOf("Action", "Drama"), details.genres)
        assertEquals("tt8178634", details.imdbId)
        assertEquals("Released", details.status)
        assertEquals("Full overview.", details.overview)
    }

    @Test
    fun `parses cast with photos and skips nameless rows`() {
        val cast = TmdbApi.parseCredits(sampleCredits)

        assertEquals(2, cast.size)
        assertEquals("N. T. Rama Rao Jr.", cast[0].personName)
        assertEquals("Komaram Bheem", cast[0].characterName)
        assertEquals("https://image.tmdb.org/t/p/w185/ntr.jpg", cast[0].imageUrl)
        assertEquals("Ram Charan", cast[1].personName)
        assertNull(cast[1].imageUrl)
    }

    @Test
    fun `malformed bodies parse to empty results`() {
        assertTrue(TmdbApi.parseMovieList("{}").isEmpty())
        assertTrue(TmdbApi.parseMovieList("not json").isEmpty())
        assertNull(TmdbApi.parseMovieDetails("{}"))
        assertTrue(TmdbApi.parseCredits("{}").isEmpty())
    }

    @Test
    fun `genre name lookup round trips`() {
        assertEquals(28, TmdbApi.genreIdFor("Action"))
        assertEquals(878, TmdbApi.genreIdFor("Science Fiction"))
        assertNull(TmdbApi.genreIdFor("Not A Genre"))
        assertNull(TmdbApi.genreIdFor(null))
    }

    // search/multi: one movie, one TV show and one person row, in the
    // real v3 shape. Person rows are skipped (no person pages in v1);
    // TV rows map through the TV genre table, not the movie one.
    private val sampleSearchMulti = """
        {"page": 1, "results": [
          {"id": 579974, "media_type": "movie", "title": "RRR",
           "original_title": "RRR",
           "overview": "A fearless warrior rides to rescue a young girl.",
           "poster_path": "/nEufeZlyAOLqO2J2J2K1Y.jpg",
           "release_date": "2022-03-24", "vote_average": 7.8,
           "genre_ids": [28, 18]},
          {"id": 1399, "media_type": "tv", "name": "Game of Thrones",
           "original_name": "Game of Thrones",
           "overview": "Nine noble families fight for control.",
           "poster_path": "/got.jpg",
           "first_air_date": "2011-04-17", "vote_average": 8.4,
           "genre_ids": [10765, 18, 10759]},
          {"id": 819, "media_type": "person", "name": "Edward Norton",
           "known_for_department": "Acting", "popularity": 12.3},
          {"id": 1000, "media_type": "movie", "title": "No Date Film",
           "release_date": "", "vote_average": 0, "genre_ids": []}
        ]}
    """.trimIndent()

    @Test
    fun `parses search multi into movies and shows skipping people`() {
        val titles = TmdbApi.parseSearchMulti(sampleSearchMulti)

        assertEquals(3, titles.size)
        val movie = titles[0]
        assertEquals(579974, movie.tmdbId)
        assertEquals("RRR", movie.name)
        assertEquals(MediaType.MOVIE, movie.mediaType)
        assertEquals("2022", movie.year)
        assertEquals(listOf("Action", "Drama"), movie.genres)
        assertEquals("tmdb:579974", movie.stableKey)

        val show = titles[1]
        assertEquals(1399, show.tmdbId)
        assertEquals("Game of Thrones", show.name)
        assertEquals(MediaType.TV, show.mediaType)
        assertEquals("2011", show.year)
        assertEquals(8.4, show.rating!!, 0.01)
        // TV genre ids map through the TV table (10765, 10759 differ
        // from the movie table).
        assertEquals(
            listOf("Sci-Fi & Fantasy", "Drama", "Action & Adventure"),
            show.genres
        )
        assertEquals("tmdb:1399", show.stableKey)
    }

    @Test
    fun `search multi keeps nulls for missing fields`() {
        val film = TmdbApi.parseSearchMulti(sampleSearchMulti)[2]
        assertEquals("No Date Film", film.name)
        assertNull(film.posterUrl)
        assertNull(film.rating)
        assertNull(film.year)
        assertTrue(film.genres.isEmpty())
    }

    @Test
    fun `malformed search multi parses to empty`() {
        assertTrue(TmdbApi.parseSearchMulti("{}").isEmpty())
        assertTrue(TmdbApi.parseSearchMulti("not json").isEmpty())
        assertTrue(TmdbApi.parseSearchMulti("""{"results": []}""").isEmpty())
    }
}
