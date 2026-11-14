/*
 * Watchly — Trakt client (cycle 4).
 *
 * Trakt supplies two things: the public "trending now" lists
 * (merged into Home through the canonical ID layer like every other
 * provider) and the community rating shown on Title Details.
 *
 * Auth is the user's own app Client ID, sent as the
 * `trakt-api-key` header with `trakt-api-version: 2`. Two hard
 * lessons from verifying this integration (2026-11-06):
 *  - api.trakt.tv sits behind Cloudflare, which BANS the default
 *    Java/Python user agents (error 1010) — a browser-like
 *    User-Agent header is mandatory.
 *  - Trending/rating reads are public-data endpoints; no OAuth or
 *    user account is involved.
 *
 * Trakt ids map onto Watchly's canonical layer through the IMDb and
 * TMDB ids every Trakt item carries (see Title/SearchMerger), so
 * trending entries dedupe against TMDB/Archive records instead of
 * appearing twice.
 */
package com.watchly.app.data.remote

import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object TraktApi {

    private const val BASE_URL = "https://api.trakt.tv"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /**
     * Browser-like user agent. REQUIRED: Cloudflare refuses the
     * default agent with error 1010 (verified live 2026-11-06).
     */
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    /** Trending movies + shows merged into one list, movies first. */
    suspend fun fetchTrending(
        clientId: String,
        limitPerType: Int = 15
    ): List<Title> {
        val movies = fetchTrendingMovies(clientId, limitPerType)
        val shows = fetchTrendingShows(clientId, limitPerType)
        return movies + shows
    }

    suspend fun fetchTrendingMovies(
        clientId: String,
        limit: Int = 15
    ): List<Title> {
        val body = httpGet(clientId, "/movies/trending?limit=$limit") ?: return emptyList()
        return parseTrending(body, MediaType.MOVIE)
    }

    suspend fun fetchTrendingShows(
        clientId: String,
        limit: Int = 15
    ): List<Title> {
        val body = httpGet(clientId, "/shows/trending?limit=$limit") ?: return emptyList()
        return parseTrending(body, MediaType.TV)
    }

    /**
     * Trakt's community rating (0–10, one decimal from the API) for
     * the title with this IMDb id, or null when unavailable.
     */
    suspend fun fetchRating(clientId: String, imdbId: String, type: MediaType): Double? {
        val segment = if (type == MediaType.MOVIE) "movies" else "shows"
        val body = httpGet(
            clientId,
            "/$segment/${URLEncoder.encode(imdbId, "UTF-8")}/ratings"
        ) ?: return null
        return parseRating(body)
    }

    /**
     * Parses a trending response. Movies arrive as
     * `[{"watchers": n, "movie": {...}}]`, shows likewise with
     * "show". The wrapped object carries title/year and an "ids"
     * block (trakt, slug, imdb, tmdb) — the bridge into the
     * canonical merge layer. Exposed for tests.
     */
    fun parseTrending(body: String, type: MediaType): List<Title> = runCatching {
        val wrapKey = if (type == MediaType.MOVIE) "movie" else "show"
        val array = JSONArray(body)
        val titles = ArrayList<Title>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val media = item.optJSONObject(wrapKey) ?: continue
            val ids = media.optJSONObject("ids")
            val name = media.optString("title").trim()
            if (name.isEmpty()) continue
            titles += Title(
                tmdbId = ids?.optInt("tmdb")?.takeIf { it > 0 },
                imdbId = ids?.optString("imdb")?.takeIf { it.isNotBlank() },
                name = name,
                mediaType = type,
                year = media.optInt("year").takeIf { it > 0 }?.toString()
            )
        }
        titles
    }.getOrDefault(emptyList())

    /** Parses a /ratings response: `{"rating": 8.36…, "votes": n}`. */
    fun parseRating(body: String): Double? = runCatching {
        val rating = JSONObject(body).optDouble("rating", Double.NaN)
        if (rating.isNaN() || rating <= 0.0) null else rating
    }.getOrNull()

    /** Authenticated GET; null on any HTTP/network failure. */
    private suspend fun httpGet(clientId: String, path: String): String? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(BASE_URL + path).openConnection()
                    as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("trakt-api-version", "2")
                    setRequestProperty("trakt-api-key", clientId)
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext null
                }
                connection.inputStream.bufferedReader()
                    .use(BufferedReader::readText)
            } catch (e: Exception) {
                null
            } finally {
                connection?.disconnect()
            }
        }
}
