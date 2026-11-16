package com.watchly.app.data.remote.providers

import android.content.Context
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.data.remote.config.ProviderEntry
import com.watchly.app.data.remote.config.RemoteProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Aggregates streams across Watchly's multi-server cloud network:
 * - Instant (<50ms) server URL construction for 0-wait playback & 1-tap switching
 * - Per-provider isolated timeout (3.5s) so one slow server never discards fast ones
 * - Filters out raw file-host homepages that don't index TMDB/IMDb IDs
 * - Deduplicates URLs and ranks direct HLS/MP4 streams first, then prioritized servers
 */
object StreamAggregator {

    private const val PER_PROVIDER_TIMEOUT_MS = 3_500L

    /** Non-indexing file hosts that must never be treated as TMDB embed servers. */
    private val NON_INDEXED_FILE_HOSTS = setOf(
        "streamwish.to",
        "dood.to",
        "filemoon.sx",
        "vidcloud9.com"
    )

    /**
     * Builds instant, zero-network embed [StreamSource]s for immediate playback
     * and the in-player / details-screen Server Switcher.
     */
    fun getInstantEmbedServers(
        title: Title,
        season: Int? = null,
        episode: Int? = null,
        context: Context? = null
    ): List<StreamSource> {
        val tmdbId = title.tmdbId
        val imdbId = title.imdbId
        if (tmdbId == null && imdbId == null) return emptyList()

        val entries = RemoteProviderConfig.getEnabledProviders(context)
            .filterNot { entry ->
                NON_INDEXED_FILE_HOSTS.any { host -> entry.baseUrl.contains(host, ignoreCase = true) }
            }

        val sources = mutableListOf<StreamSource>()
        // Branded consumer services first (Netflix, Amazon Prime Video,
        // Disney+ Hotstar, Zee5 …) so Sources/Servers show what people look
        // for; the raw embed hosts below stay as instant backups.
        sources += OttServices.buildSources(
            tmdbId = tmdbId,
            imdbId = imdbId,
            mediaType = title.mediaType,
            season = season,
            episode = episode
        )
        for (entry in entries) {
            val embedUrl = buildEmbedUrlForEntry(
                entry = entry,
                tmdbId = tmdbId,
                imdbId = imdbId,
                mediaType = title.mediaType,
                season = season,
                episode = episode
            ) ?: continue
            if (!StreamProviderHelper.isValidHttpsUrl(embedUrl)) continue
            val headers = buildMap {
                if (entry.requiresReferer) {
                    put("Referer", entry.baseUrl)
                    put("Origin", entry.baseUrl)
                }
                put("User-Agent", entry.userAgent?.takeIf { it.isNotBlank() } ?: StreamProviderHelper.nextUserAgent())
            }
            sources.add(
                StreamSource(
                    providerName = entry.name,
                    url = embedUrl,
                    quality = "Auto HD",
                    isHls = false,
                    headers = headers
                )
            )
        }
        return sources.distinctBy { it.url }
    }

