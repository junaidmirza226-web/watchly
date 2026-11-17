/*
 * Watchly — HLS (.m3u8) download support for the segmented engine.
 *
 * The headless resolver captures the real stream URL out of player
 * pages; when that stream is HLS (the common case), the "file" is a
 * playlist of segments. This module turns a playlist into the bytes
 * of one local file, exactly what MovieBox-class downloaders do:
 *
 *   master.m3u8  -> pick the highest-bandwidth variant
 *   variant.m3u8 -> ordered segments (+ optional fMP4 init segment)
 *   AES-128      -> decrypt each segment with the playlist's key
 *
 * All parsing and crypto are pure functions over strings/bytes so the
 * whole path is unit-tested on the JVM with an in-memory fake server.
 */
package com.watchly.app.data.downloads

import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object HlsSupport {

    /** True for playlist URLs (the entry point of an HLS stream). */
    fun isHlsUrl(url: String): Boolean = url.lowercase().contains(".m3u8")

    /** A quality variant from a master playlist (best first after sort). */
    data class Variant(val uri: String, val bandwidth: Long)

    /** A parsed media playlist ready for sequential download. */
    data class MediaPlaylist(
        /** Segment URIs in play order, absolute. */
        val segments: List<String>,
        /** AES-128 key URI (absolute), or null when the playlist is clear. */
        val keyUri: String?,
        /** Explicit IV (16 bytes) from the KEY tag, or null (use sequence). */
        val iv: ByteArray?,
        /** fMP4 init segment URI (#EXT-X-MAP), or null for TS segments. */
        val initUri: String?,
        /** #EXT-X-MEDIA-SEQUENCE value (IV fallback base). */
        val mediaSequence: Long
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /** Highest-bandwidth variants first. Empty for media-only playlists. */
    fun parseMaster(text: String, baseUrl: String): List<Variant> {
        val variants = mutableListOf<Variant>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val bandwidth = Regex("BANDWIDTH=(\\d+)")
                    .find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                var j = i + 1
                while (j < lines.size &&
                    (lines[j].isBlank() || lines[j].trim().startsWith("#"))
                ) {
                    j++
                }
                if (j < lines.size) {
                    variants.add(Variant(resolveUrl(baseUrl, lines[j].trim()), bandwidth))
                }
                i = j
            }
            i++
        }
        return variants.sortedByDescending { it.bandwidth }
    }

    /**
     * Parses a media playlist. Throws [IllegalArgumentException] on
     * SAMPLE-AES (not supported) so callers fail honestly instead of
     * downloading encrypted garbage.
     */
    fun parseMedia(text: String, baseUrl: String): MediaPlaylist {
        val segments = mutableListOf<String>()
        var keyUri: String? = null
        var keyIv: ByteArray? = null
        var initUri: String? = null
        var mediaSequence = 0L

        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    mediaSequence = line.substringAfter(":").trim().toLongOrNull() ?: 0L

                line.startsWith("#EXT-X-KEY:") -> {
                    val method = attr(line, "METHOD")
                    when (method) {
                        "NONE" -> {
                            keyUri = null
                            keyIv = null
                        }
                        "AES-128" -> {
                            keyUri = attr(line, "URI")?.let { resolveUrl(baseUrl, it) }
                            keyIv = attr(line, "IV")?.let { parseHexIv(it) }
                        }
                        else -> throw IllegalArgumentException(
                            "Unsupported HLS encryption: $method"
                        )
                    }
                }

                line.startsWith("#EXT-X-MAP:") ->
                    initUri = attr(line, "URI")?.let { resolveUrl(baseUrl, it) }

                line.isNotEmpty() && !line.startsWith("#") ->
                    segments.add(resolveUrl(baseUrl, line))
            }
        }
        return MediaPlaylist(segments, keyUri, keyIv, initUri, mediaSequence)
    }

    /** Decrypts one AES-128-CBC segment (PKCS#7), as HLS mandates. */
    fun decryptAes128(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv)
        )
        return cipher.doFinal(data)
    }

    /** The IV for segment [index]: explicit key IV, else the sequence number. */
    fun ivFor(playlist: MediaPlaylist, index: Int): ByteArray {
        playlist.iv?.let { return it }
        val iv = ByteArray(16)
        val sequence = playlist.mediaSequence + index
        // Last 8 bytes = big-endian media sequence number (RFC 8216 §5.2).
        for (i in 0 until 8) {
            iv[15 - i] = (sequence ushr (8 * i)).toByte()
        }
        return iv
    }

    /** Absolute-izes [ref] against [base]; data/blob refs pass through. */
    fun resolveUrl(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://") || ref.startsWith("data:")) {
            return ref
        }
        return runCatching { URI(base).resolve(ref).toString() }.getOrDefault(ref)
    }

    /** Extracts `NAME="value"` (quoted) or `NAME=value` from a tag line. */
    private fun attr(line: String, name: String): String? {
        val regex = Regex("$name=(\"([^\"]*)\"|[^,]*)")
        val match = regex.find(line) ?: return null
        val quoted = match.groupValues[2]
        return if (quoted.isNotEmpty()) quoted else match.groupValues[1].ifBlank { null }
    }

    /** Parses `0x…`/plain hex IV text into exactly 16 bytes. */
    fun parseHexIv(text: String): ByteArray? {
        val hex = text.trim().removePrefix("0x").removePrefix("0X")
        if (hex.length != 32) return null
        val bytes = ByteArray(16)
        for (i in 0 until 16) {
            bytes[i] = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return null
        }
        return bytes
    }
}
