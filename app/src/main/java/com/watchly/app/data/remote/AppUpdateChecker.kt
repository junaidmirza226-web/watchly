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
import org.json.JSONArray
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

    // The LIST endpoint is used (not /releases/latest) because GitHub's
    // "latest" pointer sorts by created_at, which for API-created tags
    // reflects the commit date — an older build can win that sort. We walk
    // all published releases and pick the numerically newest version instead.
    private const val GITHUB_RELEASES_API_URL =
        "https://api.github.com/repos/junaidmirza226-web/watchly/releases?per_page=20"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
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
                .header("Accept", "application/vnd.github+json")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "WatchlyApp/$installedVersion (Android)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null

                val releases = parseReleases(body)
                val bestTag = newestVersion(releases.map { it.tag }) ?: return@withContext null
                if (!isNewerVersion(current = installedVersion, remote = bestTag)) {
                    return@withContext null
                }
                val release = releases.first { it.tag == bestTag }

                // APK asset download URL first, release page as fallback.
                val downloadUrl = pickApkAsset(release.json) ?: release.htmlUrl

                val info = AppUpdateInfo(
                    currentVersion = installedVersion,
                    latestVersion = bestTag,
                    releaseNotes = release.notes.ifBlank { null },
                    downloadUrl = downloadUrl,
                    releaseName = release.name.ifBlank { null }
                )
                _updateState.value = info
                return@withContext info
            }
        } catch (_: Exception) {
            // Never crash on network/JSON errors
        }
        null
    }

    /**
     * One-tap update: always re-checks GitHub first so a long-lived app
     * process can never hand the installer a stale, outdated download URL.
     */
    fun updateNow(context: Context) {
        val appContext = context.applicationContext
        installScope.launch {
            val info = checkForUpdates(appContext)
            if (info != null) {
                startUpdate(appContext, info.downloadUrl, promisedVersion = info.latestVersion)
            }
        }
    }

    private data class ReleaseEntry(
        val tag: String,
        val name: String,
        val notes: String,
        val htmlUrl: String,
        val json: JSONObject
    )

    /** Accepts either a JSON array of releases or a single release object. */
    private fun parseReleases(body: String): List<ReleaseEntry> {
        val items: List<JSONObject> = when {
            body.trimStart().startsWith("[") -> {
                val arr = JSONArray(body)
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            }
            else -> listOfNotNull(JSONObject(body))
        }
        return items
            .filter { !it.optBoolean("draft", false) && !it.optBoolean("prerelease", false) }
            .mapNotNull { item ->
                val tag = item.optString("tag_name", "").removePrefix("v").trim()
                if (tag.isBlank()) return@mapNotNull null
                ReleaseEntry(
                    tag = tag,
                    name = item.optString("name", "Version $tag"),
                    notes = item.optString("body", ""),
                    htmlUrl = item.optString("html_url", ""),
                    json = item
                )
            }
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

    /** Installed versionCode, or -1 when unknown. */
    fun currentVersionCode(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrDefault(-1L)

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

    /** Newest numeric version among tag names, or null if none are usable. */
    fun newestVersion(tags: List<String>): String? {
        return tags.map { it.removePrefix("v").trim() }
            .filter { tag ->
                tag.isNotEmpty() && tag.split(".").all { part -> part.toIntOrNull() != null }
            }
            .fold<String, String?>(null) { best, candidate ->
                when {
                    best == null -> candidate
                    isNewerVersion(current = best, remote = candidate) -> candidate
                    else -> best
                }
            }
    }

    /** Outcome of checking a downloaded APK before the installer is allowed to open. */
    sealed interface ApkVerdict {
        data object Install : ApkVerdict
        /** The file is the same build (or older) than what is installed. Never sent to the installer. */
        data object SameOrOlder : ApkVerdict
        /** The file is not the version the update promised. */
        data class WrongVersion(val promised: String, val found: String) : ApkVerdict
        data object Unreadable : ApkVerdict
    }

    /**
     * Pure decision used by [startUpdate]. [archiveCode] is -1 when the file
     * could not be read as an APK. [promisedVersion] null = no promise to check.
     */
    fun judgeApk(
        archiveCode: Long,
        archiveName: String?,
        installedCode: Long,
        promisedVersion: String?
    ): ApkVerdict {
        if (archiveCode < 0 || archiveName.isNullOrBlank()) return ApkVerdict.Unreadable
        if (promisedVersion != null && archiveName.removePrefix("v").trim() != promisedVersion.removePrefix("v").trim()) {
            return ApkVerdict.WrongVersion(promisedVersion, archiveName)
        }
        if (installedCode >= 0 && archiveCode <= installedCode) return ApkVerdict.SameOrOlder
        return ApkVerdict.Install
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
     *
     * [promisedVersion] is the version the update banner offered: the
     * installer only opens when the downloaded file really is at least
     * that version and strictly newer than the installed one — a stale
     * or wrong file can never silently "reinstall the same version".
     */
    fun startUpdate(
        context: Context,
        downloadUrl: String,
        promisedVersion: String? = null
    ) {
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
                // Never trust a leftover file from a previous attempt.
                if (target.exists()) target.delete()
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

                // Verify the downloaded file really is a NEWER build before
                // opening the installer — a stale or wrong file must never
                // silently "reinstall the same version" again.
                val archive = runCatching {
                    @Suppress("DEPRECATION")
                    appContext.packageManager.getPackageArchiveInfo(target.absolutePath, 0)
                }.getOrNull()
                val archiveCode = archive?.let {
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        it.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        it.versionCode.toLong()
                    }
                } ?: -1L
                val archiveName = archive?.versionName ?: "?"
                val installedCode = currentVersionCode(appContext)
                val verdict = judgeApk(
                    archiveCode = if (archive == null || target.length() < 100_000L) -1L else archiveCode,
                    archiveName = archiveName,
                    installedCode = installedCode,
                    promisedVersion = promisedVersion
                )
                when (verdict) {
                    ApkVerdict.Install -> installApk(appContext, target)
                    ApkVerdict.SameOrOlder -> {
                        target.delete()
                        _installState.value = InstallState.Error(
                            "You're already on the latest version (${currentVersion(appContext) ?: "installed"})."
                        )
                    }
                    is ApkVerdict.WrongVersion -> {
                        target.delete()
                        _installState.value = InstallState.Error(
                            "The update file didn't match the offered version " +
                                "(expected ${verdict.promised}, got ${verdict.found}). Tap Update to try again."
                        )
                    }
                    ApkVerdict.Unreadable -> {
                        target.delete()
                        _installState.value = InstallState.Error(
                            "The download was incomplete. Tap Update to try again."
                        )
                    }
                }
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
        context.startActivity(intent)
    }

    /** Browser fallback when no APK asset exists. */
    private fun triggerInstall(context: Context, url: String) {
        runCatching {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url)
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun resetInstallState() {
        _installState.value = InstallState.Idle
    }
}