    private fun buildEmbedUrlForEntry(
        entry: ProviderEntry,
        tmdbId: Int?,
        imdbId: String?,
        mediaType: MediaType,
        season: Int?,
        episode: Int?
    ): String? {
        val base = entry.baseUrl.trimEnd('/')
        val lower = entry.name.lowercase()
        val id = tmdbId?.toString() ?: imdbId ?: return null
        val isMovie = mediaType == MediaType.MOVIE
        val s = season ?: 1
        val e = episode ?: 1

        return when {
            lower.contains("vidlink") || base.contains("vidlink") -> {
                val tid = tmdbId?.toString() ?: return null
                if (isMovie) "$base/movie/$tid?autoplay=true&primaryColor=D4AF37"
                else "$base/tv/$tid/$s/$e?autoplay=true&primaryColor=D4AF37"
            }
            lower.contains("multiembed") || base.contains("multiembed") -> {
                if (tmdbId != null) {
                    if (isMovie) "$base/?video_id=$tmdbId&tmdb=1"
                    else "$base/?video_id=$tmdbId&tmdb=1&s=$s&e=$e"
                } else if (imdbId != null) {
                    if (isMovie) "$base/?video_id=$imdbId"
                    else "$base/?video_id=$imdbId&s=$s&e=$e"
                } else null
            }
            lower.contains("2embed") || base.contains("2embed") -> {
                if (isMovie) "$base/embed/$id"
                else "$base/embedtv/$id&s=$s&e=$e"
            }
            base.contains("autoembed.co") -> {
                if (tmdbId != null) {
                    if (isMovie) "$base/movie/tmdb/$tmdbId"
                    else "$base/tv/tmdb/$tmdbId-$s-$e"
                } else if (imdbId != null) {
                    if (isMovie) "$base/movie/imdb/$imdbId"
                    else "$base/tv/imdb/$imdbId-$s-$e"
                } else null
            }
            lower.contains("moviesapi") || base.contains("moviesapi") -> {
                val tid = tmdbId?.toString() ?: return null
                if (isMovie) "$base/movie/$tid"
                else "$base/tv/$tid-$s-$e"
            }
            lower.contains("smashystream") || base.contains("smashystream") -> {
                if (tmdbId != null) {
                    if (isMovie) "$base/playere.php?tmdb=$tmdbId"
                    else "$base/playere.php?tmdb=$tmdbId&season=$s&episode=$e"
                } else null
            }
            else -> {
                // Standard VidSrc / NontonGo / Embed.su / AutoEmbed.cc convention
                if (isMovie) "$base/embed/movie/$id"
                else "$base/embed/tv/$id/$s/$e"
            }
        }
    }

    /**
     * Resolves all available streams for a given [Title].
     */
    suspend fun getStreams(
        title: Title,
        season: Int? = null,
        episode: Int? = null,
        context: Context? = null
    ): List<StreamSource> {
        return getStreams(
            tmdbId = title.tmdbId,
            imdbId = title.imdbId,
            title = title.name,
            year = title.year,
            mediaType = title.mediaType,
            season = season,
            episode = episode,
            context = context
        )
    }

