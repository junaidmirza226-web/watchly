package com.watchly.app.data.remote.providers

import com.watchly.app.data.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolver for 2Embed (2embed.cc).
 * Patterns:
 *  - Movie: {baseUrl}/embed/{imdbId} or {baseUrl}/embed/{tmdbId}
 *  - TV: {baseUrl}/embed/series?imdb={imdbId}&sea={season}&epi={episode} or {baseUrl}/embed/tv/{tmdbId}/{season}/{episode}
 */
class TwoEmbedProvider(
    private val baseUrl: String = "https://www.2embed.cc",
    override val priority: Int = 5
) : StreamProvider {

    override val name: String = "2Embed"

    override suspend fun getStreams(
        tmdbId: Int?,
        imdbId: String?,
        title: String,
        year: String?,
        mediaType: MediaType,
        season: Int?,
        episode: Int?
    ): List<StreamSource> = withContext(Dispatchers.IO) {
        val sources = mutableListOf<StreamSource>()
        try {
            val embedUrl = if (mediaType == MediaType.MOVIE) {
                val id = imdbId ?: tmdbId?.toString() ?: return@withContext emptyList()
                "$baseUrl/embed/$id"
            } else {
                val s = season ?: 1
                val e = episode ?: 1
                if (imdbId != null) {
                    "$baseUrl/embed/series?imdb=$imdbId&sea=$s&epi=$e"
                } else if (tmdbId != null) {
                    "$baseUrl/embed/tv/$tmdbId/$s/$e"
                } else {
                    return@withContext emptyList()
                }
            }

            val ua = StreamProviderHelper.nextUserAgent()
            val headers = mapOf(
                "Referer" to baseUrl,
                "Origin" to baseUrl,
                "User-Agent" to ua
            )

            val html = StreamProviderHelper.fetchString(embedUrl, referer = baseUrl, customHeaders = headers)
            if (html != null) {
                val streams = StreamProviderHelper.extractM3u8Urls(html)
                for (stream in streams) {
                    sources.add(
                        StreamSource(
                            providerName = name,
                            url = stream,
                            quality = "1080p",
                            isHls = true,
                            headers = headers
                        )
                    )
                }

                if (sources.isEmpty()) {
                    val mp4s = StreamProviderHelper.extractMp4Urls(html)
                    for (stream in mp4s) {
                        sources.add(
                            StreamSource(
                                providerName = name,
                                url = stream,
                                quality = "720p",
                                isHls = false,
                                headers = headers
                            )
                        )
                    }
                }
            }

            if (sources.isEmpty() && StreamProviderHelper.isValidHttpsUrl(embedUrl)) {
                sources.add(
                    StreamSource(
                        providerName = name,
                        url = embedUrl,
                        quality = "Auto",
                        isHls = true,
                        headers = headers
                    )
                )
            }
        } catch (_: Exception) {
            // Graceful degradation
        }
        sources
    }
}
