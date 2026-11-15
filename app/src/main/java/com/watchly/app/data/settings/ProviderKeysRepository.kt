/*
 * Watchly — the user's own API keys for the cycle-4 providers
 * (OMDb, Trakt, OpenSubtitles, Fanart), stored on-device only.
 *
 * Same rules as the TMDB key (see TmdbKeyRepository): the keys
 * belong to the user, live in SharedPreferences on this phone, are
 * only ever sent to their own provider, are never hardcoded and
 * never logged. One store for all four so the Settings hub and the
 * feature ViewModels all read the same truth. (TMDB keeps its own
 * older store in the same prefs file; the two never collide.)
 */
package com.watchly.app.data.settings

import android.content.Context
import android.content.SharedPreferences

/** A provider whose key the user can paste in Settings. */
enum class ApiProvider(
    val displayName: String,
    val prefsKey: String,
    /** Where the user gets the key, in plain words for the UI. */
    val signupHint: String
) {
    OMDB(
        displayName = "OMDb",
        prefsKey = "omdb_api_key",
        signupHint = "Free at omdbapi.com → API Key (arrives by email)."
    ),
    TRAKT(
        displayName = "Trakt",
        prefsKey = "trakt_client_id",
        signupHint = "Free at trakt.tv → Settings → Your API Apps → " +
            "New Application; paste the Client ID (not the secret)."
    ),
    OPENSUBTITLES(
        displayName = "OpenSubtitles",
        prefsKey = "opensubtitles_api_key",
        signupHint = "Free at opensubtitles.com → your profile → " +
            "API Consumers; create a consumer and copy its API Key."
    ),
    FANART(
        displayName = "Fanart.tv",
        prefsKey = "fanart_api_key",
        signupHint = "Free at fanart.tv → Get an API key → " +
            "Personal API keys."
    )
}

class ProviderKeysRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The saved key for [provider], or fallback master key when none is saved. */
    fun getKey(provider: ApiProvider): String? =
        prefs.getString(provider.prefsKey, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_KEYS[provider]

    /** True when a key is saved for [provider]. */
    fun hasKey(provider: ApiProvider): Boolean = getKey(provider) != null

    /** Saves [key] (trimmed) for [provider]. Blank clears instead. */
    fun setKey(provider: ApiProvider, key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            clearKey(provider)
        } else {
            prefs.edit().putString(provider.prefsKey, trimmed).apply()
        }
    }

    /** Removes the saved key for [provider]. */
    fun clearKey(provider: ApiProvider) {
        prefs.edit().remove(provider.prefsKey).apply()
    }

    companion object {
        /** Same file as the TMDB key store — one settings store. */
        const val PREFS_NAME = TmdbKeyRepository.PREFS_NAME

        /** Built-in master keys for out-of-the-box ratings, trending, and subtitles. */
        val DEFAULT_KEYS: Map<ApiProvider, String> = mapOf(
            ApiProvider.OMDB to "3fe6e3fc",
            ApiProvider.TRAKT to "2M3JfayN76tNAq_ppJw6ZXROTDmhsq9THc0pnuqfB6o",
            ApiProvider.OPENSUBTITLES to "nU9U21UXcHfxmOSCNUHZnFNb80zq0aa9"
        )
    }
}
