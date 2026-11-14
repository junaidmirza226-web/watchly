package com.watchly.app.data.model

import java.util.concurrent.ConcurrentHashMap

/**
 * Registry mapping media stream URLs to HTTP request headers (e.g. Referer, User-Agent)
 * required by CDNs and embed stream hosts.
 */
object PlayableMediaHeaders {
    private val headersMap = ConcurrentHashMap<String, Map<String, String>>()

    fun register(url: String, headers: Map<String, String>) {
        if (headers.isNotEmpty()) {
            headersMap[url] = headers
        }
    }

    fun get(url: String): Map<String, String> = headersMap[url] ?: emptyMap()

    fun remove(url: String) {
        headersMap.remove(url)
    }
}

/**
 * One playable / downloadable file for a title (Internet Archive, streaming providers, or local files).
 *
 * @param url          Direct file or stream URL.
 * @param fileName     Display name or file name as stored on the source.
 * @param format       Container/codec label, e.g. "HLS", "MP4", "h.264".
 * @param sizeBytes    File size in bytes, when known.
 * @param qualityLabel Human quality label when derivable, e.g. "1080p • VidSrc".
 * @param headers      CDN authentication headers (Referer, User-Agent, Origin).
 */
data class PlayableFile(
    val url: String,
    val fileName: String,
    val format: String? = null,
    val sizeBytes: Long? = null,
    val qualityLabel: String? = null,
    val headers: Map<String, String> = emptyMap()
) {
    init {
        if (headers.isNotEmpty()) {
            PlayableMediaHeaders.register(url, headers)
        }
    }

    /** "596 MB" style size label, or null when the size is unknown. */
    val sizeLabel: String?
        get() = sizeBytes?.let {
            when {
                it >= 1_000_000_000 -> "%.1f GB".format(it / 1_000_000_000.0)
                it >= 1_000_000 -> "%d MB".format(it / 1_000_000)
                it >= 1_000 -> "%d KB".format(it / 1_000)
                else -> "$it B"
            }
        }
}
