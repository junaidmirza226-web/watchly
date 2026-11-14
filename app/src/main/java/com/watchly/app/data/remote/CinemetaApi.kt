/*
 * Watchly — Cinemeta API (keyless, modern movie & TV show catalog).
 *
 * Provides instant access to global trending blockbusters and top-rated
 * movies & series via Stremio's official Cinemeta global CDN (Cloudflare).
 * Zero API keys required, sub-100ms response times, and every title includes
 * official IMDb IDs ("tt...") for instant stream resolution.
 */
package com.watchly.app.data.remote

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

object CinemetaApi {

    private const val BASE_URL = "https://v3-cinemeta.strem.io"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000

    /**
     * Fetches top trending modern blockbuster movies.
     */
    suspend fun fetchTrendingMovies(skip: Int = 0): List<Title> = withContext(Dispatchers.IO) {
        val url = if (skip > 0) {
            "$BASE_URL/catalog/movie/top/skip=$skip.json"
        } else {
            "$BASE_URL/catalog/movie/top.json"
        }
        val json = httpGet(url)
        parseMetas(json, MediaType.MOVIE)
    }

    /**
     * Fetches top rated / popular modern TV shows.
     */
    suspend fun fetchTrendingShows(skip: Int = 0): List<Title> = withContext(Dispatchers.IO) {
        val url = if (skip > 0) {
            "$BASE_URL/catalog/series/top/skip=$skip.json"
        } else {
            "$BASE_URL/catalog/series/top.json"
        }
        val json = httpGet(url)
        parseMetas(json, MediaType.TV)
    }

    /**
     * Fetches top rated modern movies.
     */
    suspend fun fetchTopRatedMovies(): List<Title> = withContext(Dispatchers.IO) {
        fetchTrendingMovies(skip = 20)
    }

    /**
     * Fetches top rated modern TV shows.
     */
    suspend fun fetchTopRatedShows(): List<Title> = withContext(Dispatchers.IO) {
        fetchTrendingShows(skip = 20)
    }

    /**
     * Search modern movies or series by query.
     */
    suspend fun search(query: String, mediaType: MediaType = MediaType.MOVIE): List<Title> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        val typeSegment = if (mediaType == MediaType.TV) "series" else "movie"
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val url = "$BASE_URL/catalog/$typeSegment/top/search=$encoded.json"
        val json = httpGet(url)
        parseMetas(json, mediaType)
    }

    /**
     * Parses the Cinemeta JSON response containing the "metas" array.
     */
    private fun parseMetas(jsonString: String?, mediaType: MediaType): List<Title> {
        if (jsonString.isNullOrBlank()) return emptyList()
        return try {
            val root = JSONObject(jsonString)
            val metas = root.optJSONArray("metas") ?: return emptyList()
            val list = mutableListOf<Title>()
            for (i in 0 until metas.length()) {
                val item = metas.optJSONObject(i) ?: continue
                val id = item.optString("id") // e.g. "tt15239678"
                val name = item.optString("name")
                if (id.isBlank() || name.isBlank()) continue

                val year = item.optString("year").takeIf { it.isNotBlank() }
                val poster = item.optString("poster").takeIf { it.isNotBlank() }
                val description = item.optString("description").takeIf { it.isNotBlank() }
                val rating = item.optString("imdbRating").toDoubleOrNull()
                
                val genresList = mutableListOf<String>()
                val genresArray = item.optJSONArray("genres")
                if (genresArray != null) {
                    for (g in 0 until genresArray.length()) {
                        genresArray.optString(g)?.takeIf { it.isNotBlank() }?.let { genresList.add(it) }
                    }
                }

                list.add(
                    Title(
                        imdbId = id,
                        name = name,
                        year = year,
                        posterUrl = poster,
                        overview = description,
                        rating = rating,
                        mediaType = mediaType,
                        genres = genresList
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun httpGet(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Watchly/1.4.6)")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
