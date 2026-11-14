package com.watchly.app.data.remote.providers

import com.watchly.app.data.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Resolver for SuperStream API (superstream.tech).
 */
class SuperStreamProvider(
    private val baseUrl: String = "https://superstream.tech",
    override val priority: Int = 9
) : StreamProvider {

    override val name: String = "SuperStream"

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
            val apiUrl = if (mediaType == MediaType.MOVIE) {
                "$baseUrl/api/stream/movie?id=$id"
            } else {
                val s = season ?: 1
                val e = episode ?: 1
                "$baseUrl/api/stream/tv?id=$id&season=$s&episode=$e"
            }

            val ua = "WatchlyApp/1.4.2 (Android; Mobile)"
            val headers = mapOf(
                "User-Agent" to ua,
                "Accept" to "application/json"
            )

            val jsonString = StreamProviderHelper.fetchString(apiUrl, customHeaders = headers)
            if (jsonString != null) {
                try {
                    val root = JSONObject(jsonString)
                    val data = root.optJSONArray("streams") ?: root.optJSONArray("data")
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val streamObj = data.optJSONObject(i) ?: continue
                            val url = streamObj.optString("url", "")
                            val quality = streamObj.optString("quality", "1080p")
                            val isHls = url.contains(".m3u8", ignoreCase = true)
                            if (StreamProviderHelper.isValidHttpsUrl(url)) {
                                sources.add(
                                    StreamSource(
                                        providerName = name,
                                        url = url,
                                        quality = quality,
                                        isHls = isHls,
                                        headers = mapOf("User-Agent" to ua)
                                    )
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Try regex extraction from response
                    val m3u8s = StreamProviderHelper.extractM3u8Urls(jsonString)
                    for (url in m3u8s) {
                        sources.add(
                            StreamSource(
                                providerName = name,
                                url = url,
                                quality = "1080p",
                                isHls = true,
                                headers = headers
                            )
                        )
                    }
                }
            }

            // Fallback direct embed URL if API didn't return stream list
            if (sources.isEmpty()) {
                val embedUrl = if (mediaType == MediaType.MOVIE) {
                    "$baseUrl/embed/movie/$id"
                } else {
                    val s = season ?: 1
                    val e = episode ?: 1
                    "$baseUrl/embed/tv/$id/$s/$e"
                }
                if (StreamProviderHelper.isValidHttpsUrl(embedUrl)) {
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
            }
        } catch (_: Exception) {
            // Graceful degradation
        }
        sources
    }
}
