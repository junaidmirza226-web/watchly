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

    /** Smallest size that reads as the real film, not a clip (Phase D). */
    private const val EFFICIENT_MIN_BYTES = 8_000_000L

    /** Sort orders the Movies tab offers. Archive has no rating field. */
    enum class MovieSort(val sortParam: String) {
        POPULAR("downloads desc"),
        NEWEST("year desc"),
        A_Z("title asc")
    }

    /**
     * One curated Internet Archive collection Watchly surfaces.
     * Collection ids + sizes verified live 2026-11-06:
     * feature_films ≈ 28.5k, classic_tv ≈ 11k (with the movies
     * mediatype filter), animationandcartoons ≈ 16k, prelinger
     * ≈ 10.4k. All four are public-domain / openly-licensed
     * collections — the only kind Watchly ever badges as playable.
     */
    data class ArchiveCollection(
        val id: String,
        val displayName: String,
        val mediaType: MediaType
    )

    val FEATURE_FILMS = ArchiveCollection("feature_films", "Feature Films", MediaType.MOVIE)
    val CLASSIC_TV = ArchiveCollection("classic_tv", "Classic TV", MediaType.TV)
    val ANIMATION_AND_CARTOONS =
        ArchiveCollection("animationandcartoons", "Animation & Cartoons", MediaType.MOVIE)
    val PRELINGER = ArchiveCollection("prelinger", "Prelinger Classics", MediaType.MOVIE)

    /** Every curated collection, in Home row order. */
    val COLLECTIONS: List<ArchiveCollection> =
        listOf(FEATURE_FILMS, CLASSIC_TV, ANIMATION_AND_CARTOONS, PRELINGER)

    /**
     * One page of titles from any curated [collection], best-first
     * for [sort]. [page] is 1-based. Titles carry the collection's
     * media type, so Classic TV items browse/search as shows while
     * keeping their Archive id (and therefore their playable files).
     */
    suspend fun fetchCollectionPage(
        collection: ArchiveCollection,
        page: Int = 1,
        perPage: Int = 30,
        sort: MovieSort = MovieSort.POPULAR
    ): List<Title> = withContext(Dispatchers.IO) {
        val query = "mediatype:movies AND collection:${collection.id}"
        val url = "$BASE_URL?q=${URLEncoder.encode(query, "UTF-8")}" +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year" +
            "&rows=$perPage&start=${(page - 1) * perPage}" +
            "&sort%5B%5D=${URLEncoder.encode(sort.sortParam, "UTF-8")}" +
            "&output=json"
        parseSearchResponse(httpGet(url), collection.mediaType)
    }

    /**
     * Free-text search over one curated [collection] (title field,
     * download-count order). Returns an empty list for a blank query.
     */
    suspend fun searchCollection(
        query: String,
        collection: ArchiveCollection,
        limit: Int = 20
    ): List<Title> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val queryText =
            "title:($q) AND mediatype:movies AND collection:${collection.id}"
        val url = "$BASE_URL?q=${URLEncoder.encode(queryText, "UTF-8")}" +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year" +
            "&rows=$limit&sort%5B%5D=downloads+desc&output=json"
        parseSearchResponse(httpGet(url), collection.mediaType)
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
        val sources = ArrayList<String?>()
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
            sources.add(file.optText("source"))
        }
        // Efficient first (Phase D, user report: downloads too slow):
        // the DEFAULT file (details Play, the quality picker's first
        // row, downloads) should be an efficient MP4 derivative —
        // big enough to be the real film, small enough to stream and
        // download on a slow connection. The full-quality original
        // stays one tap away in the picker. Rank order:
        //  0 = MP4 derivatives ≥ 8 MB (smallest adequate first)
        //  1 = smaller MP4 derivatives (clips/previews; bigger first)
        //  2 = MP4 originals (biggest first)
        //  3 = everything else (MKV/WebM/…; biggest first)
        return playable.indices
            .map { i ->
                val file = playable[i]
                val size = file.sizeBytes ?: 0L
                val mp4 = file.fileName.endsWith(".mp4", ignoreCase = true) ||
                    (file.format ?: "").contains("mpeg4", ignoreCase = true)
                val derivative = sources[i] == "derivative"
                val rank = when {
                    mp4 && derivative && size >= EFFICIENT_MIN_BYTES -> 0
                    mp4 && derivative -> 1
                    mp4 -> 2
                    else -> 3
                }
                // Rank 0 sorts ascending (smallest adequate); the
                // rest descending (most substantial first).
                Triple(file, rank, if (rank == 0) size else -size)
            }
            .sortedWith(compareBy({ it.second }, { it.third }))
            .map { it.first }
    }

    /** Archive `format` values that are playable video (not thumbnails/text). */
    private fun isPlayableFormat(format: String): Boolean {
        val f = format.lowercase()
        return f.contains("h.264") || f.contains("mpeg4") ||
            f.contains("ogg video") || f.contains("matroska") ||
            f.contains("webm") || f == "mp4" || f.contains("h.265")
    }

    /** Parses an advancedsearch body as feature films (MOVIE type). */
    internal fun parseSearchResponse(body: String): List<Title> =
        parseSearchResponse(body, MediaType.MOVIE)

    /**
     * Parses an advancedsearch JSON body into [Title]s of
     * [mediaType]. Kept separate from the network call so unit tests
     * can feed it saved responses.
     *
     * Cycle 7 truth rule: a body that cannot be understood at all
     * (not JSON, or no response/docs structure — an error page, a
     * captive-portal block, a truncated read on a dying connection)
     * THROWS, exactly like [httpGet] does for HTTP errors. It used
     * to return an empty list, which made a failed load
     * indistinguishable from a genuinely empty catalogue in Browse
     * (no error, no Retry — just a wrong "nothing here" state).
     * A valid body with zero docs still returns an empty list.
     */
    internal fun parseSearchResponse(body: String, mediaType: MediaType): List<Title> {
        val docs = runCatching {
            JSONObject(body).optJSONObject("response")?.optJSONArray("docs")
        }.getOrNull() ?: throw IllegalStateException(
            "Archive search response could not be parsed"
        )

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
                    mediaType = mediaType,
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
