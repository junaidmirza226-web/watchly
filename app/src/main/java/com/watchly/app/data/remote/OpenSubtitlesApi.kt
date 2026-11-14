/*
 * Watchly — OpenSubtitles client (cycle 4).
 *
 * Flow (the provider's public REST API, api.opensubtitles.com):
 *   1. GET  /api/v1/subtitles?imdb_id=<numeric>[&season_number=&episode_number=]
 *      → candidate subtitle files for the title/episode.
 *   2. POST /api/v1/download {"file_id": n}
 *      → a time-limited direct link + the file name; each POST
 *      spends one unit of the account's daily download quota
 *      (free accounts get a small daily allowance).
 *   3. The player saves the file into the app cache and attaches it
 *      to Media3 as a subtitle track.
 *
 * Two provider rules are baked in:
 *  - Every request carries `Api-Key: <user key>` AND
 *    `User-Agent: Watchly v1.0` — OpenSubtitles REQUIRES a named
 *    user agent identifying the app (their API terms).
 *  - Quota exhaustion answers HTTP 429 (or a 4xx whose body says
 *    the daily limit is reached) — the UI turns that into a clear
 *    "come back tomorrow" message instead of a silent failure.
 *
 * IMDb ids: the API wants the NUMERIC part for imdb_id queries
 * ("tt0468569" → 468569) and the PARENT show's numeric id plus
 * season/episode numbers for episodes. [planSearch] encodes that
 * (verified against the live API 2026-11-06).
 */
package com.watchly.app.data.remote

import com.watchly.app.data.model.SubtitleResult
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What one subtitle search should ask the provider for. */
data class SubtitleSearchPlan(
    /** Numeric IMDb id to query (no "tt" prefix). */
    val imdbNumericId: Int,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null
)

/** Outcome of the download-link request. */
sealed interface SubtitleDownloadOutcome {
    /** Direct link + the file's name, ready to fetch. */
    data class Ready(val link: String, val fileName: String) : SubtitleDownloadOutcome
    /** The account's daily download quota is spent. */
    data object QuotaReached : SubtitleDownloadOutcome
    /** Anything else went wrong (network, provider error). */
    data object Failed : SubtitleDownloadOutcome
}

/** Outcome of a subtitle search. */
sealed interface SubtitleSearchOutcome {
    data class Found(val results: List<SubtitleResult>) : SubtitleSearchOutcome
    data object QuotaReached : SubtitleSearchOutcome
    data object Failed : SubtitleSearchOutcome
}

object OpenSubtitlesApi {

    private const val BASE_URL = "https://api.opensubtitles.com/api/v1"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    /**
     * REQUIRED by the provider's terms: an app-identifying user
     * agent. Requests with a generic/absent agent are rejected.
     */
    const val USER_AGENT = "Watchly v1.0"

    private val EPISODE_LABEL = Regex("""S(\d+)\s*E(\d+)""", RegexOption.IGNORE_CASE)

    /**
     * Plans a search from what the player knows: the title's IMDb id
     * (for a show, the PARENT series id — that is how the provider
     * indexes episode subtitles) and the episode label ("S1 E4").
     * Returns null when there is no IMDb id to search by.
     */
    fun planSearch(imdbId: String?, episodeLabel: String?): SubtitleSearchPlan? {
        val numeric = imdbId?.removePrefix("tt")?.toIntOrNull() ?: return null
        val match = episodeLabel?.let { EPISODE_LABEL.find(it) }
        return SubtitleSearchPlan(
            imdbNumericId = numeric,
            seasonNumber = match?.groupValues?.get(1)?.toIntOrNull(),
            episodeNumber = match?.groupValues?.get(2)?.toIntOrNull()
        )
    }

    /** Searches subtitle files for [plan]; English first, popular first. */
    suspend fun search(
        apiKey: String,
        plan: SubtitleSearchPlan
    ): SubtitleSearchOutcome {
        val query = buildString {
            append("?imdb_id=").append(plan.imdbNumericId)
            if (plan.seasonNumber != null && plan.episodeNumber != null) {
                append("&season_number=").append(plan.seasonNumber)
                append("&episode_number=").append(plan.episodeNumber)
            }
            append("&order_by=download_count&order_direction=desc")
        }
        val response = httpRequest(apiKey, "GET", "/subtitles$query", null)
        return when {
            response == null -> SubtitleSearchOutcome.Failed
            response.first == HTTP_TOO_MANY -> SubtitleSearchOutcome.QuotaReached
            response.first != HttpURLConnection.HTTP_OK ->
                if (looksLikeQuota(response.second)) {
                    SubtitleSearchOutcome.QuotaReached
                } else {
                    SubtitleSearchOutcome.Failed
                }
            else -> SubtitleSearchOutcome.Found(parseSearchResponse(response.second))
        }
    }

