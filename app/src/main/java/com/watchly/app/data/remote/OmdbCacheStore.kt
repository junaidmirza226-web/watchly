/*
 * Watchly — the SharedPreferences-backed OMDb cache (cycle 4).
 *
 * Kept apart from OmdbApi.kt so the API + parser stay free of
 * Android imports (the pure-JVM test harness compiles them without
 * the Android SDK). Entries are stored as one compact line — the
 * fields never contain the separator, and ratings move slowly, so
 * a plain encoding beats pulling a serializer in.
 */
package com.watchly.app.data.remote

import android.content.Context
import com.watchly.app.data.model.OmdbRatings

class SharedPreferencesOmdbCache(context: Context) : OmdbCache {

    private val prefs = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    override fun get(imdbId: String): Pair<OmdbRatings, Long>? {
        val raw = prefs.getString(keyFor(imdbId), null) ?: return null
        val parts = raw.split(SEPARATOR)
        if (parts.size != FIELD_COUNT) return null
        val fetchedAt = parts[0].toLongOrNull() ?: return null
        return OmdbRatings(
            imdbRating = parts[1].toDoubleOrNull(),
            imdbVotes = parts[2].ifBlank { null },
            rottenTomatoesPercent = parts[3].toIntOrNull(),
            metacriticScore = parts[4].toIntOrNull(),
            rated = parts[5].ifBlank { null }
        ) to fetchedAt
    }

    override fun put(imdbId: String, ratings: OmdbRatings, fetchedAtMs: Long) {
        val raw = listOf(
            fetchedAtMs.toString(),
            ratings.imdbRating?.toString().orEmpty(),
            ratings.imdbVotes.orEmpty(),
            ratings.rottenTomatoesPercent?.toString().orEmpty(),
            ratings.metacriticScore?.toString().orEmpty(),
            ratings.rated.orEmpty()
        ).joinToString(SEPARATOR)
        prefs.edit().putString(keyFor(imdbId), raw).apply()
    }

    private fun keyFor(imdbId: String) = "omdb_$imdbId"

    private companion object {
        const val PREFS_NAME = "watchly_omdb_cache"
        const val SEPARATOR = "\u001F"
        const val FIELD_COUNT = 6
    }
}
