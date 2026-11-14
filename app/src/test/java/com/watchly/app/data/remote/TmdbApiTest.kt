package com.watchly.app.data.remote

import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.WatchCategory
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

    // watch/providers: one PK entry (flatrate + rent + buy, with the
    // same service in rent and buy) and one US entry, in the real v3
    // shape TMDB returns for /movie/:id/watch/providers.
    private val sampleWatchProviders = """
        {"id": 579974, "results": {
          "PK": {
            "link": "https://www.themoviedb.org/movie/579974/watch?locale=PK",
            "flatrate": [
              {"provider_id": 8, "provider_name": "Netflix",
               "logo_path": "/netflix.jpg", "display_priority": 0}
            ],
            "rent": [
              {"provider_id": 2, "provider_name": "Apple TV",
               "logo_path": "/apple.jpg", "display_priority": 1}
            ],
            "buy": [
              {"provider_id": 2, "provider_name": "Apple TV",
               "logo_path": "/apple.jpg", "display_priority": 1},
              {"provider_id": 3, "provider_name": "Google Play Movies",
               "logo_path": null, "display_priority": 2}
            ]
          },
          "US": {
            "link": "https://www.themoviedb.org/movie/579974/watch?locale=US",
            "flatrate": [
              {"provider_id": 9, "provider_name": "Amazon Prime Video",
               "logo_path": "/prime.jpg", "display_priority": 0}
            ]
          }
        }}
    """.trimIndent()

    @Test
    fun `parses watch providers for the requested region`() {
        val watch = TmdbApi.parseWatchProviders(sampleWatchProviders, "PK")

        assertEquals("PK", watch.region)
        assertEquals(
            "https://www.themoviedb.org/movie/579974/watch?locale=PK",
            watch.watchPageUrl
        )
        // Apple TV is in both rent and buy: shown once, as RENT.
        assertEquals(
            listOf("Netflix", "Apple TV", "Google Play Movies"),
            watch.providers.map { it.name }
        )
        assertEquals(
            listOf(
                WatchCategory.STREAM,
                WatchCategory.RENT,
                WatchCategory.BUY
            ),
            watch.providers.map { it.category }
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w92/netflix.jpg",
            watch.providers[0].logoUrl
        )
        assertNull(watch.providers[2].logoUrl)
    }

    @Test
    fun `watch providers fall back to US when the region has no entry`() {
        val watch = TmdbApi.parseWatchProviders(sampleWatchProviders, "FR")

        assertEquals("US", watch.region)
        assertEquals(
            listOf("Amazon Prime Video"),
            watch.providers.map { it.name }
        )
    }

    @Test
    fun `find by imdb id prefers the expected media type`() {
        // Phase D: TVmaze/AniList titles resolve to TMDB this way.
        val both = """{"movie_results":[{"id": 11}],"tv_results":[{"id": 22}]}"""
        assertEquals(11, TmdbApi.parseFind(both, MediaType.MOVIE))
        assertEquals(22, TmdbApi.parseFind(both, MediaType.TV))
        assertEquals(22, TmdbApi.parseFind(both, MediaType.ANIME))

        val tvOnly = """{"movie_results":[],"tv_results":[{"id": 33}]}"""
        assertEquals(33, TmdbApi.parseFind(tvOnly, MediaType.MOVIE))

        assertNull(TmdbApi.parseFind("""{"movie_results":[],"tv_results":[]}""", MediaType.MOVIE))
        assertNull(TmdbApi.parseFind("not json", MediaType.TV))
        assertNull(TmdbApi.parseFind("{}", MediaType.TV))
    }

    @Test
    fun `watch providers are honestly empty when no region matches`() {
        val usOnlyGone = """{"id": 1, "results": {"DE": {"link": "x"}}}"""
        val watch = TmdbApi.parseWatchProviders(usOnlyGone, "PK")

        assertEquals("PK", watch.region)
        assertNull(watch.watchPageUrl)
        assertTrue(watch.providers.isEmpty())
        assertTrue(TmdbApi.parseWatchProviders("not json", "PK").providers.isEmpty())
        assertTrue(TmdbApi.parseWatchProviders("{}", "PK").providers.isEmpty())
    }

    // ------------------------------------------------------------------
    // Cycle 10: trailer + recommendations
    // ------------------------------------------------------------------

    @Test
    fun `trailer pick prefers the official youtube trailer`() {
        val body = """
            {"id": 1, "results": [
              {"key": "vimeo1", "site": "Vimeo", "type": "Trailer", "official": true},
              {"key": "teaser1", "site": "YouTube", "type": "Teaser", "official": true},
              {"key": "trailerUnofficial", "site": "YouTube", "type": "Trailer", "official": false},
              {"key": "trailerOfficial", "site": "YouTube", "type": "Trailer", "official": true}
            ]}
        """.trimIndent()
        assertEquals("trailerOfficial", TmdbApi.parseTrailerKey(body))
    }

    @Test
    fun `trailer pick falls back through trailer then teaser`() {
        val noOfficial = """
            {"results": [
              {"key": "teaser1", "site": "YouTube", "type": "Teaser", "official": true},
              {"key": "trailer1", "site": "YouTube", "type": "Trailer", "official": false}
            ]}
        """.trimIndent()
        assertEquals("trailer1", TmdbApi.parseTrailerKey(noOfficial))

        val teaserOnly = """
            {"results": [
              {"key": "teaser1", "site": "YouTube", "type": "Teaser", "official": false}
            ]}
        """.trimIndent()
        assertEquals("teaser1", TmdbApi.parseTrailerKey(teaserOnly))
    }

    @Test
    fun `trailer pick is null without a usable youtube video`() {
        assertNull(TmdbApi.parseTrailerKey("""{"results": []}"""))
        assertNull(
            TmdbApi.parseTrailerKey(
                """{"results": [{"key": "v1", "site": "Vimeo", "type": "Trailer"}]}"""
            )
        )
        assertNull(TmdbApi.parseTrailerKey("not json"))
        assertNull(TmdbApi.parseTrailerKey("{}"))
    }

    @Test
    fun `recommendations parse movie rows and tv rows by shape`() {
        val body = """
            {"page": 1, "results": [
              {"id": 201, "title": "Similar Film", "poster_path": "/s.jpg",
               "release_date": "2019-05-01", "vote_average": 7.1, "genre_ids": [28]},
              {"id": 202, "name": "Similar Show", "poster_path": "/t.jpg",
               "first_air_date": "2018-03-02", "vote_average": 8.2, "genre_ids": [18]}
            ]}
        """.trimIndent()
        val titles = TmdbApi.parseRecommendations(body)
        assertEquals(2, titles.size)
        assertEquals(MediaType.MOVIE, titles[0].mediaType)
        assertEquals(201, titles[0].tmdbId)
        assertEquals("2019", titles[0].year)
        assertEquals(listOf("Action"), titles[0].genres)
        assertEquals(MediaType.TV, titles[1].mediaType)
        assertEquals("Similar Show", titles[1].name)
        assertEquals("2018", titles[1].year)
        // TV genres come from the TV table (18 = Drama there too).
        assertEquals(listOf("Drama"), titles[1].genres)
    }

    @Test
    fun `recommendations are empty for empty or broken bodies`() {
        assertTrue(TmdbApi.parseRecommendations("""{"results": []}""").isEmpty())
        assertTrue(TmdbApi.parseRecommendations("not json").isEmpty())
        assertTrue(TmdbApi.parseRecommendations("{}").isEmpty())
    }

    // ------------------------------------------------------------------
    // Cycle 11 (§3.3): person pages
    // ------------------------------------------------------------------

    @Test
    fun `credits carry the tmdb person id for person pages`() {
        val body = """
            {"id": 579974, "cast": [
              {"id": 94724, "name": "N. T. Rama Rao Jr.",
               "character": "Komaram Bheem", "profile_path": "/ntr.jpg",
               "order": 0},
              {"name": "No Id Row", "character": "Extra",
               "profile_path": null, "order": 1},
              {"id": 0, "name": "Zero Id Row", "character": "Extra",
               "profile_path": null, "order": 2}
            ]}
        """.trimIndent()
        val cast = TmdbApi.parseCredits(body)
        assertEquals(3, cast.size)
        // A real TMDB id is carried through — the person page key.
        assertEquals(94724, cast[0].tmdbPersonId)
        // Missing / zero ids stay null: no page is invented for them.
        assertNull(cast[1].tmdbPersonId)
        assertNull(cast[2].tmdbPersonId)
    }

    @Test
    fun `person parses with biography department and photo`() {
        val body = """
            {"id": 94724, "name": "N. T. Rama Rao Jr.",
             "biography": "An Indian actor who works in Telugu cinema.",
             "known_for_department": "Acting",
             "profile_path": "/ntr.jpg", "birthday": "1983-05-20"}
        """.trimIndent()
        val person = TmdbApi.parsePerson(body)
        assertEquals(94724, person?.tmdbId)
        assertEquals("N. T. Rama Rao Jr.", person?.name)
        assertEquals(
            "An Indian actor who works in Telugu cinema.",
            person?.biography
        )
        assertEquals("Acting", person?.knownForDepartment)
        assertEquals(
            "https://image.tmdb.org/t/p/w342/ntr.jpg",
            person?.photoUrl
        )
    }

    @Test
    fun `person with a blank biography keeps it null`() {
        // TMDB lists many people with no bio at all — the page's
        // honest "no biography yet" state depends on null here,
        // not an empty string rendered as a blank section.
        val body = """
            {"id": 555, "name": "Obscure Person", "biography": "  ",
             "known_for_department": "Acting", "profile_path": null}
        """.trimIndent()
        val person = TmdbApi.parsePerson(body)
        assertEquals(555, person?.tmdbId)
        assertNull(person?.biography)
        assertNull(person?.photoUrl)
    }

    @Test
    fun `person is null without a name or id`() {
        assertNull(TmdbApi.parsePerson("""{"id": 5, "biography": "x"}"""))
        assertNull(TmdbApi.parsePerson("""{"name": "No Id"}"""))
        assertNull(TmdbApi.parsePerson("not json"))
    }

    @Test
    fun `combined credits dispatch by media type dedupe and sort by popularity`() {
        val body = """
            {"id": 94724, "cast": [
              {"id": 579974, "media_type": "movie", "title": "RRR",
               "character": "Komaram Bheem", "popularity": 45.2,
               "release_date": "2022-03-24", "poster_path": "/rrr.jpg",
               "vote_average": 7.8, "genre_ids": [28]},
              {"id": 579974, "media_type": "movie", "title": "RRR",
               "character": "Komaram Bheem (voice)", "popularity": 45.2,
               "release_date": "2022-03-24", "poster_path": "/rrr.jpg",
               "vote_average": 7.8, "genre_ids": [28]},
              {"id": 91363, "media_type": "tv", "name": "Bigg Boss Telugu",
               "character": "Host", "popularity": 12.9,
               "first_air_date": "2017-07-16", "poster_path": null,
               "vote_average": 6.4, "genre_ids": [10764]},
              {"id": 4242, "media_type": "person", "name": "Not A Title",
               "popularity": 99.9},
              {"id": 693134, "media_type": "movie", "title": "Dune: Part Two",
               "character": "Extra", "popularity": 60.0,
               "release_date": "2024-03-01", "poster_path": "/dune.jpg",
               "vote_average": 8.2, "genre_ids": [878]}
            ]}
        """.trimIndent()
        val titles = TmdbApi.parseCombinedCredits(body)
        // The duplicate RRR row (second character) collapses, and
        // the "person" row is not a title at all.
        assertEquals(3, titles.size)
        // Popularity order: Dune (60.0) > RRR (45.2) > Bigg Boss (12.9).
        assertEquals("Dune: Part Two", titles[0].name)
        assertEquals(MediaType.MOVIE, titles[0].mediaType)
        assertEquals("RRR", titles[1].name)
        assertEquals(579974, titles[1].tmdbId)
        assertEquals("Bigg Boss Telugu", titles[2].name)
        assertEquals(MediaType.TV, titles[2].mediaType)
        assertEquals(listOf("Reality"), titles[2].genres)
    }

    @Test
    fun `combined credits are empty for empty or broken bodies`() {
        assertTrue(TmdbApi.parseCombinedCredits("""{"cast": []}""").isEmpty())
        assertTrue(TmdbApi.parseCombinedCredits("not json").isEmpty())
        assertTrue(TmdbApi.parseCombinedCredits("{}").isEmpty())
    }
}
