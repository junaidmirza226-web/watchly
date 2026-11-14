/*
 * Watchly — YouTube (official channels only) client, keyless.
 *
 * Watchly surfaces free full-length uploads from a small curated set
 * of OFFICIAL channels — studios and licensed anime distributors that
 * publish complete films/episodes on their own YouTube channels.
 * Discovery uses each channel's public RSS feed
 * (youtube.com/feeds/videos.xml?channel_id=…): no API key exists, and
 * none is needed for reads. Playback never touches the raw stream —
 * titles carry a [Title.youtubeId] and play through YouTube's
 * official embedded player (see ui/player), exactly as YouTube's
 * terms require.
 *
 * Channel ids verified live 2026-11-06 against each channel's
 * canonical youtube.com URL, and every feed below was fetched and
 * returned real entries the same day:
 *  - Muse Asia (official anime, Medialink) — full episodes
 *  - Shemaroo Movies (official Bollywood studio channel)
 *  - Rajshri (official Bollywood studio channel)
 *  - Goldmines (official Hindi cinema channel)
 *  - Ultra Movie Parlour (official Bollywood classics channel)
 * (Ani-One Asia is the other big official anime channel; its channel
 * page was unreachable from this build machine, so its id is NOT
 * included — an unverified id would surface the wrong channel.)
 *
 * All failures throw; callers catch and fail soft (the row hides).
 */
package com.watchly.app.data.remote

import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object YouTubeApi {

    private const val FEED_BASE = "https://www.youtube.com/feeds/videos.xml?channel_id="
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** One curated official channel and the kind of titles it carries. */
    data class YouTubeChannel(
        val channelId: String,
        val displayName: String,
        val mediaType: MediaType
    )

    /** The curated official channels, in Home row order. */
    val CHANNELS: List<YouTubeChannel> = listOf(
        YouTubeChannel(
            channelId = "UCGbshtvS9t-8CW11W7TooQg",
            displayName = "Muse Asia",
            mediaType = MediaType.ANIME
        ),
        YouTubeChannel(
            // Verified 2026-11-06 (cycle 6): the @AniOneAsia handle's
            // canonical URL resolves to this channel id, and its live
            // RSS feed returns the "Ani-One Asia" feed. (Cycle 4 had
            // excluded it as unverifiable from the build VM.)
            channelId = "UC0wNSTMWIL3qaorLx0jie6A",
            displayName = "Ani-One Asia",
            mediaType = MediaType.ANIME
        ),
        YouTubeChannel(
            channelId = "UCBOmfqgTZi7yDp4-3Lr_3lA",
            displayName = "Shemaroo Movies",
            mediaType = MediaType.MOVIE
        ),
        YouTubeChannel(
            channelId = "UCEKWXRsfUHkan-D_ljU8Asw",
            displayName = "Rajshri",
            mediaType = MediaType.MOVIE
        ),
        YouTubeChannel(
            channelId = "UCyoXW-Dse7fURq30EWl_CUA",
            displayName = "Goldmines",
            mediaType = MediaType.MOVIE
        ),
        YouTubeChannel(
            channelId = "UCYauDsl-rswjQGpA0_-Z9Pw",
            displayName = "Ultra Movie Parlour",
            mediaType = MediaType.MOVIE
        )
    )

    /**
     * Latest uploads of one official channel as playable [Title]s
     * (newest first, the feed's own order). Every returned title has
     * a real [Title.youtubeId] taken from the feed, so its ▶ Playable
     * badge is earned; a channel whose feed fails throws and its row
     * simply doesn't appear.
     */
    suspend fun fetchChannelVideos(
        channel: YouTubeChannel,
        limit: Int = 15
    ): List<Title> = withContext(Dispatchers.IO) {
        parseFeed(httpGet(FEED_BASE + channel.channelId), channel).take(limit)
    }

    /**
     * Parses one channel RSS (Atom) body into titles. Pure string
     * work — no XML library — so it is unit-testable on the JVM.
     * Entries without a video id or a title are skipped; nothing is
     * invented when the feed is malformed (empty list).
     */
    internal fun parseFeed(body: String, channel: YouTubeChannel): List<Title> {
        val entries = body.split("<entry>")
        if (entries.size < 2) return emptyList()
        val titles = ArrayList<Title>()
        for (block in entries.drop(1)) {
            val videoId = extractTag(block, "yt:videoId") ?: continue
            val name = extractTag(block, "title")?.let { unescapeXml(it) }
                ?.takeIf { it.isNotBlank() } ?: continue
            val published = extractTag(block, "published")
            val thumbnail = extractAttr(block, "media:thumbnail", "url")
            titles.add(
                Title(
                    youtubeId = videoId,
                    name = name,
                    posterUrl = thumbnail
                        ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                    mediaType = channel.mediaType,
                    year = published?.takeIf { it.length >= 4 }?.substring(0, 4)
                )
            )
        }
        return titles
    }

    /** Text of the first `<tag>…</tag>` in [block], or null. */
    private fun extractTag(block: String, tag: String): String? {
        val open = "<$tag>"
        val close = "</$tag>"
        val start = block.indexOf(open)
        if (start < 0) return null
        val end = block.indexOf(close, start + open.length)
        if (end < 0) return null
        return block.substring(start + open.length, end).trim()
            .takeIf { it.isNotEmpty() }
    }

    /** Value of [attr] on the first `<tag …>` element in [block]. */
    private fun extractAttr(block: String, tag: String, attr: String): String? {
        val start = block.indexOf("<$tag")
        if (start < 0) return null
        val end = block.indexOf('>', start)
        if (end < 0) return null
        val element = block.substring(start, end)
        val marker = "$attr=\""
        val attrStart = element.indexOf(marker)
        if (attrStart < 0) return null
        val valueStart = attrStart + marker.length
        val valueEnd = element.indexOf('"', valueStart)
        if (valueEnd < 0) return null
        return element.substring(valueStart, valueEnd).takeIf { it.isNotBlank() }
    }

    /** Decodes the XML entities a feed title can carry. */
    private fun unescapeXml(text: String): String {
        var out = text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
        // Numeric entities (&#8212; style) before &amp; so a literal
        // "&amp;#8212;" stays an escaped ampersand, not an entity.
        out = Regex("&#(\\d+);").replace(out) { match ->
            match.groupValues[1].toIntOrNull()
                ?.takeIf { it in 1..0xFFFF }
                ?.let { it.toChar().toString() }
                ?: match.value
        }
        return out.replace("&amp;", "&").trim()
    }

    // ------------------------------------------------------------------
    // HTTP helper (same shape as ArchiveApi / TmdbApi)
    // ------------------------------------------------------------------

    private fun httpGet(urlString: String): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/atom+xml")
            setRequestProperty("User-Agent", "Watchly/1.0 (Android)")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                .use { it.readText() }
            if (code !in 200..299) {
                throw IllegalStateException("YouTube HTTP $code: ${body.take(200)}")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }
}
