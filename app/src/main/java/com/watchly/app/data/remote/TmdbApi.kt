/*
 * Watchly — TMDB client (user-supplied API key, v3).
 *
 * TMDB is the self-updating catalogue database: community editors add
 * new films and shows daily across every industry — Hollywood,
 * Bollywood (Hindi), Tollywood (Telugu), Kollywood (Tamil),
 * Mollywood (Malayalam), Punjabi, Bengali, Korean and more. Watchly
 * never mirrors the database; it asks TMDB live, filtered by original
 * language for the industry rows.
 *
 * The API key belongs to the user (free TMDB signup). It is passed in
 * per call from TmdbKeyRepository — this object holds no key of its
 * own and there is no key anywhere in the source. Calls happen only
 * when a key exists; without one the UI hides TMDB sections.
 *
 * ASSUMPTIONS (same stack as TvMazeApi / ArchiveApi):
 *  - HTTP: java.net.HttpURLConnection. JSON: org.json.
 *  - Concurrency: kotlinx-coroutines-android (IO dispatcher).
 *  - Requires the INTERNET permission in AndroidManifest.xml.
 *
 * Endpoints used (https://api.themoviedb.org/3):
 *  - GET /configuration                          → key validation
 *  - GET /discover/movie?with_original_language= → industry / catalogue rows
 *  - GET /search/multi?query=                    → unified search (movies + TV)
 *  - GET /movie/:id                              → full movie details
 *  - GET /movie/:id/credits                      → movie cast
 *  - GET /movie/:id/watch/providers              → where-to-watch (movie)
 *  - GET /tv/:id/watch/providers                 → where-to-watch (TV)
 * All failures throw; callers catch and degrade gracefully.
 */
package com.watchly.app.data.remote

