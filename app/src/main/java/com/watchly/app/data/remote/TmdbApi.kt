/*
 * Watchly — TMDB client (built-in master key, v3).
 *
 * TMDB is the self-updating catalogue database: community editors add
 * new films and shows daily across every industry — Hollywood,
 * Bollywood (Hindi), Tollywood (Telugu), Kollywood (Tamil),
 * Mollywood (Malayalam), Punjabi, Bengali, Korean and more. Watchly
 * never mirrors the database; it asks TMDB live, filtered by original
 * language for the industry rows.
 *
 * The API key comes in per call from TmdbKeyRepository — the built-in
 * master pool, overridable per device. This object holds no key of its
 * own. A rejected key rotates through the built-in pool before the
 * call fails; without any key the UI falls back to the keyless sources.
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
 *  - GET /movie/:id/videos, /tv/:id/videos       → official trailer (cycle 10)
 *  - GET /movie/:id/recommendations, /tv/:id/recommendations → more like this
 *  - GET /tv/:id/credits                          → TV cast (cycle 11)
 *  - GET /person/:id                              → person page details (cycle 11)
 *  - GET /person/:id/combined_credits             → person "known for" (cycle 11)
 * All failures throw; callers catch and degrade gracefully.
 */
package com.watchly.app.data.remote