    /**
     * Resolves streams across all enabled providers in parallel with
     * isolated per-provider timeouts so slow hosts never block playback.
     */
    suspend fun getStreams(
        tmdbId: Int?,
        imdbId: String?,
        title: String,
        year: String?,
        mediaType: MediaType,
        season: Int? = null,
        episode: Int? = null,
        context: Context? = null
    ): List<StreamSource> {
        val enabledConfigs = RemoteProviderConfig.getEnabledProviders(context)
            .filterNot { entry ->
                NON_INDEXED_FILE_HOSTS.any { host -> entry.baseUrl.contains(host, ignoreCase = true) }
            }
        val providers = buildProviders(enabledConfigs)
        if (providers.isEmpty()) return emptyList()

        val results = coroutineScope {
            providers.map { provider ->
                async {
                    withTimeoutOrNull(PER_PROVIDER_TIMEOUT_MS) {
                        runCatching {
                            provider.getStreams(
                                tmdbId = tmdbId,
                                imdbId = imdbId,
                                title = title,
                                year = year,
                                mediaType = mediaType,
                                season = season,
                                episode = episode
                            )
                        }.getOrDefault(emptyList())
                    } ?: emptyList()
                }
            }.awaitAll()
        }

        val priorityMap = providers.associate { it.name to it.priority }

        return results.flatten()
            .filter { StreamProviderHelper.isValidHttpsUrl(it.url) }
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<StreamSource> { isDirectVideoUrl(it.url) }
                    .thenByDescending { qualityRank(it.quality) }
                    .thenBy { priorityMap[it.providerName] ?: 99 }
            )
            .map { stream ->
                // Tag dubbed-audio variants from the provider's own
                // labels/URLs so they surface as clearly-marked sources.
                stream.language?.let { stream }
                    ?: stream.copy(
                        language = StreamProviderHelper.detectAudioLanguage(
                            "${stream.url} ${stream.quality} ${stream.providerName}"
                        )
                    )
            }
    }

    private fun isDirectVideoUrl(url: String): Boolean =
        url.contains(".m3u8", ignoreCase = true) ||
            url.contains(".mp4", ignoreCase = true) ||
            url.contains(".mkv", ignoreCase = true)

    /**
     * Builds active provider instances from remote configuration entries.
     */
    private fun buildProviders(entries: List<ProviderEntry>): List<StreamProvider> {
        val providers = mutableListOf<StreamProvider>()
        for (entry in entries) {
            if (!entry.enabled) continue
            val lower = entry.name.lowercase()
            val provider: StreamProvider = when {
                lower.contains("vidlink") -> VidLinkProvider(baseUrl = entry.baseUrl, priority = entry.priority)
                lower.contains("2embed") -> TwoEmbedProvider(baseUrl = entry.baseUrl, priority = entry.priority)
                lower.contains("autoembed") -> AutoEmbedProvider(baseUrl = entry.baseUrl, priority = entry.priority, name = entry.name)
                lower.contains("multiembed") -> MultiEmbedStreamProvider(baseUrl = entry.baseUrl, priority = entry.priority, name = entry.name)
                lower.contains("superstream") -> SuperStreamProvider(baseUrl = entry.baseUrl, priority = entry.priority)
                lower.contains("embed.su") -> EmbedSuProvider(baseUrl = entry.baseUrl, priority = entry.priority)
                lower.contains("moviesapi") -> MoviesApiProvider(baseUrl = entry.baseUrl, priority = entry.priority)
                lower.contains("smashystream") -> SmashyStreamProvider(baseUrl = entry.baseUrl, priority = entry.priority)
                else -> VidSrcProvider(baseUrl = entry.baseUrl, priority = entry.priority, name = entry.name)
            }
            providers.add(provider)
        }

        if (providers.isEmpty()) {
            return listOf(
                VidLinkProvider(priority = 1),
                VidSrcProvider(baseUrl = "https://vidsrc.to", priority = 2, name = "VidSrc.to"),
                VidSrcProvider(baseUrl = "https://vidsrc.me", priority = 3, name = "VidSrc.me"),
                MultiEmbedStreamProvider(priority = 4),
                AutoEmbedProvider(priority = 5),
                TwoEmbedProvider(priority = 6)
            )
        }

        return providers
    }

    private fun qualityRank(quality: String): Int = when {
        quality.contains("4k", ignoreCase = true) || quality.contains("2160", ignoreCase = true) -> 5
        quality.contains("1080", ignoreCase = true) -> 4
        quality.contains("720", ignoreCase = true) -> 3
        quality.contains("480", ignoreCase = true) -> 2
        quality.contains("360", ignoreCase = true) -> 1
        else -> 0
    }
}

/** Dedicated MultiEmbed resolver (`https://multiembed.mov/?video_id={tmdbId}&tmdb=1`). */
private class MultiEmbedStreamProvider(
    private val baseUrl: String = "https://multiembed.mov",
    override val priority: Int = 3,
    override val name: String = "MultiEmbed"
) : StreamProvider {
    override suspend fun getStreams(
        tmdbId: Int?,
        imdbId: String?,
        title: String,
        year: String?,
        mediaType: MediaType,
        season: Int?,
        episode: Int?
    ): List<StreamSource> = withContext(Dispatchers.IO) {
        val isMovie = mediaType == MediaType.MOVIE
        val s = season ?: 1
        val e = episode ?: 1
        val base = baseUrl.trimEnd('/')
        val embedUrl = when {
            tmdbId != null -> if (isMovie) "$base/?video_id=$tmdbId&tmdb=1" else "$base/?video_id=$tmdbId&tmdb=1&s=$s&e=$e"
            imdbId != null -> if (isMovie) "$base/?video_id=$imdbId" else "$base/?video_id=$imdbId&s=$s&e=$e"
            else -> return@withContext emptyList()
        }
        if (!StreamProviderHelper.isValidHttpsUrl(embedUrl)) return@withContext emptyList()
        val headers = mapOf(
            "Referer" to base,
            "User-Agent" to StreamProviderHelper.nextUserAgent()
        )
        listOf(
            StreamSource(
                providerName = name,
                url = embedUrl,
                quality = "Auto HD",
                isHls = false,
                headers = headers
            )
        )
    }
}