    /**
     * Exchanges a file id for a time-limited download link. Spends
     * one unit of the daily quota server-side.
     */
    suspend fun downloadLink(
        apiKey: String,
        fileId: Int
    ): SubtitleDownloadOutcome {
        val payload = JSONObject().put("file_id", fileId).toString()
        val response = httpRequest(apiKey, "POST", "/download", payload)
        return when {
            response == null -> SubtitleDownloadOutcome.Failed
            response.first == HTTP_TOO_MANY -> SubtitleDownloadOutcome.QuotaReached
            response.first != HttpURLConnection.HTTP_OK ->
                if (looksLikeQuota(response.second)) {
                    SubtitleDownloadOutcome.QuotaReached
                } else {
                    SubtitleDownloadOutcome.Failed
                }
            else -> parseDownloadResponse(response.second)
        }
    }

    /** Lightweight key check for Settings: true when the API answers 200. */
    suspend fun ping(apiKey: String): Boolean =
        httpRequest(apiKey, "GET", "/infos/languages", null)?.first ==
            HttpURLConnection.HTTP_OK

    /**
     * Downloads the subtitle file itself from a time-limited [link]
     * (returned by [downloadLink]). These links are plain file URLs
     * on the provider's CDN — no key header is sent to them.
     */
    suspend fun downloadFileBytes(link: String): ByteArray? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(link).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext null
                }
                connection.inputStream.use { it.readBytes() }
            } catch (e: Exception) {
                null
            } finally {
                connection?.disconnect()
            }
        }

    /**
     * Parses a search response: `data[].attributes` carries
     * language, release, download_count, ratings {rating} and
     * files[0].file_id. Malformed entries are skipped. Exposed for
     * tests.
     */
    fun parseSearchResponse(body: String): List<SubtitleResult> = runCatching {
        val data = JSONObject(body).optJSONArray("data")
            ?: return@runCatching emptyList()
        val results = ArrayList<SubtitleResult>(data.length())
        for (i in 0 until data.length()) {
            val attributes = data.optJSONObject(i)?.optJSONObject("attributes")
                ?: continue
            val files = attributes.optJSONArray("files")
            val fileId = files?.optJSONObject(0)?.optInt("file_id") ?: continue
            if (fileId <= 0) continue
            results += SubtitleResult(
                fileId = fileId,
                language = attributes.optString("language").ifBlank { "en" },
                releaseName = attributes.optString("release").ifBlank { null },
                downloadCount = attributes.optInt("download_count"),
                rating = attributes.optJSONObject("ratings")
                    ?.optDouble("rating", Double.NaN)
                    ?.takeIf { !it.isNaN() && it > 0.0 },
                hearingImpaired = attributes.optBoolean("hearing_impaired")
            )
        }
        results
    }.getOrDefault(emptyList())

    /** Parses a download response: `{"link": "…", "file_name": "…"}`. */
    fun parseDownloadResponse(body: String): SubtitleDownloadOutcome = runCatching {
        val root = JSONObject(body)
        val link = root.optString("link")
        val fileName = root.optString("file_name")
        if (link.isBlank()) {
            SubtitleDownloadOutcome.Failed
        } else {
            SubtitleDownloadOutcome.Ready(
                link = link,
                fileName = fileName.ifBlank { "subtitle.srt" }
            )
        }
    }.getOrDefault(SubtitleDownloadOutcome.Failed)

    /** True when an error body reads like quota exhaustion. */
    fun looksLikeQuota(body: String): Boolean {
        val lower = body.lowercase()
        return "download limit" in lower || "quota" in lower ||
            "too many" in lower || "limit reached" in lower
    }

    private const val HTTP_TOO_MANY = 429

    /**
     * One API call with the mandatory headers. Returns
     * (statusCode, body) — error bodies are read too (quota wording
     * lives in them) — or null on a network failure.
     */
    private suspend fun httpRequest(
        apiKey: String,
        method: String,
        path: String,
        jsonBody: String?
    ): Pair<Int, String>? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(BASE_URL + path).openConnection()
                as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Api-Key", apiKey)
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
                if (jsonBody != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            if (jsonBody != null) {
                connection.outputStream.use { out ->
                    out.write(jsonBody.toByteArray(Charsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val text = stream?.bufferedReader()?.use(BufferedReader::readText)
                .orEmpty()
            code to text
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
