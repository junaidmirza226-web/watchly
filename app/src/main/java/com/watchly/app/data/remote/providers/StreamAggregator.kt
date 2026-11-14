package com.watchly.app.data.remote.providers

import android.content.Context
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.data.remote.config.ProviderEntry
import com.watchly.app.data.remote.config.RemoteProviderConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Aggregates streams from multiple streaming providers concurrently:
 * - Reads enabled providers from [RemoteProviderConfig]
 * - Queries all active providers in parallel using coroutineScope { async }
 * - 15-second total timeout
 * - Deduplicates URLs and sorts by quality (best first), then provider priority
 */
object StreamAggregator {

    private const val TOTAL_TIMEOUT_MS = 15_000L

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
     * Resolves streams across all enabled providers in parallel.
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
        val providers = buildProviders(enabledConfigs)

        if (providers.isEmpty()) return emptyList()

        val results = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            coroutineScope {
                providers.map { provider ->
                    async {
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
                    }
                }.awaitAll()
            }
        } ?: emptyList()

        val priorityMap = providers.associate { it.name to it.priority }

        return results.flatten()
            .filter { StreamProviderHelper.isValidHttpsUrl(it.url) }
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<StreamSource> { qualityRank(it.quality) }
                    .thenBy { priorityMap[it.providerName] ?: 99 }
            )
    }

    /**
     * Builds active provider instances from remote configuration entries.
     */
    private fun buildProviders(entries: List<ProviderEntry>): List<StreamProvider> {
        val providers = mutableListOf<StreamProvider>()
        for (entry in entries) {
            if (!entry.enabled) continue
            val lower = entry.name.lowercase()
            val provider: StreamProvider = when {
                lower.contains("vidsrc") -> VidSrcProvider(baseUrl = entry.baseUrl, priority = entry.priority)
                lower.contains("2embed") -> TwoEmbedProvider(baseUrl = entry.baseUrl, priority = entry.priority)
                lower.contains("autoembed") -> AutoEmbedProvider(baseUrl = entry.baseUrl, priority = entry.priority)
                lower.contains("vidlink") -> VidLinkProvider(baseUrl = entry.baseUrl, priority = entry.priority)
                lower.contains("superstream") -> SuperStreamProvider(baseUrl = entry.baseUrl, priority = entry.priority)
                lower.contains("embed.su") -> EmbedSuProvider(baseUrl = entry.baseUrl, priority = entry.priority)
                lower.contains("moviesapi") -> MoviesApiProvider(baseUrl = entry.baseUrl, priority = entry.priority)
                lower.contains("smashystream") -> SmashyStreamProvider(baseUrl = entry.baseUrl, priority = entry.priority)
                else -> {
                    // Fallback to generic embed resolver matching the base URL
                    VidSrcProvider(baseUrl = entry.baseUrl, priority = entry.priority)
                }
            }
            providers.add(provider)
        }

        // If no providers mapped from config, use defaults
        if (providers.isEmpty()) {
            return listOf(
                VidSrcProvider(priority = 1),
                TwoEmbedProvider(priority = 2),
                AutoEmbedProvider(priority = 3),
                EmbedSuProvider(priority = 4),
                VidLinkProvider(priority = 5),
                MoviesApiProvider(priority = 6),
                SuperStreamProvider(priority = 7),
                SmashyStreamProvider(priority = 8)
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
