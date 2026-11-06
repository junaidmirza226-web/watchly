/*
 * Watchly — AniList client (keyless, no API key / no account required).
 *
 * ASSUMPTIONS:
 *  - HTTP: java.net.HttpURLConnection (built into Android — no OkHttp dep).
 *  - JSON: org.json (built into Android — no kotlinx.serialization dep).
 *  - Concurrency: kotlinx-coroutines-android (suspend functions, IO dispatcher).
 *  - Requires the INTERNET permission in AndroidManifest.xml.
 *
 * Endpoint used (https://graphql.anilist.co — public GraphQL, keyless):
 *  - POST Page(media: type ANIME, sort TRENDING_DESC, isAdult false)
 *    → currently trending anime with scores and cover images.
 * AniList scores are 0–100; they are normalised to the app's 0.0–10.0
 * scale at parse time. All failures throw; callers catch and degrade.
 */
package com.watchly.app.data.remote

import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object AniListApi {

    private const val ENDPOINT = "https://graphql.anilist.co"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    private const val TRENDING_QUERY = """
        query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}genres: [String], ${'$'}sort: [MediaSort], ${'$'}search: String) {
          Page(page: ${'$'}page, perPage: ${'$'}perPage) {
            media(type: ANIME, sort: ${'$'}sort, genre_in: ${'$'}genres, search: ${'$'}search, isAdult: false) {
              id
              idMal
              title { romaji english native }
              description(asHtml: false)
              averageScore
              seasonYear
              coverImage { large medium }
              genres
            }
          }
        }
    """

    /** Sort orders the Anime tab offers, as AniList MediaSort values. */
    enum class AnimeSort(val apiValue: String) {
        POPULAR("POPULARITY_DESC"),
        TRENDING("TRENDING_DESC"),
        TOP_RATED("SCORE_DESC"),
        NEWEST("START_DATE_DESC"),
        A_Z("TITLE_ROMAJI")
    }

    /** Genre chips shown on the Anime tab (AniList genre names). */
    val ANIME_GENRES: List<String> = listOf(
        "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror",
        "Mystery", "Romance", "Sci-Fi", "Slice of Life", "Sports",
        "Supernatural", "Suspense", "Thriller"
    )

    /** Currently trending anime, best-trending first, capped at [limit]. */
    suspend fun fetchTrendingAnime(limit: Int = 20): List<Title> =
        fetchAnimePage(page = 1, perPage = limit, sort = AnimeSort.TRENDING)

    /**
     * Free-text anime search (AniList `search` argument, same query shape
     * as the browse pages — a null `search` variable means "no filter").
     * Best-matching first, capped at [limit]. Returns an empty list for
     * a blank query. Note: AniList rejects requests from this VM's
     * datacenter IP (Cloudflare 403), so end-to-end verification happens
     * on the phone — the query shape is the documented standard one.
     */
    suspend fun searchAnime(query: String, limit: Int = 20): List<Title> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return fetchAnimePage(
            page = 1,
            perPage = limit,
            sort = AnimeSort.TRENDING,
            search = q
        )
    }

    /**
     * One page of anime, server-side filtered by [genre] (null = all) and
     * sorted by [sort]. [page] is 1-based. A null `genres` variable means
     * "no genre filter" to AniList; a null `search` means "no text filter".
     */
    suspend fun fetchAnimePage(
        page: Int = 1,
        perPage: Int = 30,
        sort: AnimeSort = AnimeSort.POPULAR,
        genre: String? = null,
        search: String? = null
    ): List<Title> =
        withContext(Dispatchers.IO) {
            val variables = JSONObject()
                .put("page", page)
                .put("perPage", perPage)
                .put(
                    "genres",
                    genre?.let { org.json.JSONArray().put(it) } ?: JSONObject.NULL
                )
                .put("sort", org.json.JSONArray().put(sort.apiValue))
                .put("search", search ?: JSONObject.NULL)
            val payload = JSONObject()
                .put("query", TRENDING_QUERY)
                .put("variables", variables)
                .toString()

            parseMediaList(httpPostJson(ENDPOINT, payload))
        }

    /**
     * Parses a Page(media) GraphQL response body into [Title]s. Kept
     * separate from the network call so unit tests can feed saved
     * responses (the AniList endpoint itself is unreachable from this
     * VM — Cloudflare 403 on a datacenter IP).
     */
    internal fun parseMediaList(body: String): List<Title> {
        val media = runCatching {
            JSONObject(body)
                .optJSONObject("data")
                ?.optJSONObject("Page")
                ?.optJSONArray("media")
        }.getOrNull() ?: return emptyList()

        val titles = ArrayList<Title>(media.length())
        for (i in 0 until media.length()) {
            val item = media.optJSONObject(i) ?: continue
            parseMedia(item)?.let { titles.add(it) }
        }
        return titles
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    private fun parseMedia(item: JSONObject): Title? {
        val titleObj = item.optJSONObject("title")
        val name = titleObj?.optText("english")
            ?: titleObj?.optText("romaji")
            ?: titleObj?.optText("native")
            ?: return null

        val cover = item.optJSONObject("coverImage")
        val score = if (item.has("averageScore") && !item.isNull("averageScore")) {
            item.optDouble("averageScore") / 10.0 // 0–100 → 0.0–10.0
        } else {
            null
        }

        return Title(
            anilistId = if (item.has("id")) item.optInt("id") else null,
            name = name,
            overview = item.optText("description")?.stripHtml()
                ?.takeIf { it.isNotBlank() },
            posterUrl = cover?.optText("large") ?: cover?.optText("medium"),
            rating = score,
            mediaType = MediaType.ANIME,
            year = if (item.has("seasonYear") && !item.isNull("seasonYear")) {
                item.optInt("seasonYear").toString()
            } else {
                null
            },
            genres = item.optJSONArray("genres").toStringList()
        )
    }

    // ------------------------------------------------------------------
    // HTTP helper
    // ------------------------------------------------------------------

    private fun httpPostJson(urlString: String, jsonBody: String): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Watchly/1.0 (Android)")
        }
        try {
            connection.outputStream.use { out ->
                out.write(jsonBody.toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                .use { it.readText() }
            if (code !in 200..299) {
                throw IllegalStateException("AniList HTTP $code: ${body.take(200)}")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }
}
