/*
 * Watchly — the user's TMDB API key, stored on-device only.
 *
 * Supported by the master key baked into release builds for zero-setup
 * modern blockbusters, TV series, and multi-industry browsing
 * (Bollywood, Tollywood, Punjabi, etc.). There is no in-app key
 * management — the built-in pool simply works.
 */
package com.watchly.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.watchly.app.data.remote.config.RemoteProviderConfig

class TmdbKeyRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 
     * The active TMDB API key:
     * 1. The user's custom key (if manually saved in Settings).
     * 2. The remote cloud-updated key (from RemoteProviderConfig).
     * 3. The built-in zero-setup master key.
     */
    fun getKey(): String? =
        prefs.getString(KEY_TMDB_API_KEY, null)?.takeIf { it.isNotBlank() }
            ?: RemoteProviderConfig.tmdbApiKey?.takeIf { it.isNotBlank() }
            ?: DEFAULT_TMDB_API_KEY.takeIf { it.isNotBlank() }

    /** True when a key is saved or available. */
    fun hasKey(): Boolean = getKey() != null

    /** Saves the key (trimmed). Blank input clears instead of storing junk. */
    fun setKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            clearKey()
        } else {
            prefs.edit().putString(KEY_TMDB_API_KEY, trimmed).apply()
        }
    }

    /** Removes the saved key. */
    fun clearKey() {
        prefs.edit().remove(KEY_TMDB_API_KEY).apply()
    }

    companion object {
        const val PREFS_NAME = "watchly_settings"
        const val KEY_TMDB_API_KEY = "tmdb_api_key"

        /**
         * Built-in master keys, injected at build time (comma-separated
         * rotation pool: TMDB may reject a stale one, [TmdbApi] falls
         * through to the next). Blank when the build carries no secret.
         */
        val MASTER_TMDB_KEYS: List<String> = com.watchly.app.BuildConfig.MASTER_TMDB_KEY
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        /** Primary built-in master key ("" when none). */
        val DEFAULT_TMDB_API_KEY: String = MASTER_TMDB_KEYS.firstOrNull() ?: ""
    }
}