import com.watchly.app.data.model.CastMember
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.data.model.WatchCategory
import com.watchly.app.data.model.WatchProvider
import com.watchly.app.data.model.WatchProviders
import com.watchly.app.data.model.combineWatchProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object TmdbApi {

    private const val BASE_URL = "https://api.themoviedb.org/3"
    private const val IMAGE_BASE = "https://image.tmdb.org/t/p"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** Sort orders for discover, mapped from the browse tab's sorts. */
    enum class MovieSort(val sortParam: String) {
        POPULAR("popularity.desc"),
        TOP_RATED("vote_average.desc"),
        NEWEST("primary_release_date.desc"),
        A_Z("original_title.asc")
    }

    /**
     * TMDB movie genre ids → display names (the stable core list).
     * TMDB rarely changes these; unknown ids are simply skipped.
     */
    val MOVIE_GENRES: Map<Int, String> = linkedMapOf(
        28 to "Action",
        12 to "Adventure",
        16 to "Animation",
        35 to "Comedy",
        80 to "Crime",
        99 to "Documentary",
        18 to "Drama",
        10751 to "Family",
        14 to "Fantasy",
        36 to "History",
        27 to "Horror",
        10402 to "Music",
        9648 to "Mystery",
        10749 to "Romance",
        878 to "Science Fiction",
        53 to "Thriller",
        10752 to "War",
        37 to "Western"
    )

    /** Genre display name → TMDB genre id (for the Movies tab chips). */
    fun genreIdFor(name: String?): Int? =
        name?.let { wanted ->
            MOVIE_GENRES.entries.firstOrNull { it.value == wanted }?.key
        }

    /**
     * TMDB TV genre ids → display names. The TV table differs from the
     * movie table (e.g. 10759 "Action & Adventure", 10765 "Sci-Fi &
     * Fantasy"), so search results map TV genres through this, not
     * [MOVIE_GENRES]. Unknown ids are skipped, as with movies.
     */
    val TV_GENRES: Map<Int, String> = linkedMapOf(
        10759 to "Action & Adventure",
        16 to "Animation",
        35 to "Comedy",
        80 to "Crime",
        99 to "Documentary",
        18 to "Drama",
        10751 to "Family",
        10762 to "Kids",
        9648 to "Mystery",
        10763 to "News",
        10764 to "Reality",
        10765 to "Sci-Fi & Fantasy",
        10766 to "Soap",
        10767 to "Talk",
        10768 to "War & Politics",
        37 to "Western"
    )

    // ------------------------------------------------------------------
    // Network calls
    // ------------------------------------------------------------------

    /**
     * True when TMDB accepts [apiKey]. A 401 means the key itself is
     * wrong (returns false); network failures throw so the caller can
     * tell "bad key" apart from "no connection".
     */
    suspend fun validateKey(apiKey: String): Boolean =
        withContext(Dispatchers.IO) {
            val connection = openConnection("$BASE_URL/configuration?api_key=${apiKey.enc()}")
            try {
                connection.responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        }

    /**
     * One page of the movie catalogue via discover. [language] is an
     * ISO 639-1 original-language code ("hi" Bollywood, "te"
     * Tollywood, …) or null for all languages. [genreId] filters by
     * TMDB genre. Results are canonical [Title]s with tmdbId set.
     */
    suspend fun discoverMovies(
        apiKey: String,
        language: String? = null,
        genreId: Int? = null,
        sort: MovieSort = MovieSort.POPULAR,
        page: Int = 1
    ): List<Title> = withContext(Dispatchers.IO) {
        val params = StringBuilder()
        params.append("?api_key=").append(apiKey.enc())
        params.append("&include_adult=false")
        params.append("&page=").append(page)
        params.append("&sort_by=").append(sort.sortParam.enc())
        language?.let { params.append("&with_original_language=").append(it.enc()) }
        genreId?.let { params.append("&with_genres=").append(it) }
        if (sort == MovieSort.TOP_RATED) {
            // Without a vote floor, "top rated" is all 10.0 obscurities.
            params.append("&vote_count.gte=300")
        }
        parseMovieList(httpGet("$BASE_URL/discover/movie$params"))
    }

    /** Full details for one movie (`GET /movie/:id`). */
    suspend fun fetchMovieDetails(apiKey: String, tmdbId: Int): Title =
        withContext(Dispatchers.IO) {
            val body = httpGet("$BASE_URL/movie/$tmdbId?api_key=${apiKey.enc()}")
            parseMovieDetails(body)
                ?: throw IllegalStateException("TMDB returned no movie for id $tmdbId")
        }

    /**
     * Multi-search (`GET /search/multi`): one call finds movies and TV
     * shows for [query]. Results carry their TMDB ids, so Title Details
     * enrichment works for them. "person" results are skipped — person
     * pages (spec §3.3) are not built in v1. Returns an empty list for
     * a blank query. Only called when the user has saved a key.
     */
    suspend fun searchMulti(apiKey: String, query: String, page: Int = 1): List<Title> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val url = "$BASE_URL/search/multi?api_key=${apiKey.enc()}" +
                "&query=${q.enc()}&include_adult=false&page=$page"
            parseSearchMulti(httpGet(url))
        }

    /** Main cast for one movie (`GET /movie/:id/credits`), billing order. */
    suspend fun fetchMovieCast(apiKey: String, tmdbId: Int): List<CastMember> =
        withContext(Dispatchers.IO) {
            parseCredits(httpGet("$BASE_URL/movie/$tmdbId/credits?api_key=${apiKey.enc()}"))
        }

    /**
     * Official watch options for one title: which legal services
     * stream, rent or sell it in [region] (an ISO country code like
     * "PK" or "US"). Movies use `/movie/:id/watch/providers`; TV and
     * ANIME titles use `/tv/:id/watch/providers`. Only called when the
     * user has saved a key.
     */
    suspend fun fetchWatchProviders(
        apiKey: String,
        mediaType: MediaType,
        tmdbId: Int,
        region: String
    ): WatchProviders = withContext(Dispatchers.IO) {
        val segment = if (mediaType == MediaType.MOVIE) "movie" else "tv"
        parseWatchProviders(
            httpGet("$BASE_URL/$segment/$tmdbId/watch/providers?api_key=${apiKey.enc()}"),
            region
        )
    }

    // ------------------------------------------------------------------
    // Parsing (internal so unit tests can feed saved responses)
    // ------------------------------------------------------------------

    /** Parses a discover/trending/search response (`results` array). */
    internal fun parseMovieList(body: String): List<Title> {
        val results = runCatching {
            JSONObject(body).optJSONArray("results")
        }.getOrNull() ?: return emptyList()
        val titles = ArrayList<Title>(results.length())
        for (i in 0 until results.length()) {
            val movie = results.optJSONObject(i) ?: continue
            parseMovie(movie, detailed = false)?.let { titles.add(it) }
        }
        return titles
    }

    /** Parses a search/multi response (`results` array). */
    internal fun parseSearchMulti(body: String): List<Title> {
        val results = runCatching {
            JSONObject(body).optJSONArray("results")
        }.getOrNull() ?: return emptyList()
        val titles = ArrayList<Title>(results.length())
        for (i in 0 until results.length()) {
            val row = results.optJSONObject(i) ?: continue
            when (row.optText("media_type")) {
                "movie" -> parseMovie(row, detailed = false)?.let { titles.add(it) }
                "tv" -> parseTv(row)?.let { titles.add(it) }
                // "person" rows are skipped: person pages (§3.3) are v2.
                else -> Unit
            }
        }
        return titles
    }

    /** Parses a single-movie details response. Null when it has no title. */
    internal fun parseMovieDetails(body: String): Title? {
        val movie = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return parseMovie(movie, detailed = true)
    }

    /** Parses a credits response (`cast` array) into [CastMember]s. */
    internal fun parseCredits(body: String): List<CastMember> {
        val cast = runCatching {
            JSONObject(body).optJSONArray("cast")
        }.getOrNull() ?: return emptyList()
        val members = ArrayList<CastMember>(cast.length())
        for (i in 0 until cast.length()) {
            val row = cast.optJSONObject(i) ?: continue
            val name = row.optText("name") ?: continue
            members.add(
                CastMember(
                    personName = name,
                    characterName = row.optText("character"),
                    imageUrl = imageUrl(row.optText("profile_path"), "w185")
                )
            )
        }
        return members
    }

    /**
     * Parses a watch/providers response for [preferredRegion]. When
     * TMDB has no entry for that region, the US entry is used instead
     * and the result's region says "US" — the UI shows which region
     * the options are for, so the fallback is never silent. When
     * neither exists, the result is honestly empty.
     */
    internal fun parseWatchProviders(body: String, preferredRegion: String): WatchProviders {
        val results = runCatching {
            JSONObject(body).optJSONObject("results")
        }.getOrNull() ?: return WatchProviders(preferredRegion, null, emptyList())
        val region = when {
            results.has(preferredRegion) -> preferredRegion
            results.has("US") -> "US"
            else -> preferredRegion
        }
        val entry = results.optJSONObject(region)
            ?: return WatchProviders(region, null, emptyList())
        fun parseList(key: String, category: WatchCategory): List<WatchProvider> {
            val array = entry.optJSONArray(key) ?: return emptyList()
            val providers = ArrayList<WatchProvider>(array.length())
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val name = row.optText("provider_name") ?: continue
                providers.add(
                    WatchProvider(
                        name = name,
                        logoUrl = imageUrl(row.optText("logo_path"), "w92"),
                        category = category
                    )
                )
            }
            return providers
        }
        return WatchProviders(
            region = region,
            watchPageUrl = entry.optText("link"),
            providers = combineWatchProviders(
                parseList("flatrate", WatchCategory.STREAM),
                parseList("rent", WatchCategory.RENT),
                parseList("buy", WatchCategory.BUY)
            )
        )
    }

    /**
     * One TMDB movie object → canonical [Title]. List responses carry
     * `genre_ids`; the details response carries `genres` objects plus
     * `runtime` and `imdb_id`, so [detailed] switches those on.
     */
    private fun parseMovie(movie: JSONObject, detailed: Boolean): Title? {
        val name = movie.optText("title") ?: movie.optText("original_title") ?: return null
        val releaseDate = movie.optText("release_date")
        val genres: List<String> = if (detailed) {
            val genreArray = movie.optJSONArray("genres")
            val names = ArrayList<String>()
            if (genreArray != null) {
                for (i in 0 until genreArray.length()) {
                    genreArray.optJSONObject(i)?.optText("name")?.let { names.add(it) }
                }
            }
            names
        } else {
            val ids = movie.optJSONArray("genre_ids")
            val names = ArrayList<String>()
            if (ids != null) {
                for (i in 0 until ids.length()) {
                    MOVIE_GENRES[ids.optInt(i)]?.let { names.add(it) }
                }
            }
            names
        }
        return Title(
            tmdbId = if (movie.has("id") && !movie.isNull("id")) movie.optInt("id") else null,
            imdbId = if (detailed) movie.optText("imdb_id") else null,
            name = name,
            overview = movie.optText("overview")?.takeIf { it.isNotBlank() },
            posterUrl = imageUrl(movie.optText("poster_path"), "w500"),
            rating = if (movie.has("vote_average") && !movie.isNull("vote_average")) {
                movie.optDouble("vote_average").takeIf { it > 0.0 }
            } else {
                null
            },
            mediaType = MediaType.MOVIE,
            year = releaseDate?.takeIf { it.length >= 4 }?.substring(0, 4),
            genres = genres,
            runtimeMinutes = if (detailed && movie.has("runtime") && !movie.isNull("runtime")) {
                movie.optInt("runtime").takeIf { it > 0 }
            } else {
                null
            },
            status = if (detailed) movie.optText("status") else null
        )
    }

    /**
     * One TMDB TV object (search/multi or discover shape) → canonical
     * [Title]. TV rows carry `name` / `first_air_date` instead of the
     * movie fields, and genre ids map through [TV_GENRES].
     */
    private fun parseTv(tv: JSONObject): Title? {
        val name = tv.optText("name") ?: tv.optText("original_name") ?: return null
        val firstAirDate = tv.optText("first_air_date")
        val genres = ArrayList<String>()
        val ids = tv.optJSONArray("genre_ids")
        if (ids != null) {
            for (i in 0 until ids.length()) {
                TV_GENRES[ids.optInt(i)]?.let { genres.add(it) }
            }
        }
        return Title(
            tmdbId = if (tv.has("id") && !tv.isNull("id")) tv.optInt("id") else null,
            name = name,
            overview = tv.optText("overview")?.takeIf { it.isNotBlank() },
            posterUrl = imageUrl(tv.optText("poster_path"), "w500"),
            rating = if (tv.has("vote_average") && !tv.isNull("vote_average")) {
                tv.optDouble("vote_average").takeIf { it > 0.0 }
            } else {
                null
            },
            mediaType = MediaType.TV,
            year = firstAirDate?.takeIf { it.length >= 4 }?.substring(0, 4),
            genres = genres
        )
    }

    /** TMDB image path → full URL at the given size, or null. */
    private fun imageUrl(path: String?, size: String): String? =
        path?.takeIf { it.isNotBlank() }?.let { "$IMAGE_BASE/$size$it" }

    // ------------------------------------------------------------------
    // HTTP helper (same shape as TvMazeApi / ArchiveApi)
    // ------------------------------------------------------------------

    private fun openConnection(urlString: String): HttpURLConnection =
        (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Watchly/1.0 (Android)")
        }

    private fun httpGet(urlString: String): String {
        val connection = openConnection(urlString)
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                .use { it.readText() }
            if (code !in 200..299) {
                throw IllegalStateException("TMDB HTTP $code: ${body.take(200)}")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun String.enc(): String = URLEncoder.encode(this, "UTF-8")
}
