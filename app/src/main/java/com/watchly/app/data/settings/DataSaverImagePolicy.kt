/*
 * Watchly — Data Saver image downscaling (cycle 10, spec §14.1).
 *
 * Pure URL policy: given an image URL, return the smaller variant
 * the same provider serves, or the URL unchanged when the provider
 * has no derivable smaller variant. Used by the Coil interceptor in
 * WatchlyApplication, so the saving is real — the smaller file is
 * what actually crosses the network and fills the cache.
 *
 * Providers covered (only where the smaller URL is guaranteed by
 * the provider's own URL scheme — nothing is guessed):
 *  - TMDB: the size is a path segment (…/t/p/w500/…). w780 and
 *    w500 posters/backdrops drop to w342; "original" drops to
 *    w500. Sizes at or below w342 (w185 cast photos, w92 logos)
 *    are already small and pass through untouched.
 *  - AniList: cover URLs carry the size in the path
 *    (…/cover/large/…); the same file is served under
 *    …/cover/medium/…, which is what the API's own "medium"
 *    field returns.
 *
 * TVmaze posters are NOT rewritten: the API hands out separate
 * original/medium URLs with no derivable relationship, and
 * inventing one could 404 — those posters load as-is.
 */
package com.watchly.app.data.settings

object DataSaverImagePolicy {

    /** TMDB size segment → its Data Saver replacement. */
    private val TMDB_DOWNSCALES = mapOf(
        "w780" to "w342",
        "w500" to "w342",
        "original" to "w500"
    )

    private const val TMDB_MARKER = "image.tmdb.org/t/p/"

    /** The Data Saver variant of [url] (or [url] itself). */
    fun smallerUrl(url: String): String {
        tmdbSmaller(url)?.let { return it }
        if (url.contains("/cover/large/")) {
            return url.replace("/cover/large/", "/cover/medium/")
        }
        return url
    }

    private fun tmdbSmaller(url: String): String? {
        val markerAt = url.indexOf(TMDB_MARKER)
        if (markerAt < 0) return null
        val sizeStart = markerAt + TMDB_MARKER.length
        val sizeEnd = url.indexOf('/', sizeStart)
        if (sizeEnd < 0) return null
        val replacement = TMDB_DOWNSCALES[url.substring(sizeStart, sizeEnd)]
            ?: return null
        return url.substring(0, sizeStart) + replacement +
            url.substring(sizeEnd)
    }
}
