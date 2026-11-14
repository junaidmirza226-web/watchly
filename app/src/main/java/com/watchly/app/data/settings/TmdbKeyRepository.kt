/*
 * Watchly — the user's own TMDB API key, stored on-device only.
 *
 * The key is the user's personal free TMDB key (they create it in the
 * guided signup round). It lives in SharedPreferences on this phone
 * and is only ever sent to api.themoviedb.org as part of TMDB
 * requests. It is NEVER hardcoded in the app, never logged, and never
 * sent anywhere else. When the app scales, keys move behind a backend
 * (see GOAL.md) — this store is the v1 stand-in.
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
     * 3. The built-in zero-setup default key.
     */
    fun getKey(): String? =
        prefs.getString(KEY_TMDB_API_KEY, null)?.takeIf { it.isNotBlank() }
            ?: RemoteProviderConfig.tmdbApiKey?.takeIf { it.isNotBlank() }
            ?: "3b6bdb61d7ea586e9b15d15aad74a0e0"

    /** True when a key is saved or available — TMDB features switch on automatically. */
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

    /** Removes the saved key. TMDB features switch off gracefully. */
    fun clearKey() {
        prefs.edit().remove(KEY_TMDB_API_KEY).apply()
    }

    companion object {
        const val PREFS_NAME = "watchly_settings"
        const val KEY_TMDB_API_KEY = "tmdb_api_key"
    }
}
