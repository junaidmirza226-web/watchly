package com.watchly.app.data.remote.providers

import com.watchly.app.data.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolver for VidSrc (vidsrc.to / vidsrc.pro / vidsrc.cc).
 * Patterns:
 *  - Movie: {baseUrl}/embed/movie/{tmdbId} or {baseUrl}/embed/movie/{imdbId}
 *  - TV: {baseUrl}/embed/tv/{tmdbId}/{season}/{episode}
 */
class VidSrcProvider(
    private val baseUrl: String = "https://vidsrc.to",
    override val priority: Int = 1
) : StreamProvider {

    override val name: String = "VidSrc"

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
            val id = tmdbId?.toString() ?: imdbId ?: return@withContext emptyList()
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
                // 1. Direct .m3u8 playlist streams
                val m3u8Urls = StreamProviderHelper.extractM3u8Urls(html)
                for (streamUrl in m3u8Urls) {
                    sources.add(
                        StreamSource(
                            providerName = name,
                            url = streamUrl,
                            quality = "1080p",
                            isHls = true,
                            headers = headers
                        )
                    )
                }

                // 2. Check embedded iframes / secondary sources
                if (sources.isEmpty()) {
                    val iframes = StreamProviderHelper.extractIframeSrcs(html)
                    for (iframe in iframes) {
                        val fullIframeUrl = if (iframe.startsWith("//")) "https:$iframe"
                        else if (iframe.startsWith("/")) "$baseUrl$iframe"
                        else iframe

                        val subHtml = StreamProviderHelper.fetchString(fullIframeUrl, referer = embedUrl, customHeaders = headers)
                        if (subHtml != null) {
                            val subStreams = StreamProviderHelper.extractM3u8Urls(subHtml)
                            for (streamUrl in subStreams) {
                                sources.add(
                                    StreamSource(
                                        providerName = name,
                                        url = streamUrl,
                                        quality = "1080p",
                                        isHls = true,
                                        headers = mapOf("Referer" to fullIframeUrl, "User-Agent" to ua)
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Fallback direct embed stream URL for ExoPlayer webview/hls handlers
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
            // Graceful degradation, never crash
        }
        sources
    }
}
