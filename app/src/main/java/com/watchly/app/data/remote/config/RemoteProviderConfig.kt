package com.watchly.app.data.remote.config

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Manages streaming provider configuration:
 * 1. Fetches updated providers.json from remote GitHub URL.
 * 2. Caches the last successful config in SharedPreferences.
 * 3. Falls back to a bundled offline default when offline or unreachable.
 */
object RemoteProviderConfig {

    private const val PREFS_NAME = "watchly_remote_providers"
    private const val KEY_CONFIG_JSON = "cached_providers_json"
    private const val KEY_LAST_FETCH_TIME = "last_fetch_timestamp"

    /** Configurable remote endpoint (default: Watchly GitHub repository). */
    var remoteConfigUrl: String =
        "https://raw.githubusercontent.com/junaidmirza226-web/watchly/main/providers.json"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    @Volatile
    private var memoryCachedProviders: List<ProviderEntry>? = null

    /** Dynamically updated TMDB API key from cloud config. */
    @Volatile
    var tmdbApiKey: String? = null

    /**
     * Bundled fallback provider list embedded directly in the app.
     * Guarantees Watchly can stream immediately without waiting for network.
     */
    val DEFAULT_PROVIDERS: List<ProviderEntry> = listOf(
        ProviderEntry("VidSrc.to", "https://vidsrc.to", enabled = true, priority = 1, type = ProviderType.EMBED, requiresReferer = true),
        ProviderEntry("VidSrc.pro", "https://vidsrc.pro", enabled = true, priority = 2, type = ProviderType.EMBED, requiresReferer = true),
        ProviderEntry("VidSrc.cc", "https://vidsrc.cc", enabled = true, priority = 3, type = ProviderType.EMBED, requiresReferer = true),
        ProviderEntry("Embed.su", "https://embed.su", enabled = true, priority = 4, type = ProviderType.EMBED, requiresReferer = true),
        ProviderEntry("2Embed", "https://www.2embed.cc", enabled = true, priority = 5, type = ProviderType.EMBED, requiresReferer = true),
        ProviderEntry("AutoEmbed", "https://player.autoembed.cc", enabled = true, priority = 6, type = ProviderType.EMBED, requiresReferer = false),
        ProviderEntry("VidLink", "https://vidlink.pro", enabled = true, priority = 7, type = ProviderType.EMBED, requiresReferer = true),
        ProviderEntry("MoviesAPI", "https://moviesapi.club", enabled = true, priority = 8, type = ProviderType.EMBED, requiresReferer = true),
        ProviderEntry("SuperStream", "https://superstream.tech", enabled = true, priority = 9, type = ProviderType.API, requiresReferer = false),
        ProviderEntry("FlixHQ", "https://flixhq.to", enabled = true, priority = 10, type = ProviderType.SCRAPER, requiresReferer = false),
        ProviderEntry("VidBinge", "https://vidbinge.com", enabled = true, priority = 11, type = ProviderType.EMBED, requiresReferer = true),
        ProviderEntry("SmashyStream", "https://embed.smashystream.com", enabled = true, priority = 12, type = ProviderType.EMBED, requiresReferer = true),
        ProviderEntry("RiveStream", "https://rivestream.live", enabled = true, priority = 13, type = ProviderType.EMBED, requiresReferer = false),
        ProviderEntry("NontonGo", "https://www.nontongo.win", enabled = true, priority = 14, type = ProviderType.EMBED, requiresReferer = false),
        ProviderEntry("MultiEmbed", "https://multiembed.mov", enabled = true, priority = 15, type = ProviderType.EMBED, requiresReferer = true),
        ProviderEntry("VidCloud", "https://vidcloud9.com", enabled = true, priority = 16, type = ProviderType.EMBED, requiresReferer = true),
        ProviderEntry("Autoembed", "https://autoembed.co", enabled = true, priority = 17, type = ProviderType.EMBED, requiresReferer = false)
    )

    /**
     * Gets the currently active providers synchronously.
     * Order: Memory Cache -> Local Storage Cache -> Bundled Defaults.
     */
    fun getProviders(context: Context? = null): List<ProviderEntry> {
        memoryCachedProviders?.let { return it }

        if (context != null) {
            val cached = loadFromPreferences(context)
            if (!cached.isNullOrEmpty()) {
                memoryCachedProviders = cached
                return cached
            }
        }

        memoryCachedProviders = DEFAULT_PROVIDERS
        return DEFAULT_PROVIDERS
    }

    /**
     * Gets only enabled providers, sorted by priority.
     */
    fun getEnabledProviders(context: Context? = null): List<ProviderEntry> {
        return getProviders(context)
            .filter { it.enabled }
            .sortedBy { it.priority }
    }

    /**
     * Asynchronously fetches the latest providers configuration from GitHub.
     * On success, updates memory cache and persists to SharedPreferences.
     * On failure, silently falls back to existing cached or bundled list.
     */
    suspend fun fetchRemoteConfig(context: Context? = null): List<ProviderEntry> =
        withContext(Dispatchers.IO) {
            try {
                if (!remoteConfigUrl.startsWith("https://", ignoreCase = true)) {
                    return@withContext getProviders(context)
                }

                val request = Request.Builder()
                    .url(remoteConfigUrl)
                    .header("User-Agent", "WatchlyApp/1.4.2 (Android)")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext getProviders(context)
                    }

                    val responseBody = response.body?.string() ?: return@withContext getProviders(context)
                    val parsed = parseJson(responseBody)
                    if (parsed.isNotEmpty()) {
                        memoryCachedProviders = parsed
                        if (context != null) {
                            saveToPreferences(context, responseBody)
                        }
                        return@withContext parsed
                    }
                }
            } catch (_: Exception) {
                // Graceful fallback: network error, timeout, or DNS issue
            }

            getProviders(context)
        }

    /**
     * Parses a JSON string array into a list of [ProviderEntry].
     */
    fun parseJson(jsonString: String): List<ProviderEntry> {
        return try {
            val trimmed = jsonString.trim()
            val array = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val obj = org.json.JSONObject(trimmed)
                val key = if (obj.has("tmdbKey")) obj.optString("tmdbKey") else obj.optString("tmdb_api_key")
                if (!key.isNullOrBlank()) {
                    tmdbApiKey = key
                }
                obj.optJSONArray("providers") ?: JSONArray()
            }
            val list = mutableListOf<ProviderEntry>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val entry = ProviderEntry.fromJson(obj)
                if (entry.baseUrl.isNotBlank() && entry.baseUrl.startsWith("https://", ignoreCase = true)) {
                    list.add(entry)
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun loadFromPreferences(context: Context): List<ProviderEntry>? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_CONFIG_JSON, null) ?: return null
            val parsed = parseJson(json)
            parsed.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveToPreferences(context: Context, json: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_CONFIG_JSON, json)
                .putLong(KEY_LAST_FETCH_TIME, System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {
            // Ignore storage errors
        }
    }
}
