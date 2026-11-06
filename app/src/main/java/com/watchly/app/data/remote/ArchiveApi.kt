/*
 * Watchly — Internet Archive client (keyless, no API key required).
 *
 * Legal source: public-domain feature films from the Internet Archive
 * (collection:feature_films). Verified live 2026-11-06: advancedsearch
 * returns ~28,500 films; a subject filter (e.g. subject:horror) returns
 * 1,226. Posters come from the Archive thumbnail service
 * (archive.org/services/img/<identifier>).
 *
 * ASSUMPTIONS:
 *  - HTTP: java.net.HttpURLConnection (built into Android — no OkHttp dep).
 *  - JSON: org.json (built into Android — no kotlinx.serialization dep).
 *  - Concurrency: kotlinx-coroutines-android (suspend functions, IO dispatcher).
 *  - Requires the INTERNET permission in AndroidManifest.xml.
 *
 * Endpoint used (https://archive.org/advancedsearch.php):
 *  - q=mediatype:movies AND collection:feature_films [AND subject:"<term>"]
 *    &fl[]=identifier,title,year &rows=N &start=M &sort[]=... &output=json
 * All failures throw; callers (ViewModel) catch and degrade gracefully.
 */
package com.watchly.app.data.remote

import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.Title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object ArchiveApi {

    private const val BASE_URL = "https://archive.org/advancedsearch.php"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** Sort orders the Movies tab offers. Archive has no rating field. */
    enum class MovieSort(val sortParam: String) {
        POPULAR("downloads desc"),
        NEWEST("year desc"),
        A_Z("title asc")
    }

    /**
     * Genre chips shown on the Movies tab, mapped to the Archive `subject`
     * term used in the search query. Subjects are free-form tags on the
     * Archive, so these are best-effort filters, not exact categories.
     */
    val MOVIE_GENRES: Map<String, String> = linkedMapOf(
        "Horror" to "Horror",
        "Comedy" to "Comedy",
        "Drama" to "Drama",
        "Sci-Fi" to "Science Fiction",
        "Western" to "Western",
        "Animation" to "Animation",
        "Silent" to "Silent Film",
        "Musical" to "Musical"
    )

    /**
     * One page of public-domain feature films, best-first for [sort].
     * [page] is 1-based. [genre] is a display label from [MOVIE_GENRES]
     * (null = all genres).
     */
    suspend fun fetchMoviesPage(
        page: Int = 1,
        perPage: Int = 30,
        sort: MovieSort = MovieSort.POPULAR,
        genre: String? = null
    ): List<Title> = withContext(Dispatchers.IO) {
        val query = StringBuilder("mediatype:movies AND collection:feature_films")
        genre?.let { MOVIE_GENRES[it] }?.let { subject ->
            query.append(" AND subject:\"").append(subject).append('"')
        }
        val url = "$BASE_URL?q=${URLEncoder.encode(query.toString(), "UTF-8")}" +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year" +
            "&rows=$perPage&start=${(page - 1) * perPage}" +
            "&sort%5B%5D=${URLEncoder.encode(sort.sortParam, "UTF-8")}" +
            "&output=json"
        parseSearchResponse(httpGet(url))
    }

    /**
     * Free-text search over public-domain feature films. Matches the
     * title field; results are ordered by download count (a rough
     * popularity proxy, since Archive search has no relevance sort that
     * fits us). Verified live 2026-11-06: `title:(night of the living
     * dead)` returns 23 docs. Returns an empty list for a blank query.
     */
    suspend fun searchMovies(query: String, limit: Int = 20): List<Title> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val queryText =
                "title:($q) AND mediatype:movies AND collection:feature_films"
            val url = "$BASE_URL?q=${URLEncoder.encode(queryText, "UTF-8")}" +
                "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year" +
                "&rows=$limit&sort%5B%5D=downloads+desc&output=json"
            parseSearchResponse(httpGet(url))
        }

    /**
     * Playable video files for one Archive item, from the metadata
     * endpoint (`https://archive.org/metadata/<identifier>`). Only
     * real video files are returned (h.264 / MPEG4 / Ogg Video etc.),
     * largest first. Verified live 2026-11-06: Night of the Living
     * Dead → one 596 MB h.264 MP4. Empty list when the item has no
     * playable video file.
     */
    suspend fun fetchPlayableFiles(identifier: String): List<PlayableFile> =
        withContext(Dispatchers.IO) {
            val url = "https://archive.org/metadata/" +
                URLEncoder.encode(identifier, "UTF-8")
            parseMetadataFiles(identifier, httpGet(url))
        }

    /**
     * Parses a metadata JSON body into playable files. Kept separate so
     * unit tests can feed saved responses. [identifier] is needed to
     * build the download URLs (`/download/<id>/<filename>`).
     */
    internal fun parseMetadataFiles(identifier: String, body: String): List<PlayableFile> {
        val files = runCatching {
            JSONObject(body).optJSONArray("files")
        }.getOrNull() ?: return emptyList()
        val playable = ArrayList<PlayableFile>()
        for (i in 0 until files.length()) {
            val file = files.optJSONObject(i) ?: continue
            val format = file.optText("format") ?: continue
            if (!isPlayableFormat(format)) continue
            val name = file.optText("name") ?: continue
            val size = if (file.has("size") && !file.isNull("size")) {
                file.optString("size").toLongOrNull()
            } else {
                null
            }
            playable.add(
                PlayableFile(
                    url = "https://archive.org/download/$identifier/$name",
                    fileName = name,
                    format = format,
                    sizeBytes = size,
                    qualityLabel = file.optText("format")
                )
            )
        }
        // Largest first: the full-quality file is usually what the user wants.
        return playable.sortedByDescending { it.sizeBytes ?: 0L }
    }

    /** Archive `format` values that are playable video (not thumbnails/text). */
    private fun isPlayableFormat(format: String): Boolean {
        val f = format.lowercase()
        return f.contains("h.264") || f.contains("mpeg4") ||
            f.contains("ogg video") || f.contains("matroska") ||
            f.contains("webm") || f == "mp4" || f.contains("h.265")
    }

    /**
     * Parses an advancedsearch JSON body into [Title]s. Kept separate from
     * the network call so unit tests can feed it saved responses.
     */
    internal fun parseSearchResponse(body: String): List<Title> {
        val docs = runCatching {
            JSONObject(body).optJSONObject("response")?.optJSONArray("docs")
        }.getOrNull() ?: return emptyList()

        val titles = ArrayList<Title>(docs.length())
        for (i in 0 until docs.length()) {
            val doc = docs.optJSONObject(i) ?: continue
            val identifier = doc.optText("identifier") ?: continue
            val name = doc.optText("title") ?: continue
            titles.add(
                Title(
                    archiveId = identifier,
                    name = name,
                    // Archive thumbnail service: a poster image for the item.
                    posterUrl = "https://archive.org/services/img/$identifier",
                    mediaType = MediaType.MOVIE,
                    year = doc.optText("year")
                )
            )
        }
        return titles
    }

    // ------------------------------------------------------------------
    // HTTP helper (same shape as TvMazeApi / AniListApi)
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
                throw IllegalStateException("Archive HTTP $code: ${body.take(200)}")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }
}
