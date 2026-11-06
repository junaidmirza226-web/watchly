/*
 * Watchly — TVmaze client (keyless, no API key required).
 *
 * ASSUMPTIONS:
 *  - HTTP: java.net.HttpURLConnection (built into Android — no OkHttp dep).
 *  - JSON: org.json (built into Android — no kotlinx.serialization dep).
 *  - Concurrency: kotlinx-coroutines-android (suspend functions, IO dispatcher).
 *  - Requires the INTERNET permission in AndroidManifest.xml.
 *
 * Endpoints used (https://api.tvmaze.com):
 *  - GET /schedule?country=US&date=yyyy-MM-dd  → today's US schedule; the
 *    embedded shows act as a "trending-ish" TV list for v1 (TVmaze has no
 *    true trending endpoint).
 *  - GET /search/shows?q=<query>              → show search (used for the
 *    search screen later, exposed here now).
 * All failures throw; callers (ViewModel) catch and degrade gracefully.
 */
package com.watchly.app.data.remote

import com.watchly.app.data.model.CastMember
import com.watchly.app.data.model.Episode
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TvMazeApi {

    private const val BASE_URL = "https://api.tvmaze.com"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /**
     * "Trending-ish" TV list for v1: shows airing today in the US schedule,
     * de-duplicated by show, best-rated first, capped at [limit].
     */
    suspend fun fetchTrendingShows(limit: Int = 20): List<Title> =
        withContext(Dispatchers.IO) {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val body = httpGet("$BASE_URL/schedule?country=US&date=$date")
            val episodes = JSONArray(body)

            val seen = HashSet<Int>()
            val titles = ArrayList<Title>()
            for (i in 0 until episodes.length()) {
                val show = episodes.optJSONObject(i)
                    ?.optJSONObject("_embedded")
                    ?.optJSONObject("show")
                    ?: continue
                val parsed = parseShow(show) ?: continue
                if (seen.add(parsed.tvmazeId ?: parsed.name.hashCode())) {
                    titles.add(parsed)
                }
            }
            titles
                .sortedByDescending { it.rating ?: 0.0 }
                .take(limit)
        }

    /** Free-text show search. Returns an empty list for a blank query. */
    suspend fun searchShows(query: String, limit: Int = 20): List<Title> =        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val encoded = URLEncoder.encode(q, "UTF-8")
            val body = httpGet("$BASE_URL/search/shows?q=$encoded")
            val results = JSONArray(body)

            val titles = ArrayList<Title>()
            for (i in 0 until results.length()) {
                val show = results.optJSONObject(i)?.optJSONObject("show") ?: continue
                parseShow(show)?.let { titles.add(it) }
            }
            titles.take(limit)
        }

    /**
     * Full details for one show (`GET /shows/:id`) — same shape as the
     * search/browse records, plus runtime and status. Used by the Title
     * Details page to fill in what list responses already carry.
     */
    suspend fun fetchShowDetails(showId: Int): Title =
        withContext(Dispatchers.IO) {
            val body = httpGet("$BASE_URL/shows/$showId")
            parseShow(JSONObject(body))
                ?: throw IllegalStateException("TVmaze returned no show for id $showId")
        }

    /**
     * Every episode of a show (`GET /shows/:id/episodes`), in air order.
     * Verified live 2026-11-06: show 169 (Breaking Bad) → 62 episodes
     * with season/number/airdate/runtime/summary. A show with no episode
     * data returns an empty list.
     */
    suspend fun fetchShowEpisodes(showId: Int): List<Episode> =
        withContext(Dispatchers.IO) {
            parseEpisodes(httpGet("$BASE_URL/shows/$showId/episodes"))
        }

    /**
     * Main cast of a show (`GET /shows/:id/cast`), billing order.
     * Verified live 2026-11-06: show 169 → Bryan Cranston as Walter
     * White first, with person + character photos.
     */
    suspend fun fetchShowCast(showId: Int): List<CastMember> =
        withContext(Dispatchers.IO) {
            parseCast(httpGet("$BASE_URL/shows/$showId/cast"))
        }

    // ------------------------------------------------------------------
    // Parsing (kept internal so unit tests can feed saved responses)
    // ------------------------------------------------------------------

    /** Parses an episodes array into [Episode]s, skipping malformed rows. */
    internal fun parseEpisodes(body: String): List<Episode> {
        val items = runCatching { JSONArray(body) }.getOrElse { return emptyList() }
        val episodes = ArrayList<Episode>(items.length())
        for (i in 0 until items.length()) {
            val ep = items.optJSONObject(i) ?: continue
            val season = if (ep.has("season") && !ep.isNull("season")) ep.optInt("season") else continue
            val number = if (ep.has("number") && !ep.isNull("number")) ep.optInt("number") else continue
            val image = ep.optJSONObject("image")
            episodes.add(
                Episode(
                    id = if (ep.has("id")) ep.optInt("id") else null,
                    season = season,
                    number = number,
                    name = ep.optText("name"),
                    airDate = ep.optText("airdate"),
                    runtimeMinutes = if (ep.has("runtime") && !ep.isNull("runtime")) {
                        ep.optInt("runtime").takeIf { it > 0 }
                    } else {
                        null
                    },
                    overview = ep.optText("summary")?.stripHtml()?.takeIf { it.isNotBlank() },
                    imageUrl = image?.optText("original") ?: image?.optText("medium")
                )
            )
        }
        return episodes
    }

    /** Parses a cast array into [CastMember]s, skipping rows without a person. */
    internal fun parseCast(body: String): List<CastMember> {
        val items = runCatching { JSONArray(body) }.getOrElse { return emptyList() }
        val cast = ArrayList<CastMember>(items.length())
        for (i in 0 until items.length()) {
            val row = items.optJSONObject(i) ?: continue
            val person = row.optJSONObject("person") ?: continue
            val personName = person.optText("name") ?: continue
            val personImage = person.optJSONObject("image")
            val character = row.optJSONObject("character")
            cast.add(
                CastMember(
                    personName = personName,
                    characterName = character?.optText("name"),
                    imageUrl = personImage?.optText("medium") ?: personImage?.optText("original")
                )
            )
        }
        return cast
    }
    suspend fun fetchShowsPage(page: Int = 0): List<Title> =
        withContext(Dispatchers.IO) {
            val body = httpGet("$BASE_URL/shows?page=$page")
            val shows = JSONArray(body)

            val titles = ArrayList<Title>(shows.length())
            for (i in 0 until shows.length()) {
                val show = shows.optJSONObject(i) ?: continue
                parseShow(show)?.let { titles.add(it) }
            }
            titles
        }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    private fun parseShow(show: JSONObject): Title? {
        val name = show.optString("name").takeIf { it.isNotBlank() } ?: return null
        val image = show.optJSONObject("image")
        val rating = show.optJSONObject("rating")
            ?.takeIf { it.has("average") && !it.isNull("average") }
            ?.optDouble("average")
        val premiered = show.optText("premiered")?.takeIf { it.length >= 4 }

        return Title(
            imdbId = show.optJSONObject("externals")?.optText("imdb"),
            tvmazeId = if (show.has("id")) show.optInt("id") else null,
            name = name,
            overview = show.optText("summary")?.stripHtml()?.takeIf { it.isNotBlank() },
            posterUrl = image?.optText("original") ?: image?.optText("medium"),
            rating = rating,
            mediaType = MediaType.TV,
            year = premiered?.substring(0, 4),
            genres = show.optJSONArray("genres").toStringList(),
            runtimeMinutes = when {
                show.has("runtime") && !show.isNull("runtime") &&
                    show.optInt("runtime") > 0 -> show.optInt("runtime")
                show.has("averageRuntime") && !show.isNull("averageRuntime") &&
                    show.optInt("averageRuntime") > 0 -> show.optInt("averageRuntime")
                else -> null
            },
            status = show.optText("status")
        )
    }

    // ------------------------------------------------------------------
    // HTTP + JSON helpers (shared shape with AniListApi)
    // ------------------------------------------------------------------

    private fun httpGet(urlString: String): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Watchly/1.0 (Android)")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                .use { it.readText() }
            if (code !in 200..299) {
                throw IllegalStateException("TVmaze HTTP $code: ${body.take(200)}")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }
}

/** Strips the simple HTML TVmaze/AniList wrap summaries in. */
internal fun String?.stripHtml(): String =
    (this ?: "")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .trim()

internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) {
        if (isNull(i)) continue
        optString(i).takeIf { it.isNotBlank() }?.let { out.add(it) }
    }
    return out
}

/**
 * Reads a string field that may be JSON null: org.json's `optString`
 * returns the literal string "null" for a JSON null, which must never
 * leak into a title, overview or year. Returns null for a missing key,
 * a JSON null, or a blank value.
 */
internal fun JSONObject.optText(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
