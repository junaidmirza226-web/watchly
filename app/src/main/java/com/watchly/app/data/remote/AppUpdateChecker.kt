package com.watchly.app.data.remote

import android.content.Context
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
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
        context: Context,
        apiUrl: String = GITHUB_RELEASES_API_URL
    ): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val installedVersion = currentVersion(context) ?: return@withContext null
        try {
            val request = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "WatchlyApp/$installedVersion (Android)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)

                val tagName = json.optString("tag_name", "").removePrefix("v").trim()
                if (tagName.isBlank()) return@withContext null

                if (isNewerVersion(current = installedVersion, remote = tagName)) {
                    val name = json.optString("name", "Version $tagName")
                    val bodyNotes = json.optString("body", "")
                    val htmlUrl = json.optString("html_url", "")

                    // APK asset download URL first, release page as fallback.
                    val downloadUrl = pickApkAsset(json) ?: htmlUrl

                    val info = AppUpdateInfo(
                        currentVersion = installedVersion,
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
     * The version of the installed app, read from Android itself.
     *
     * This deliberately does NOT read BuildConfig: release builds run R8 with
     * `-repackageclasses`, which moves the BuildConfig class to another package,
     * so any reference to it either fails to compile or throws at runtime.
     * PackageManager always reports the version that was really installed.
     */
    fun currentVersion(context: Context): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Compares semantic versions (e.g. "1.4.2" vs "1.5.0", "1.4.10",
     * or per-build "1.5.1.37"). Returns true if [remote] is strictly
     * newer than [current].
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

    /** Picks the APK asset download URL from a release JSON (watchly.apk first). */
    fun pickApkAsset(releaseJson: JSONObject): String? {
        val assets = releaseJson.optJSONArray("assets") ?: return null
        var generic: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val url = asset.optString("browser_download_url", "")
            if (url.isBlank()) continue
            if (name.contains("watchly", ignoreCase = true)) return url
            if (generic == null) generic = url
        }
        return generic
    }

    // ------------------------------------------------------------------
    // One-tap update: download the APK, hand it to the system installer.
    // (Sideloaded apps can't install fully silently — the system's
    // "Install" confirmation is the one tap.)
    // ------------------------------------------------------------------

    sealed interface InstallState {
        data object Idle : InstallState
        data class Downloading(val percent: Int? = null) : InstallState
        data object Installing : InstallState
        data class Error(val message: String) : InstallState
    }

    private val _installState = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    private val installScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Downloads [downloadUrl] and launches the system installer. Non-APK
     * URLs (release pages) fall back to opening the browser.
     */
    fun startUpdate(context: Context, downloadUrl: String) {
        if (!downloadUrl.endsWith(".apk", ignoreCase = true)) {
            triggerInstall(context, downloadUrl)
            return
        }
        if (_installState.value is InstallState.Downloading) return
        val appContext = context.applicationContext
        _installState.value = InstallState.Downloading(null)
        installScope.launch {
            try {
                val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
                val target = File(dir, "watchly-update.apk")
                val request = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", "WatchlyApp/${currentVersion(appContext)} (Android)")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IllegalStateException("Empty response")
                    val total = body.contentLength().takeIf { it > 0 }
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var read = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                read += n
                                if (total != null) {
                                    _installState.value =
                                        InstallState.Downloading(((read * 100) / total).toInt())
                                }
                            }
                        }
                    }
                }
                installApk(appContext, target)
            } catch (t: Throwable) {
                _installState.value = InstallState.Error(
                    t.message ?: "Download failed — try again."
                )
            }
        }
    }

    /** Hands [apk] to the system installer (one "Install" tap for the user). */
    fun installApk(context: Context, apk: File) {
        if (android.os.Build.VERSION.SDK_INT >= 26 &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            runCatching {
                val settings = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:${context.packageName}")
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(settings)
            }
            _installState.value = InstallState.Error(
                "Allow installs from Watchly in the screen that opened, then tap Update again."
            )
            return
        }
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            apk
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
        _installState.value = InstallState.Installing
        runCatching { context.startActivity(intent) }.onFailure {
            _installState.value = InstallState.Error("Couldn't open the installer.")
        }
    }

    /** Resets the install status (after a failed attempt is abandoned). */
    fun clearInstallState() {
        _installState.value = InstallState.Idle
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
}
