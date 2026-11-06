/*
 * Watchly — local watchlist + watched state (spec §9.1, §4.2, v1 slice).
 *
 * Stored in SharedPreferences as JSON arrays of small title snapshots
 * (stable key, name, poster, year, type) — enough to render the
 * Watchlist screen later without re-fetching. No PINs, tokens or
 * passwords ever live here (see spec §15.2). Per-profile separation
 * arrives with Profiles (§8); v1 has a single implicit profile, so one
 * store is enough for now.
 *
 * Pure storage logic: no network, works fully offline.
 */
package com.watchly.app.data.library

import android.content.Context
import android.content.SharedPreferences
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import org.json.JSONArray
import org.json.JSONObject

class WatchlistRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // Watchlist
    // ------------------------------------------------------------------

    /** True when the title is on the watchlist. */
    fun isInWatchlist(title: Title): Boolean =
        readKeys(KEY_WATCHLIST).contains(title.stableKey)

    /**
     * Adds the title if absent, removes it if present. Returns true when
     * the title is on the watchlist afterwards.
     */
    fun toggleWatchlist(title: Title): Boolean {
        val entries = readEntries(KEY_WATCHLIST).toMutableList()
        val key = title.stableKey
        val existing = entries.indexOfFirst { it.stableKey == key }
        return if (existing >= 0) {
            entries.removeAt(existing)
            writeEntries(KEY_WATCHLIST, entries)
            false
        } else {
            entries.add(StoredTitle.from(title))
            writeEntries(KEY_WATCHLIST, entries)
            true
        }
    }

    /** Watchlist titles, most recently added first. */
    fun getWatchlist(): List<Title> =
        readEntries(KEY_WATCHLIST).reversed().mapNotNull { it.toTitle() }

    // ------------------------------------------------------------------
    // Watched state
    // ------------------------------------------------------------------

    /** True when the title is marked watched. */
    fun isWatched(title: Title): Boolean =
        readKeys(KEY_WATCHED).contains(title.stableKey)

    /** Marks the title watched (or un-watches it). */
    fun setWatched(title: Title, watched: Boolean) {
        val entries = readEntries(KEY_WATCHED).toMutableList()
        val key = title.stableKey
        val existing = entries.indexOfFirst { it.stableKey == key }
        if (watched && existing < 0) {
            entries.add(StoredTitle.from(title))
            writeEntries(KEY_WATCHED, entries)
        } else if (!watched && existing >= 0) {
            entries.removeAt(existing)
            writeEntries(KEY_WATCHED, entries)
        }
    }

    // ------------------------------------------------------------------
    // Per-episode watched state (stored as "stableKey:S1E2" keys)
    // ------------------------------------------------------------------

    /** True when this episode is marked watched. */
    fun isEpisodeWatched(title: Title, season: Int, number: Int): Boolean =
        readStringSet(KEY_WATCHED_EPISODES)
            .contains(episodeKey(title, season, number))

    /** Toggles one episode's watched state; returns the new state. */
    fun toggleEpisodeWatched(title: Title, season: Int, number: Int): Boolean {
        val keys = readStringSet(KEY_WATCHED_EPISODES).toMutableSet()
        val key = episodeKey(title, season, number)
        val nowWatched = if (keys.contains(key)) {
            keys.remove(key); false
        } else {
            keys.add(key); true
        }
        prefs.edit().putStringSet(KEY_WATCHED_EPISODES, keys).apply()
        return nowWatched
    }

    private fun episodeKey(title: Title, season: Int, number: Int): String =
        "${title.stableKey}:S${season}E$number"

    private fun readStringSet(key: String): Set<String> =
        prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    private fun readEntries(key: String): List<StoredTitle> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        val out = ArrayList<StoredTitle>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val stableKey = obj.optString("key").takeIf { it.isNotBlank() } ?: continue
            out.add(
                StoredTitle(
                    stableKey = stableKey,
                    name = obj.optString("name").takeIf { it.isNotBlank() } ?: continue,
                    posterUrl = obj.optString("poster").takeIf { it.isNotBlank() },
                    year = obj.optString("year").takeIf { it.isNotBlank() },
                    mediaType = runCatching {
                        MediaType.valueOf(obj.optString("type"))
                    }.getOrElse { MediaType.MOVIE }
                )
            )
        }
        return out
    }

    private fun readKeys(key: String): Set<String> =
        readEntries(key).map { it.stableKey }.toSet()

    private fun writeEntries(key: String, entries: List<StoredTitle>) {
        val array = JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject()
                    .put("key", entry.stableKey)
                    .put("name", entry.name)
                    .put("poster", entry.posterUrl ?: JSONObject.NULL)
                    .put("year", entry.year ?: JSONObject.NULL)
                    .put("type", entry.mediaType.name)
            )
        }
        prefs.edit().putString(key, array.toString()).apply()
    }

    /** Minimal snapshot of a title, enough to render a list row offline. */
    private data class StoredTitle(
        val stableKey: String,
        val name: String,
        val posterUrl: String?,
        val year: String?,
        val mediaType: MediaType
    ) {
        companion object {
            fun from(title: Title) = StoredTitle(
                stableKey = title.stableKey,
                name = title.name,
                posterUrl = title.posterUrl,
                year = title.year,
                mediaType = title.mediaType
            )
        }

        /** Rebuilds a displayable Title. Provider ids are recoverable
         * from the stable key for the common single-provider cases. */
        fun toTitle(): Title? {
            val parts = stableKey.split(":")
            val base = Title(
                name = name,
                posterUrl = posterUrl,
                mediaType = mediaType,
                year = year
            )
            if (parts.size != 2) return base
            return when (parts[0]) {
                "tvmaze" -> base.copy(tvmazeId = parts[1].toIntOrNull())
                "anilist" -> base.copy(anilistId = parts[1].toIntOrNull())
                "archive" -> base.copy(archiveId = parts[1])
                "imdb" -> base.copy(imdbId = parts[1])
                "tmdb" -> base.copy(tmdbId = parts[1].toIntOrNull())
                else -> base
            }
        }
    }

    companion object {
        const val PREFS_NAME = "watchly_library"
        const val KEY_WATCHLIST = "watchlist"
        const val KEY_WATCHED = "watched"
        const val KEY_WATCHED_EPISODES = "watched_episodes"
    }
}
