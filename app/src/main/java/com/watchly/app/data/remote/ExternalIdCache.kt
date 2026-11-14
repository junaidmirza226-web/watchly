/*
 * Watchly — IMDb → TMDB id resolution cache (Phase D).
 *
 * Titles from TVmaze/AniList carry an IMDb id but no TMDB id; the
 * details page resolves them through TMDB's /find route so they
 * can show watch providers and enrichment too. External ids are
 * stable for the life of a title, so resolutions are cached
 * forever (no TTL) — one /find call per title, ever.
 */
package com.watchly.app.data.remote

import android.content.Context

/** Read/write side of the resolution cache (fakeable in tests). */
interface ExternalIdCache {
    /** The cached TMDB id for [imdbId], or null when unresolved. */
    fun tmdbIdFor(imdbId: String): Int?

    /** Remembers a resolution. */
    fun putTmdbId(imdbId: String, tmdbId: Int)
}

/** SharedPreferences-backed [ExternalIdCache]. */
class SharedPreferencesExternalIdCache(context: Context) : ExternalIdCache {

    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun tmdbIdFor(imdbId: String): Int? =
        if (prefs.contains(imdbId)) prefs.getInt(imdbId, 0).takeIf { it > 0 }
        else null

    override fun putTmdbId(imdbId: String, tmdbId: Int) {
        prefs.edit().putInt(imdbId, tmdbId).apply()
    }

    companion object {
        const val PREFS_NAME = "watchly_external_ids"
    }
}
