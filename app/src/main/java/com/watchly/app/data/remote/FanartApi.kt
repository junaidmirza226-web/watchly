/*
 * Watchly — Fanart.tv client (cycle 4).
 *
 * Fanart is an artwork ENHANCEMENT layer only: TMDB stays the
 * primary image source, and Fanart fills two gaps on Title Details —
 * an HD clear logo (transparent wordmark PNG) to lay over the hero,
 * and an extra backdrop when TMDB has none. Lookups are by TMDB id:
 * `/v3/movies/{tmdbId}` or `/v3/tv/{tmdbId}`.
 *
 * Auth is the user's own key as the `api_key` query parameter. A
 * title Fanart doesn't know returns HTTP 404 — a normal outcome,
 * treated as "no enhancement", never an error state.
 */
package com.watchly.app.data.remote

import com.watchly.app.data.model.FanartArtwork
import com.watchly.app.data.model.MediaType
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object FanartApi {

    private const val BASE_URL = "https://webservice.fanart.tv/v3"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /**
     * Best logo + backdrop for the title with this TMDB id, or null
     * when Fanart has nothing (or anything fails).
     */
    suspend fun fetchArtwork(
        apiKey: String,
        tmdbId: Int,
        type: MediaType
    ): FanartArtwork? {
        val segment = if (type == MediaType.MOVIE) "movies" else "tv"
        val url = "$BASE_URL/$segment/$tmdbId" +
            "?api_key=" + URLEncoder.encode(apiKey, "UTF-8")
        val body = httpGet(url) ?: return null
        return parseArtwork(body, type)?.takeIf { !it.isEmpty }
    }

    /**
     * Parses one Fanart record. Field names differ by kind (verified
     * live 2026-11-06): movies use "hdmovielogo"/"moviethumb"-style
     * keys under "hdmovielogo" + "moviebackground"; shows use
     * "hdtvlogo" + "showbackground". Each entry carries url, lang
     * and likes. Exposed for tests.
     */
    fun parseArtwork(body: String, type: MediaType): FanartArtwork? = runCatching {
        val root = JSONObject(body)
        val logoKey = if (type == MediaType.MOVIE) "hdmovielogo" else "hdtvlogo"
        val backdropKey =
            if (type == MediaType.MOVIE) "moviebackground" else "showbackground"
        FanartArtwork(
            logoUrl = bestPick(root.optJSONArray(logoKey)),
            backdropUrl = bestPick(root.optJSONArray(backdropKey))
        )
    }.getOrNull()

    /**
     * Picks one image URL from a Fanart array: English art first
     * (logos with text read wrong in other languages), then the
     * community favourite (most likes). Null when the array is
     * empty or carries no usable URL.
     */
    private fun bestPick(array: JSONArray?): String? {
        if (array == null) return null
        var bestUrl: String? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
            val langBonus = if (item.optString("lang") == "en") 1_000_000 else 0
            val likes = item.optString("likes").toIntOrNull() ?: 0
            val score = langBonus + likes
            if (score > bestScore) {
                bestScore = score
                bestUrl = url
            }
        }
        return bestUrl
    }

    /** Plain GET; null on any HTTP/network failure (incl. 404). */
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
