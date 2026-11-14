package com.watchly.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Information about an available application update.
 */
data class AppUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val releaseNotes: String? = null,
    val downloadUrl: String,
    val releaseName: String? = null
)

/**
 * Checks GitHub Releases API for app updates and emits events when a new version is available.
 */
object AppUpdateChecker {

    private const val GITHUB_RELEASES_API_URL =
        "https://api.github.com/repos/junaidmirza226-web/watchly/releases/latest"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val _updateState = MutableStateFlow<AppUpdateInfo?>(null)
    val updateState: StateFlow<AppUpdateInfo?> = _updateState.asStateFlow()

    /**
     * Checks if a newer release is published on GitHub.
     * If newer, updates [updateState] and returns the [AppUpdateInfo].
     */
    suspend fun checkForUpdates(
        currentVersion: String = getCurrentVersion(),
        apiUrl: String = GITHUB_RELEASES_API_URL
    ): AppUpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "WatchlyApp/$currentVersion (Android)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)

                val tagName = json.optString("tag_name", "").removePrefix("v").trim()
                if (tagName.isBlank()) return@withContext null

                if (isNewerVersion(current = currentVersion, remote = tagName)) {
                    val name = json.optString("name", "Version $tagName")
                    val bodyNotes = json.optString("body", "")
                    val htmlUrl = json.optString("html_url", "")

                    // Look for .apk asset download URL first, fallback to release HTML URL
                    var downloadUrl = htmlUrl
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.optJSONObject(i) ?: continue
                            val assetName = asset.optString("name", "")
                            if (assetName.endsWith(".apk", ignoreCase = true)) {
                                downloadUrl = asset.optString("browser_download_url", htmlUrl)
                                break
                            }
                        }
                    }

                    val info = AppUpdateInfo(
                        currentVersion = currentVersion,
                        latestVersion = tagName,
                        releaseNotes = bodyNotes.ifBlank { null },
                        downloadUrl = downloadUrl,
                        releaseName = name.ifBlank { null }
                    )
                    _updateState.value = info
                    return@withContext info
                }
            }
        } catch (_: Exception) {
            // Never crash on network/JSON errors
        }
        null
    }

    /**
     * Compares semantic versions (e.g. "1.4.2" vs "1.5.0" or "1.4.10").
     * Returns true if [remote] is strictly newer than [current].
     */
    fun isNewerVersion(current: String, remote: String): Boolean {
        val currParts = current.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val remoteParts = remote.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(currParts.size, remoteParts.size)
        for (i in 0 until maxLen) {
            val c = currParts.getOrElse(i) { 0 }
            val r = remoteParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    fun triggerInstall(context: android.content.Context, downloadUrl: String) {
        runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse(downloadUrl)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private fun getCurrentVersion(): String {
        return try {
            val clazz = Class.forName("com.watchly.app.BuildConfig")
            clazz.getField("VERSION_NAME").get(null) as? String ?: "1.4.8"
        } catch (_: Throwable) {
            "1.4.8"
        }
    }
}
