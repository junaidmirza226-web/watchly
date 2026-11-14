package com.watchly.app.data.remote.providers

import com.watchly.app.data.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolver for Embed.su (embed.su).
 * Patterns:
 *  - Movie: {baseUrl}/embed/movie/{tmdbId}
 *  - TV: {baseUrl}/embed/tv/{tmdbId}/{season}/{episode}
 */
class EmbedSuProvider(
    private val baseUrl: String = "https://embed.su",
    override val priority: Int = 4
) : StreamProvider {

    override val name: String = "Embed.su"

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
            val id = tmdbId?.toString() ?: return@withContext emptyList()
            val embedUrl = if (mediaType == MediaType.MOVIE) {
                "$baseUrl/embed/movie/$id"
            } else {
                val s = season ?: 1
                val e = episode ?: 1
                "$baseUrl/embed/tv/$id/$s/$e"
            }

            val ua = StreamProviderHelper.nextUserAgent()
            val headers = mapOf(
                "Referer" to baseUrl,
                "Origin" to baseUrl,
                "User-Agent" to ua
            )

            val html = StreamProviderHelper.fetchString(embedUrl, referer = baseUrl, customHeaders = headers)
            if (html != null) {
                val m3u8s = StreamProviderHelper.extractM3u8Urls(html)
                for (stream in m3u8s) {
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
