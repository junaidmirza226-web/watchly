/*
 * Watchly — OMDb client (cycle 4).
 *
 * OMDb (omdbapi.com) is a field provider: it looks one title up by
 * IMDb id and returns the ratings bundle (IMDb score + votes,
 * Rotten Tomatoes, Metacritic, age rating). The free plan allows
 * 1,000 requests a day, so every successful lookup is cached
 * on-device for a week (see OmdbCache) — a title's ratings change
 * slowly, and repeat visits never spend quota.
 *
 * The key is the user's own (Settings → API Keys), sent as the
 * `apikey` query parameter. Never logged. Fail-soft throughout: a
 * missing key, a spent quota (HTTP 401 "Request limit reached!") or
 * any network failure simply means "no extra ratings to show".
 */
package com.watchly.app.data.remote

import com.watchly.app.data.model.OmdbRatings
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** On-device store for OMDb lookups, one entry per IMDb id. */
interface OmdbCache {
    /** Cached ratings + fetch time (epoch ms), or null. */
    fun get(imdbId: String): Pair<OmdbRatings, Long>?
    fun put(imdbId: String, ratings: OmdbRatings, fetchedAtMs: Long)
}

object OmdbApi {

    private const val BASE_URL = "https://www.omdbapi.com/"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** Ratings barely move; a week-old copy is still useful. */
    const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Ratings for [imdbId] ("tt0468569"), cache first: a fresh cache
     * entry is returned without touching the network (protecting the
     * 1,000/day free limit). Network results are written back.
     * Returns null on any failure — the UI just shows fewer ratings.
     */
    suspend fun getRatings(
        apiKey: String,
        imdbId: String,
        cache: OmdbCache?,
        nowMs: Long = System.currentTimeMillis()
    ): OmdbRatings? {
        val cached = cache?.get(imdbId)
        if (cached != null && nowMs - cached.second < CACHE_TTL_MS) {
            return cached.first
        }
        val fresh = fetchRatings(apiKey, imdbId) ?: return cached?.first
        cache?.put(imdbId, fresh, nowMs)
        return fresh
    }

    /** One live lookup; null on any failure or "not found". */
    suspend fun fetchRatings(apiKey: String, imdbId: String): OmdbRatings? {
        val url = BASE_URL +
            "?apikey=" + URLEncoder.encode(apiKey, "UTF-8") +
            "&i=" + URLEncoder.encode(imdbId, "UTF-8")
        val body = httpGet(url) ?: return null
        return parseResponse(body)
    }

    /**
     * Parses one OMDb title response. OMDb reports failures in-band:
     * `"Response":"False"` (wrong key, unknown id, spent limit).
     * Exposed for tests.
     */
    fun parseResponse(body: String): OmdbRatings? = runCatching {
        val root = JSONObject(body)
        if (!root.optString("Response").equals("True", ignoreCase = true)) {
            return@runCatching null
        }
        var rottenTomatoes: Int? = null
        val ratings = root.optJSONArray("Ratings")
        if (ratings != null) {
            for (i in 0 until ratings.length()) {
                val entry = ratings.optJSONObject(i) ?: continue
                if (entry.optString("Source") == "Rotten Tomatoes") {
                    rottenTomatoes = entry.optString("Value")
                        .substringBefore('%').trim().toIntOrNull()
                }
            }
        }
        val ratingsOut = OmdbRatings(
            imdbRating = root.optString("imdbRating")
                .takeIf { it.isNotBlank() && it != "N/A" }?.toDoubleOrNull(),
            imdbVotes = root.optString("imdbVotes")
                .takeIf { it.isNotBlank() && it != "N/A" },
            rottenTomatoesPercent = rottenTomatoes,
            metacriticScore = root.optString("Metascore")
                .takeIf { it.isNotBlank() && it != "N/A" }?.toIntOrNull(),
            rated = root.optString("Rated")
                .takeIf { it.isNotBlank() && it != "N/A" }
        )
        if (ratingsOut.hasAnyRating) ratingsOut else null
    }.getOrNull()

    /** Plain GET; null on any HTTP/network failure. */
    private suspend fun httpGet(url: String): String? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
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
