package com.watchly.app.data.remote

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface AppUpdateInfo {
    data object Checking : AppUpdateInfo
    data object UpToDate : AppUpdateInfo
    data class UpdateAvailable(
        val releaseName: String,
        val version: String,
        val downloadUrl: String
    ) : AppUpdateInfo
}

sealed interface InstallState {
    data object Idle : InstallState
    data object Downloading : InstallState
    data object Installing : InstallState
    data class Error(val message: String) : InstallState
}

object AppUpdateChecker {
    private const val REPO = "junaidmirza226-web/watchly"
    private const val TAG = "AppUpdateChecker"
    private const val MIN_UPDATE_VERSION_CODE = 216L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _updateState = MutableStateFlow<AppUpdateInfo?>(null)
    val updateState: StateFlow<AppUpdateInfo?> = _updateState

    private val _installState = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = _installState

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun checkForUpdates() {
        scope.launch {
            _updateState.value = AppUpdateInfo.Checking
            _updateState.value = runCatching { fetchNewestRelease() }
                .getOrElse { AppUpdateInfo.UpToDate }
        }
    }

    /**
     * One-tap update: always re-check first so a long-lived app process can
     * never hand the installer a stale, outdated download URL.
     */
    fun updateNow(context: Context) {
        scope.launch {
            _updateState.value = AppUpdateInfo.Checking
            val info = runCatching { fetchNewestRelease() }
                .getOrElse { AppUpdateInfo.UpToDate }
            _updateState.value = info
            if (info is AppUpdateInfo.UpdateAvailable) {
                startUpdate(context, info.downloadUrl)
            }
        }
    }

    /**
     * Walk ALL published releases and pick the numerically newest version.
     * GitHub's /releases/latest is unreliable here: it orders by the release's
     * created_at, which for API-created tags reflects the commit date — so an
     * older build can be served as "latest".
     */
    private fun fetchNewestRelease(): AppUpdateInfo {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases?per_page=20")
            .header("Cache-Control", "no-cache")
            .addHeader("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Release list request failed: HTTP ${response.code}")
                return AppUpdateInfo.UpToDate
            }
            val body = response.body?.string() ?: "[]"
            val releases = JSONArray(body)
            val newestTag: String? = newestVersion(
                (0 until releases.length()).mapNotNull { i ->
                    val item = releases.optJSONObject(i) ?: return@mapNotNull null
                    if (item.optBoolean("draft", false) || item.optBoolean("prerelease", false)) {
                        return@mapNotNull null
                    }
                    item.optString("tag_name").takeIf { it.isNotBlank() }
                }
            ) ?: return AppUpdateInfo.UpToDate

            if (!isNewerVersion(currentVersion(), newestTag)) {
                return AppUpdateInfo.UpToDate
            }
            val release = (0 until releases.length()).mapNotNull { i ->
                releases.optJSONObject(i)
            }.firstOrNull {
                normalizeVersion(it.optString("tag_name")) == newestTag
            } ?: return AppUpdateInfo.UpToDate

            val downloadUrl = pickApkAsset(release.optJSONArray("assets") ?: JSONArray())
                ?: release.optString("html_url").takeIf { it.isNotBlank() }
                ?: return AppUpdateInfo.UpToDate
            return AppUpdateInfo.UpdateAvailable(
                releaseName = release.optString("name").ifBlank { "Watchly $newestTag" },
                version = newestTag,
                downloadUrl = downloadUrl
            )
        }
    }

    fun startUpdate(context: Context, downloadUrl: String) {
        if (_installState.value is InstallState.Downloading || _installState.value is InstallState.Installing) {
            return
        }
        if (!downloadUrl.endsWith(".apk", ignoreCase = true)) {
            // No APK asset: fall back to opening the release page in a browser.
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        _installState.value = InstallState.Downloading
        scope.launch {
            try {
                val target = File(cacheDir(context), "watchly-update.apk")
                target.parentFile?.mkdirs()
                // Never trust a leftover file from a previous attempt.
                if (target.exists()) target.delete()

                val request = Request.Builder().url(downloadUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Download failed: HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IllegalStateException("Empty response body")
                    target.outputStream().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }

                if (target.length() < 100_000L) {
                    target.delete()
                    _installState.value = InstallState.Error(
                        "The downloaded file looks broken (${target.length()} bytes) — tap Update to try again."
                    )
                    return@launch
                }

                // Verify the downloaded APK really is a NEWER build before
                // handing it to the installer — so a stale or wrong file can
                // never silently "reinstall the same version" again.
                val archive = packageArchiveInfo(context, target)
                val archiveCode = archive?.longVersionCodeCompat() ?: -1L
                val archiveName = archive?.versionName ?: "?"
                val installedCode = currentVersionCode()
                if (archive == null || archiveCode <= installedCode) {
                    target.delete()
                    _installState.value = InstallState.Error(
                        "Downloaded file is build $archiveName ($archiveCode) but you already have " +
                            "${currentVersion()} ($installedCode) — tap Update to try again."
                    )
                    return@launch
                }

                _installState.value = InstallState.Installing
                installApk(context, target)
            } catch (e: Exception) {
                Log.w(TAG, "Update download failed", e)
                _installState.value = InstallState.Error("Update failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun resetInstallState() {
        _installState.value = InstallState.Idle
    }

    private fun cacheDir(context: Context): File = File(context.cacheDir, "updates")

    private fun installApk(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun packageArchiveInfo(context: Context, file: File): PackageInfo? = runCatching {
        context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
    }.getOrNull()

    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }

    fun currentVersion(): String {
        return runCatching {
            val info = applicationContext().packageManager.getPackageInfo(applicationContext().packageName, 0)
            info.versionName ?: "1.5.1"
        }.getOrDefault("1.5.1")
    }

    fun currentVersionCode(): Long {
        return runCatching {
            val info = applicationContext().packageManager.getPackageInfo(applicationContext().packageName, 0)
            info.longVersionCodeCompat()
        }.getOrDefault(0L)
    }

    private fun applicationContext(): Context {
        return androidx.startup.AppInitializer.getInstance(
            com.watchly.app.WatchlyApp::class.java.javaClass.let {
                // Fall back: grab the app context via a tiny reflection-free path.
                @Suppress("DEPRECATION")
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as Context
            }
        ).let { ctx -> ctx as? Context ?: error("no app context") }
    }

    private fun normalizeVersion(raw: String): String {
        return raw.trim().removePrefix("v").removePrefix("V")
    }

    fun isNewerVersion(currentVersion: String, newVersion: String): Boolean {
        val current = normalizeVersion(currentVersion).split(".").map { it.toIntOrNull() ?: 0 }
        val new = normalizeVersion(newVersion).split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(current.size, new.size)) {
            val c = current.getOrElse(i) { 0 }
            val n = new.getOrElse(i) { 0 }
            if (n > c) return true
            if (n < c) return false
        }
        return false
    }

    /** Newest numeric version among tag names, or null if none are usable. */
    fun newestVersion(tags: List<String>): String? {
        return tags.map { normalizeVersion(it) }
            .filter { it.isNotEmpty() && it.split(".").all { p -> p.toIntOrNull() != null } }
            .maxWithOrNull { a, b ->
                when {
                    a == b -> 0
                    isNewerVersion(a, b) -> 1
                    else -> -1
                }
            }
    }

    private fun pickApkAsset(assets: JSONArray): String? {
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.endsWith(".apk") && name.contains("watchly", ignoreCase = true)) return url
        }
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.endsWith(".apk")) return url
        }
        return null
    }
}