import com.watchly.app.data.browse.BrowseYearFilter
import com.watchly.app.data.model.CastMember
import com.watchly.app.data.model.Episode
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Person
import com.watchly.app.data.model.Title
import com.watchly.app.data.model.WatchCategory
import com.watchly.app.data.model.WatchProvider
import com.watchly.app.data.model.WatchProviders
import com.watchly.app.data.model.combineWatchProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLException

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

    /** Genre display name → TMDB TV genre id (for the Shows tab chips). */
    fun tvGenreIdFor(name: String?): Int? =
        name?.let { wanted ->
            TV_GENRES.entries.firstOrNull { it.value.equals(wanted, ignoreCase = true) }?.key
        }

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
        fanOutLanguages(language) { code ->
            parseMovieList(
                httpGet(movieDiscoverUrl(apiKey, code, genreId, sort, page))
            )
        }
    }

    /** Simple movie discover URL for exactly one language code (never a pipe list). */
    internal fun movieDiscoverUrl(
        apiKey: String,
        language: String?,
        genreId: Int?,
        sort: MovieSort,
        page: Int
    ): String {
        val params = StringBuilder()
        params.append("?api_key=").append(apiKey.enc())
        params.append("&include_adult=false")
        params.append("&page=").append(page)
        params.append("&sort_by=").append(sort.sortParam.enc())
        language?.let { params.append("&with_original_language=").append(it.enc()) }
        genreId?.let { params.append("&with_genres=").append(it) }
        if (sort == MovieSort.TOP_RATED) {
            val minVotes = if (language.isNullOrBlank()) 100 else 10
            params.append("&vote_count.gte=").append(minVotes)
        }
        return "$BASE_URL/discover/movie$params"
    }

    /**
     * One page of the TV show catalogue via discover. [genreId] filters by
     * TMDB TV genre. Results are canonical [Title]s with tmdbId set.
     */
    suspend fun discoverTv(
        apiKey: String,
        genreId: Int? = null,
        sort: MovieSort = MovieSort.POPULAR,
        page: Int = 1
    ): List<Title> = withContext(Dispatchers.IO) {
        parseTvList(httpGet(tvDiscoverUrl(apiKey, null, genreId, sort, page)))
    }

    /** Simple TV discover URL. [language] must be a single code or null. */
    internal fun tvDiscoverUrl(
        apiKey: String,
        language: String?,
        genreId: Int?,
        sort: MovieSort,
        page: Int
    ): String {
        val tvSortParam = when (sort) {
            MovieSort.POPULAR -> "popularity.desc"
            MovieSort.TOP_RATED -> "vote_average.desc"
            MovieSort.NEWEST -> "first_air_date.desc"
            MovieSort.A_Z -> "name.asc"
        }
        val params = StringBuilder()
        params.append("?api_key=").append(apiKey.enc())
        params.append("&include_adult=false")
        params.append("&page=").append(page)
        params.append("&sort_by=").append(tvSortParam.enc())
        language?.let { params.append("&with_original_language=").append(it.enc()) }
        genreId?.let { params.append("&with_genres=").append(it) }
        if (sort == MovieSort.TOP_RATED) {
            params.append("&vote_count.gte=100")
        }
        return "$BASE_URL/discover/tv$params"
    }

    /**
     * Multi-filter movie discovery supporting pipe-separated original
     * languages (e.g. "hi|te|ta|ml|kn"), multi-genre expressions
     * ("28|53" or "28,53"), release date bounds, and minimum rating.
     */
    suspend fun discoverMoviesAdvanced(
        apiKey: String,
        languageQuery: String? = null,
        genresQuery: String? = null,
        yearFilter: BrowseYearFilter = BrowseYearFilter.ALL,
        minRating: Double? = null,
        sort: MovieSort = MovieSort.POPULAR,
        page: Int = 1
    ): List<Title> = withContext(Dispatchers.IO) {
        fanOutLanguages(languageQuery) { code ->
            parseMovieList(
                httpGet(
                    movieDiscoverAdvancedUrl(
                        apiKey, code, genresQuery, yearFilter, minRating, sort, page
                    )
                )
            )
        }
    }

    /**
     * Advanced movie discover URL for exactly one language code (or null).
     * Genre expressions use TMDB syntax: `80,16` = OR (ANY mode),
     * `80|16` = AND (ALL mode) — see [com.watchly.app.data.browse.GenreMatchMode].
     */
    internal fun movieDiscoverAdvancedUrl(
        apiKey: String,
        language: String?,
        genresQuery: String?,
        yearFilter: BrowseYearFilter,
        minRating: Double?,
        sort: MovieSort,
        page: Int
    ): String {
        val params = StringBuilder()
        params.append("?api_key=").append(apiKey.enc())
        params.append("&include_adult=false")
        params.append("&page=").append(page)
        params.append("&sort_by=").append(sort.sortParam.enc())
        language?.takeIf { it.isNotBlank() }?.let {
            params.append("&with_original_language=").append(it.enc())
        }
        genresQuery?.takeIf { it.isNotBlank() }?.let {
            params.append("&with_genres=").append(it.enc())
        }
        yearFilter.startDateIso?.let {
            params.append("&primary_release_date.gte=").append(it.enc())
        }
        yearFilter.endDateIso?.let {
            params.append("&primary_release_date.lte=").append(it.enc())
        }
        minRating?.let {
            params.append("&vote_average.gte=").append(it)
        }
        if (sort == MovieSort.TOP_RATED || minRating != null) {
            val minVotes = if (language.isNullOrBlank()) 80 else 8
            params.append("&vote_count.gte=").append(minVotes)
        }
        return "$BASE_URL/discover/movie$params"
    }

    /**
     * Multi-filter TV discovery supporting multi-language, multi-genre,
     * air-date bounds, and minimum rating.
     */
    suspend fun discoverTvAdvanced(
        apiKey: String,
        languageQuery: String? = null,
        genresQuery: String? = null,
        yearFilter: BrowseYearFilter = BrowseYearFilter.ALL,
        minRating: Double? = null,
        sort: MovieSort = MovieSort.POPULAR,
        page: Int = 1
    ): List<Title> = withContext(Dispatchers.IO) {
        fanOutLanguages(languageQuery) { code ->
            parseTvList(
                httpGet(
                    tvDiscoverAdvancedUrl(
                        apiKey, code, genresQuery, yearFilter, minRating, sort, page
                    )
                )
            )
        }
    }

    /** Advanced TV discover URL for exactly one language code (or null). */
    internal fun tvDiscoverAdvancedUrl(
        apiKey: String,
        language: String?,
        genresQuery: String?,
        yearFilter: BrowseYearFilter,
        minRating: Double?,
        sort: MovieSort,
        page: Int
    ): String {
        val tvSortParam = when (sort) {
            MovieSort.POPULAR -> "popularity.desc"
            MovieSort.TOP_RATED -> "vote_average.desc"
            MovieSort.NEWEST -> "first_air_date.desc"
            MovieSort.A_Z -> "name.asc"
        }
        val params = StringBuilder()
        params.append("?api_key=").append(apiKey.enc())
        params.append("&include_adult=false")
        params.append("&page=").append(page)
        params.append("&sort_by=").append(tvSortParam.enc())
        language?.takeIf { it.isNotBlank() }?.let {
            params.append("&with_original_language=").append(it.enc())
        }
        genresQuery?.takeIf { it.isNotBlank() }?.let {
            params.append("&with_genres=").append(it.enc())
        }
        yearFilter.startDateIso?.let {
            params.append("&first_air_date.gte=").append(it.enc())
        }
        yearFilter.endDateIso?.let {
            params.append("&first_air_date.lte=").append(it.enc())
        }
        minRating?.let {
            params.append("&vote_average.gte=").append(it)
        }
        if (sort == MovieSort.TOP_RATED || minRating != null) {
            val minVotes = if (language.isNullOrBlank()) 50 else 5
            params.append("&vote_count.gte=").append(minVotes)
        }
        return "$BASE_URL/discover/tv$params"
    }

    /**
     * TMDB's `with_original_language` accepts exactly one ISO 639-1 code —
     * pipe/comma lists ("te|ta|ml|kn") make TMDB answer HTTP 422 and every
     * industry row silently died. Multi-language selections fan out into one
     * call per language and merge in stable order. When EVERY leg fails the
     * first error is rethrown, so callers can tell "no results" apart from
     * "catalogue unreachable".
     */
    private suspend fun fanOutLanguages(
        languageQuery: String?,
        call: suspend (String?) -> List<Title>
    ): List<Title> {
        val codes = splitLanguageQuery(languageQuery)
        if (codes.size <= 1) return call(codes.firstOrNull())
        val results = coroutineScope {
            codes.map { code -> async { runCatching { call(code) } } }.awaitAll()
        }
        if (results.all { it.isFailure }) {
            throw results.first().exceptionOrNull()!!
        }
        return results.flatMap { it.getOrDefault(emptyList()) }
            .distinctBy { it.stableKey }
    }

    /** Splits "hi|te,ta" style multi-language queries into distinct codes. */
    internal fun splitLanguageQuery(languageQuery: String?): List<String> =
        languageQuery
            ?.split('|', ',')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?: emptyList()

    /**
     * Human-readable, actionable failure reason for a TMDB load error.
     * Distinguishes "key rejected" (get your own free key — it helps) from
     * "TMDB unreachable from this network" (a key won't help; the fallback
     * catalogue keeps working).
     */
    fun describeTmdbFailure(err: Throwable): String {
        val msg = err.message ?: ""
        return when {
            err is UnknownHostException || err is ConnectException ||
                err is SocketTimeoutException || err is SSLException ||
                err is IOException ->
                "Couldn't reach TMDB from this network. Watchly keeps using its " +
                    "fallback catalogue — if TMDB is blocked on your network, " +
                    "try another network."
            msg.contains("TMDB HTTP 401") || msg.contains("TMDB HTTP 403") ->
                "TMDB rejected this app's access for now. The fallback " +
                    "catalogue stays on — try again later."
            msg.contains("TMDB HTTP 429") ->
                "TMDB is rate-limiting requests right now. Try again in a moment."
            msg.contains("TMDB HTTP 5") ->
                "TMDB servers are having trouble. Try again shortly."
            msg.contains("No TMDB key") ->
                "No TMDB key in this build. The fallback catalogue stays on."
            else -> "Couldn't load content. ${msg.take(120)}".trim()
        }
    }

    /** Enriched TV show metadata + season list (`GET /tv/:id?append_to_response=external_ids`). */
    data class TvDetailsBundle(
        val title: Title,
        val seasonNumbers: List<Int>
    )

    suspend fun fetchTvDetails(apiKey: String, tmdbId: Int): TvDetailsBundle =
        withContext(Dispatchers.IO) {
            val url = "$BASE_URL/tv/$tmdbId?api_key=${apiKey.enc()}&append_to_response=external_ids"
            parseTvDetailsBundle(httpGet(url))
                ?: throw IllegalStateException("TMDB returned no TV details for id $tmdbId")
        }

    /** Episodes for one season (`GET /tv/:id/season/:seasonNumber`). */
    suspend fun fetchTvSeasonEpisodes(
        apiKey: String,
        tmdbId: Int,
        seasonNumber: Int
    ): List<Episode> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/tv/$tmdbId/season/$seasonNumber?api_key=${apiKey.enc()}"
        parseTvSeasonEpisodes(httpGet(url), seasonNumber)
    }

    /** Top Trending Movies of the week. */
    suspend fun fetchTrendingMovies(apiKey: String, page: Int = 1): List<Title> =
        withContext(Dispatchers.IO) {
            parseMovieList(httpGet("$BASE_URL/trending/movie/week?api_key=${apiKey.enc()}&page=$page"))
        }

    /** Top Trending TV Shows of the week. */
    suspend fun fetchTrendingTv(apiKey: String, page: Int = 1): List<Title> =
        withContext(Dispatchers.IO) {
            parseTvList(httpGet("$BASE_URL/trending/tv/week?api_key=${apiKey.enc()}&page=$page"))
        }

    /** Top Rated Movies of all time. */
    suspend fun fetchTopRatedMovies(apiKey: String, page: Int = 1): List<Title> =
        withContext(Dispatchers.IO) {
            parseMovieList(httpGet("$BASE_URL/movie/top_rated?api_key=${apiKey.enc()}&page=$page"))
        }

    /** Top Rated TV Series of all time. */
    suspend fun fetchTopRatedTv(apiKey: String, page: Int = 1): List<Title> =
        withContext(Dispatchers.IO) {
            parseTvList(httpGet("$BASE_URL/tv/top_rated?api_key=${apiKey.enc()}&page=$page"))
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
     * enrichment works for them. "person" results are skipped —
     * unified search stays titles-only even though person pages
     * (§3.3, cycle 11) exist; they're reached from cast rows.
     * Returns an empty list for a blank query. Only called when the
     * user has saved a key.
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
     * Main cast for one TV show (`GET /tv/:id/credits`), billing
     * order (cycle 11). Same response shape as the movie endpoint.
     * Until this existed, a TMDB-sourced show had no cast row at
     * all — and no way into the person pages (§3.3).
     */
    suspend fun fetchTvCast(apiKey: String, tmdbId: Int): List<CastMember> =
        withContext(Dispatchers.IO) {
            parseCredits(httpGet("$BASE_URL/tv/$tmdbId/credits?api_key=${apiKey.enc()}"))
        }

    /**
     * One person's page data (`GET /person/:id`, cycle 11, §3.3).
     * Throws when TMDB has no such person — the caller shows the
     * honest load-failed state, never a fabricated page.
     */
    suspend fun fetchPerson(apiKey: String, personId: Int): Person =
        withContext(Dispatchers.IO) {
            val body = httpGet("$BASE_URL/person/$personId?api_key=${apiKey.enc()}")
            parsePerson(body)
                ?: throw IllegalStateException("TMDB returned no person for id $personId")
        }

    /**
     * "Known for" (cycle 11, §3.3): the person's combined movie +
     * TV credits (`GET /person/:id/combined_credits`), strongest
     * first. Results are canonical [Title]s that open their own
     * details pages like any other card.
     */
    suspend fun fetchPersonKnownFor(
        apiKey: String,
        personId: Int,
        limit: Int = 20
    ): List<Title> = withContext(Dispatchers.IO) {
        parseCombinedCredits(
            httpGet("$BASE_URL/person/$personId/combined_credits?api_key=${apiKey.enc()}")
        ).take(limit)
    }

    /**
     * Resolves an IMDb id to a TMDB id (`GET /find/:id` with
     * external_source=imdb_id) — Phase D: titles that came from
     * TVmaze/AniList carry an IMDb id but no TMDB id, and this is
     * what lets their details page show watch providers and TMDB
     * enrichment too. Returns null when TMDB has no match. Only
     * called when the user has saved a key; callers cache the
     * result (ids never change).
     */
    suspend fun findByImdbId(apiKey: String, imdbId: String, mediaType: MediaType): Int? =
        withContext(Dispatchers.IO) {
            val url = "$BASE_URL/find/${imdbId.enc()}?api_key=${apiKey.enc()}" +
                "&external_source=imdb_id"
            parseFind(httpGet(url), mediaType)
        }

    /** Parses a /find response: the expected type's first result wins. */
    internal fun parseFind(body: String, mediaType: MediaType): Int? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        fun firstId(key: String): Int? =
            root.optJSONArray(key)?.optJSONObject(0)
                ?.takeIf { it.has("id") && !it.isNull("id") }
                ?.optInt("id")?.takeIf { it > 0 }
        return when (mediaType) {
            MediaType.MOVIE ->
                firstId("movie_results") ?: firstId("tv_results")
            MediaType.TV, MediaType.ANIME ->
                firstId("tv_results") ?: firstId("movie_results")
        }
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

    /**
     * The YouTube key of the title's official trailer (spec §4.1,
     * cycle 10), from `GET /{movie|tv}/:id/videos`. Returns null
     * when TMDB lists no suitable YouTube video — the details page
     * then simply has no Trailer button; nothing is invented.
     * Only called when the user has saved a key.
     */
    suspend fun fetchTrailerKey(
        apiKey: String,
        mediaType: MediaType,
        tmdbId: Int
    ): String? = withContext(Dispatchers.IO) {
        val segment = if (mediaType == MediaType.MOVIE) "movie" else "tv"
        parseTrailerKey(
            httpGet("$BASE_URL/$segment/$tmdbId/videos?api_key=${apiKey.enc()}")
        )
    }

    /**
     * "More like this" (spec §4.1, cycle 10): TMDB's own
     * recommendations for one title, `GET /{movie|tv}/:id/
     * recommendations`. Results are canonical [Title]s of the same
     * kind as the source title. Only called with a saved key.
     */
    suspend fun fetchRecommendations(
        apiKey: String,
        mediaType: MediaType,
        tmdbId: Int,
        limit: Int = 12
    ): List<Title> = withContext(Dispatchers.IO) {
        val segment = if (mediaType == MediaType.MOVIE) "movie" else "tv"
        parseRecommendations(
            httpGet("$BASE_URL/$segment/$tmdbId/recommendations?api_key=${apiKey.enc()}")
        ).take(limit)
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

    /** Parses a TV show discover/trending response (`results` array). */
    internal fun parseTvList(body: String): List<Title> {
        val results = runCatching {
            JSONObject(body).optJSONArray("results")
        }.getOrNull() ?: return emptyList()
        val titles = ArrayList<Title>(results.length())
        for (i in 0 until results.length()) {
            val tv = results.optJSONObject(i) ?: continue
            parseTv(tv)?.let { titles.add(it) }
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
                // "person" rows are skipped: unified search is
                // titles-only; person pages open from cast rows.
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
                    imageUrl = imageUrl(row.optText("profile_path"), "w185"),
                    // Cycle 11 (§3.3): TMDB's own person id — the
                    // ONLY id a cast row may carry into a person
                    // page. Rows without one stay inert.
                    tmdbPersonId = if (row.has("id") && !row.isNull("id")) {
                        row.optInt("id").takeIf { it > 0 }
                    } else {
                        null
                    }
                )
            )
        }
        return members
    }

    /**
     * Parses a /person response into a [Person] (cycle 11, §3.3).
     * Null when the body has no usable name/id. A blank biography
     * stays null — the page's "no biography yet" state depends on
     * the difference between "TMDB has none" and a real bio.
     */
    internal fun parsePerson(body: String): Person? {
        val person = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val name = person.optText("name") ?: return null
        val id = if (person.has("id") && !person.isNull("id")) {
            person.optInt("id").takeIf { it > 0 }
        } else {
            null
        } ?: return null
        return Person(
            tmdbId = id,
            name = name,
            biography = person.optText("biography")?.takeIf { it.isNotBlank() },
            knownForDepartment = person.optText("known_for_department"),
            photoUrl = imageUrl(person.optText("profile_path"), "w342")
        )
    }

    /**
     * Parses a /person/:id/combined_credits response (`cast`
     * array) into canonical [Title]s (cycle 11, §3.3). Rows are
     * dispatched by their own `media_type` — a person's film and
     * TV work arrive mixed in one list. Crew entries are not in
     * `cast`, so everything here is acting work. The same title
     * can appear twice (multiple characters), so rows are deduped
     * by stable key, then ordered by TMDB's popularity so the
     * "Known for" row leads with what they're actually known for.
     */
    internal fun parseCombinedCredits(body: String): List<Title> {
        val cast = runCatching {
            JSONObject(body).optJSONArray("cast")
        }.getOrNull() ?: return emptyList()
        data class Scored(val title: Title, val popularity: Double, val order: Int)
        val scored = ArrayList<Scored>()
        val seen = HashSet<String>()
        for (i in 0 until cast.length()) {
            val row = cast.optJSONObject(i) ?: continue
            val parsed = when (row.optText("media_type")) {
                "movie" -> parseMovie(row, detailed = false)
                "tv" -> parseTv(row)
                else -> null
            } ?: continue
            if (!seen.add(parsed.stableKey)) continue
            scored.add(
                Scored(
                    title = parsed,
                    popularity = if (row.has("popularity") &&
                        !row.isNull("popularity")
                    ) {
                        row.optDouble("popularity")
                    } else {
                        0.0
                    },
                    order = i
                )
            )
        }
        return scored
            .sortedWith(
                compareByDescending<Scored> { it.popularity }.thenBy { it.order }
            )
            .map { it.title }
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
     * Picks the trailer's YouTube key from a /videos response.
     * Only YouTube entries can play in Watchly's embedded player.
     * Preference, strongest first: official Trailer → any Trailer
     * → official Teaser → any Teaser → the first YouTube video of
     * any kind (a featurette beats no button only when TMDB lists
     * nothing better — in practice the first three cover it).
     */
    internal fun parseTrailerKey(body: String): String? {
        val results = runCatching {
            JSONObject(body).optJSONArray("results")
        }.getOrNull() ?: return null
        data class Candidate(val key: String, val rank: Int, val order: Int)
        val candidates = ArrayList<Candidate>()
        for (i in 0 until results.length()) {
            val row = results.optJSONObject(i) ?: continue
            if (row.optText("site") != "YouTube") continue
            val key = row.optText("key") ?: continue
            val official = row.optBoolean("official")
            val rank = when (row.optText("type")) {
                "Trailer" -> if (official) 0 else 1
                "Teaser" -> if (official) 2 else 3
                else -> 4
            }
            candidates.add(Candidate(key, rank, i))
        }
        return candidates
            .sortedWith(compareBy({ it.rank }, { it.order }))
            .firstOrNull()?.key
    }

    /**
     * "More like this" (spec §4.1, cycle 10): TMDB's own
     * recommendations for one title, `GET /{movie|tv}/:id/
     * recommendations`. Results are canonical [Title]s of the same
     * kind as the source title. Only called with a saved key.
     */
    internal fun parseRecommendations(body: String): List<Title> {
        val results = runCatching {
            JSONObject(body).optJSONArray("results")
        }.getOrNull() ?: return emptyList()
        val titles = ArrayList<Title>(results.length())
        for (i in 0 until results.length()) {
            val row = results.optJSONObject(i) ?: continue
            val parsed = if (row.has("title")) {
                parseMovie(row, detailed = false)
            } else {
                parseTv(row)
            }
            parsed?.let { titles.add(it) }
        }
        return titles
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
            status = if (detailed) movie.optText("status") else null,
            backdropUrl = imageUrl(movie.optText("backdrop_path"), "w780"),
            originalLanguage = movie.optText("original_language")?.takeIf { it.isNotBlank() },
            releaseDate = releaseDate?.takeIf { it.isNotBlank() }
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
            genres = genres,
            backdropUrl = imageUrl(tv.optText("backdrop_path"), "w780"),
            originalLanguage = tv.optText("original_language")?.takeIf { it.isNotBlank() },
            releaseDate = firstAirDate?.takeIf { it.isNotBlank() }
        )
    }

    /** Parses `GET /tv/:id?append_to_response=external_ids`. */
    internal fun parseTvDetailsBundle(body: String): TvDetailsBundle? {
        val tv = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val name = tv.optText("name") ?: tv.optText("original_name") ?: return null
        val firstAirDate = tv.optText("first_air_date")
        val genres = ArrayList<String>()
        val genreArray = tv.optJSONArray("genres")
        if (genreArray != null) {
            for (i in 0 until genreArray.length()) {
                genreArray.optJSONObject(i)?.optText("name")?.let { genres.add(it) }
            }
        }
        val runtimes = tv.optJSONArray("episode_run_time")
        val runtime = if (runtimes != null && runtimes.length() > 0) {
            runtimes.optInt(0).takeIf { it > 0 }
        } else {
            null
        }
        val imdbId = tv.optJSONObject("external_ids")?.optText("imdb_id")
            ?.takeIf { it.isNotBlank() }
        val seasonArray = tv.optJSONArray("seasons")
        val seasons = ArrayList<Int>()
        if (seasonArray != null) {
            for (i in 0 until seasonArray.length()) {
                val s = seasonArray.optJSONObject(i) ?: continue
                val epCount = s.optInt("episode_count", 0)
                val seasonNum = s.optInt("season_number", -1)
                if (seasonNum > 0 && epCount > 0) {
                    seasons.add(seasonNum)
                }
            }
        }
        if (seasons.isEmpty() && tv.optInt("number_of_seasons", 0) > 0) {
            for (s in 1..tv.optInt("number_of_seasons", 1)) {
                seasons.add(s)
            }
        }
        val title = Title(
            tmdbId = if (tv.has("id") && !tv.isNull("id")) tv.optInt("id") else null,
            imdbId = imdbId,
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
            genres = genres,
            runtimeMinutes = runtime,
            status = tv.optText("status"),
            backdropUrl = imageUrl(tv.optText("backdrop_path"), "w780"),
            originalLanguage = tv.optText("original_language")?.takeIf { it.isNotBlank() },
            releaseDate = firstAirDate?.takeIf { it.isNotBlank() }
        )
        return TvDetailsBundle(title = title, seasonNumbers = seasons.distinct().sorted())
    }

    /** Parses `GET /tv/:id/season/:seasonNumber` into canonical [Episode]s. */
    internal fun parseTvSeasonEpisodes(body: String, fallbackSeason: Int): List<Episode> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val array = root.optJSONArray("episodes") ?: return emptyList()
        val episodes = ArrayList<Episode>(array.length())
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val number = row.optInt("episode_number", i + 1)
            val season = row.optInt("season_number", fallbackSeason)
            episodes.add(
                Episode(
                    id = if (row.has("id") && !row.isNull("id")) row.optInt("id") else null,
                    season = season,
                    number = number,
                    name = row.optText("name"),
                    airDate = row.optText("air_date"),
                    runtimeMinutes = if (row.has("runtime") && !row.isNull("runtime")) {
                        row.optInt("runtime").takeIf { it > 0 }
                    } else {
                        null
                    },
                    overview = row.optText("overview")?.takeIf { it.isNotBlank() },
                    imageUrl = imageUrl(row.optText("still_path"), "w300")
                )
            )
        }
        return episodes
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
        var url = urlString
        val triedKeys = mutableListOf<String>()
        while (true) {
            val connection = openConnection(url)
            try {
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = BufferedReader(
                    InputStreamReader(stream ?: java.io.ByteArrayInputStream(ByteArray(0)), Charsets.UTF_8)
                ).use { it.readText() }
                if (code in 200..299) return body
                // A rejected key (401/403) falls through to the next master
                // candidate before giving up — one stale built-in key must
                // never blank the catalogue while a healthy one exists.
                if (code == 401 || code == 403) {
                    val currentKey = apiKeyFrom(url)
                    val next = fallbackKeyCandidates.firstOrNull {
                        it.isNotBlank() && it != currentKey && it !in triedKeys
                    }
                    if (next != null) {
                        currentKey?.let { triedKeys.add(it) }
                        url = withApiKey(url, next)
                        continue
                    }
                }
                throw IllegalStateException("TMDB HTTP $code: ${body.take(200)}")
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * Master-key rotation pool (build-secret injected). Tried in order when
     * TMDB rejects the key a request was built with.
     */
    @Volatile
    var fallbackKeyCandidates: List<String> = emptyList()

    /** Returns [url] with its `api_key` parameter replaced by [key]. */
    internal fun withApiKey(url: String, key: String): String =
        url.replace(Regex("([?&]api_key=)[^&]*")) {
            it.groupValues[1] + URLEncoder.encode(key, "UTF-8")
        }

    /** The `api_key` value embedded in [url], or null. */
    internal fun apiKeyFrom(url: String): String? =
        Regex("[?&]api_key=([^&]*)").find(url)?.groupValues?.get(1)

    private fun String.enc(): String = URLEncoder.encode(this, "UTF-8")
}
