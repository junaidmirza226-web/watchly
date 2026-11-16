package com.watchly.app.data.remote.providers

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * Shared utility for StreamProvider implementations:
 * - 10-second timeout OkHttp client
 * - Rotating User-Agent pool to avoid fingerprinting
 * - Regex stream extractors for HLS (.m3u8) and MP4
 * - HTTPS validation
 */
object StreamProviderHelper {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    )

    private val uaIndex = AtomicInteger(0)

    /** Returns the next rotating User-Agent string. */
    fun nextUserAgent(): String {
        val idx = Math.abs(uaIndex.getAndIncrement() % userAgents.size)
        return userAgents[idx]
    }

    /**
     * Executes a GET request with safe headers and returns body string, or null on error.
     */
    fun fetchString(url: String, referer: String? = null, customHeaders: Map<String, String> = emptyMap()): String? {
        if (!isValidHttpsUrl(url)) return null
        return try {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", customHeaders["User-Agent"] ?: nextUserAgent())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")

            referer?.let { builder.header("Referer", it) }
            customHeaders.forEach { (k, v) ->
                if (!k.equals("User-Agent", ignoreCase = true) && !k.equals("Referer", ignoreCase = true)) {
                    builder.header(k, v)
                }
            }

            client.newCall(builder.build()).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Validates that a URL is well-formed, uses HTTPS, and has a valid host.
     */
    fun isValidHttpsUrl(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return false
        return try {
            val uri = java.net.URI(url)
            val host = uri.host
            !host.isNullOrBlank() && host.contains(".")
        } catch (_: Exception) {
            false
        }
    }

    private val m3u8Regex = Pattern.compile("https?://[^\"'\\s]+\\.m3u8[^\"'\\s]*", Pattern.CASE_INSENSITIVE)
    private val mp4Regex = Pattern.compile("https?://[^\"'\\s]+\\.mp4[^\"'\\s]*", Pattern.CASE_INSENSITIVE)
    private val iframeRegex = Pattern.compile("<iframe[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)

    /** Extracts .m3u8 URLs found in raw HTML/JS text. */
    fun extractM3u8Urls(text: String): List<String> {
        val results = mutableListOf<String>()
        val matcher = m3u8Regex.matcher(text)
        while (matcher.find()) {
            val url = matcher.group().replace("\\/", "/")
            if (isValidHttpsUrl(url) && !results.contains(url)) {
                results.add(url)
            }
        }
        return results
    }

    /** Extracts .mp4 URLs found in raw HTML/JS text. */
    fun extractMp4Urls(text: String): List<String> {
        val results = mutableListOf<String>()
        val matcher = mp4Regex.matcher(text)
        while (matcher.find()) {
            val url = matcher.group().replace("\\/", "/")
            if (isValidHttpsUrl(url) && !results.contains(url)) {
                results.add(url)
            }
        }
        return results
    }

    /** Extracts iframe source URLs found in HTML. */
    fun extractIframeSrcs(html: String): List<String> {
        val results = mutableListOf<String>()
        val matcher = iframeRegex.matcher(html)
        while (matcher.find()) {
            val src = matcher.group(1).replace("\\/", "/")
            if (src.isNotBlank() && !results.contains(src)) {
                results.add(src)
            }
        }
        return results
    }

    /**
     * Detects the dubbed-audio variant a stream belongs to from any
     * label/URL text the provider supplied ("hindi", "dual audio", …).
     * Null = default/original audio. A dual/multi-audio marker wins over
     * a single language name (it describes the file's actual contents),
     * and markers never match inside unrelated words ("gradual").
     */
    fun detectAudioLanguage(text: String): String? {
        val lower = text.lowercase()
        for ((marker, label) in AUDIO_LANGUAGE_MARKERS) {
            if (lower.contains(marker)) return label
        }
        return null
    }

    private val AUDIO_LANGUAGE_MARKERS = listOf(
        "dual audio" to "Dual Audio",
        "multi audio" to "Dual Audio",
        "dual-" to "Dual Audio",
        "-dual" to "Dual Audio",
        "dual_" to "Dual Audio",
        "_dual" to "Dual Audio",
        "malayalam" to "Malayalam",
        "kannada" to "Kannada",
        "punjabi" to "Punjabi",
        "bengali" to "Bengali",
        "telugu" to "Telugu",
        "tamil" to "Tamil",
        "hindi" to "Hindi",
        "urdu" to "Urdu",
        "dubbed" to "Dubbed"
    )
}
