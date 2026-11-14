/*
 * Watchly — offline downloads (spec §7).
 *
 * Cycle 5 replaced the system DownloadManager with Watchly's own
 * engine: [DownloadWorker] (WorkManager foreground work) drives a
 * segmented OkHttp downloader ([SegmentedDownloader]) with true
 * byte-level pause/resume — progress lives in [DownloadStateStore],
 * so a pause, an app restart or a process death loses nothing.
 * Files land in app-private storage and play from inside Watchly
 * only, per spec §7.1.
 *
 * Records created by the OLD DownloadManager engine (everything
 * before cycle 5) keep working: they carry engineKind "dm" and are
 * still refreshed / paused / resumed / deleted through
 * [AndroidDownloadEngine] until they reach a terminal state, so an
 * in-flight download from before the update still completes and no
 * Library entry breaks. Every NEW download uses the Watchly engine.
 *
 * Records live in SharedPreferences as JSON; both engines sit
 * behind interfaces so the repository's logic is testable with
 * fakes under Robolectric.
 *
 * Only titles with a playable file in Watchly can be downloaded:
 * public-domain Internet Archive films (and, later, personal
 * library files). Catalogue-only titles have no file to save, and
 * the UI says so honestly instead of faking a download.
 */
package com.watchly.app.data.downloads

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.Title
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One download, as stored and as shown on the Downloads list. */
data class DownloadRecord(
    /** Stable record id: "<title stable key>|<file name>". */
    val id: String,
    val titleKey: String,
    val titleName: String,
    val posterUrl: String?,
    val mediaType: MediaType,
    val fileName: String,
    val url: String,
    /** Expected size from the source, when known. */
    val sizeBytes: Long?,
    /** The quality the user picked for this download (spec §7.1). */
    val qualityLabel: String? = null,
    /** Legacy DownloadManager id (-1 for Watchly-engine records). */
    val downloadId: Long,
    /**
     * Which engine owns this record: [ENGINE_WATCHLY] (cycle 5+,
     * the default for new downloads) or [ENGINE_DM] (a record
     * created by the old DownloadManager engine, kept on its legacy
     * path until it terminates).
     */
    val engineKind: String = ENGINE_WATCHLY,
    val status: DownloadStatus,
    val bytesDownloaded: Long = 0,
    /** Total size as reported by the download engine, -1 when unknown. */
    val totalBytes: Long = -1,
    /** Absolute path of the finished file, set once COMPLETED. */
    val localPath: String? = null,
    /** Plain-language failure reason, set when FAILED. */
    val failureReason: String? = null,
    val createdAtMs: Long,
    /**
     * Paused BY THE USER (Phase D): the engine entry was removed, so
     * status refreshes must leave this record alone — it is waiting
     * for Resume, not lost. Distinct from a system pause (waiting
     * for network/Wi-Fi), which the engine still tracks.
     */
    val userPaused: Boolean = false
) {
    /** 0..1 progress for the progress bar, null when indeterminate. */
    val progressFraction: Float?
        get() = DownloadRules.progressFraction(
            bytesDownloaded,
            if (totalBytes > 0) totalBytes else (sizeBytes ?: -1)
        )

    val isActive: Boolean
        get() = DownloadRules.isActive(status)

    companion object {
        /** Cycle-5 engine: Watchly's own WorkManager downloader. */
        const val ENGINE_WATCHLY = "watchly"

        /** Pre-cycle-5 engine: the system DownloadManager. */
        const val ENGINE_DM = "dm"
    }
}

/** Snapshot of one download as reported by the platform engine. */
data class EngineDownloadState(
    val statusCode: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val reasonCode: Int
)

/**
 * The LEGACY platform download engine behind an interface (the
 * system DownloadManager). Only pre-cycle-5 records still use it,
 * until they terminate; [AndroidDownloadEngine] is the real one and
 * tests substitute a fake.
 */
interface DownloadEngine {
    /** Starts a download; returns the engine's download id. */
    fun enqueue(url: String, fileName: String, notificationTitle: String): Long

    /** Current state of a download, or null when the engine lost it. */
    fun query(downloadId: Long): EngineDownloadState?

    /** Cancels the download and removes its (partial) file. */
    fun remove(downloadId: Long)

    /** Where a finished file lives. */
    fun localPathFor(fileName: String): String
}

/** Real engine: the system DownloadManager, app-private destination. */
class AndroidDownloadEngine(context: Context) : DownloadEngine {

    private val appContext = context.applicationContext
    private val manager: DownloadManager =
        appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    override fun enqueue(url: String, fileName: String, notificationTitle: String): Long {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(notificationTitle)
            .setDescription("Watchly download")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setDestinationInExternalFilesDir(
                appContext, Environment.DIRECTORY_MOVIES, fileName
            )
        // §7.3 at the engine level too: while Wi-Fi only is on, the
        // system itself must not spend mobile data on a download
        // (the repository's checked start already refuses to begin
        // one on mobile data — this covers the connection changing
        // mid-download).
        if (DownloadSettingsRepository(appContext).settings().wifiOnly) {
            request.setAllowedOverMetered(false)
        }
        return manager.enqueue(request)
    }

    override fun query(downloadId: Long): EngineDownloadState? {
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
            ?: return null
        return cursor.use {
            if (!it.moveToFirst()) return null
            EngineDownloadState(
                statusCode = it.getInt(
                    it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                ),
                bytesDownloaded = it.getLong(
                    it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                ),
                totalBytes = it.getLong(
                    it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                ),
                reasonCode = it.getInt(
                    it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                )
            )
        }
    }

    override fun remove(downloadId: Long) {
        manager.remove(downloadId)
    }

    override fun localPathFor(fileName: String): String =
        File(appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES), fileName)
            .absolutePath
}

/**
 * The phone facts the §7.3 start rules read (network kind, battery,
 * free space), behind an interface so repository tests can script
 * them. The policy that judges them is [DownloadPolicy].
 */
interface DownloadEnvironmentProvider {
    fun current(): DownloadEnvironment
}

/**
 * Real provider: ConnectivityManager + the sticky battery intent +
 * StatFs on the downloads volume. Deliberately conservative — a
 * start is only ever blocked on a POSITIVELY known bad state
 * (cellular transport while Wi-Fi only is on, a read low battery,
 * a measured free-space shortfall). Anything the platform can't
 * describe is treated as fine and left for DownloadManager, which
 * queues and retries on its own.
 */
class AndroidDownloadEnvironmentProvider(context: Context) :
    DownloadEnvironmentProvider {

    private val appContext = context.applicationContext

    override fun current(): DownloadEnvironment {
        val connectivity = appContext.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as? ConnectivityManager
        val capabilities = runCatching {
            connectivity?.activeNetwork?.let {
                connectivity.getNetworkCapabilities(it)
            }
        }.getOrNull()
        // Only a POSITIVELY known cellular transport counts as
        // metered. Wi-Fi/Ethernet, VPN-only, empty or unreadable
        // capability sets are all treated as unmetered — the §7.3
        // block must never fire on a guess.
        val network = when {
            capabilities == null -> NetworkKind.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                NetworkKind.METERED
            else -> NetworkKind.WIFI
        }

        val batteryIntent = runCatching {
            appContext.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
        }.getOrNull()
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent = if (level >= 0 && scale > 0) {
            level * 100 / scale
        } else {
            null
        }
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val freeBytes = runCatching {
            val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: appContext.filesDir
            StatFs(dir.absolutePath).availableBytes
        }.getOrNull()

        return DownloadEnvironment(
            network = network,
            batteryPercent = batteryPercent,
            batteryCharging = charging,
            freeBytes = freeBytes
        )
    }
}

/** Result of [DownloadsRepository.startDownloadChecked]. */
sealed interface StartDownloadOutcome {
    /** The download is enqueued; [warning] rides along when §7.3 asks for one. */
    data class Started(
        val record: DownloadRecord,
        val warning: String?
    ) : StartDownloadOutcome

    /** Nothing was enqueued; [message] says why, in plain language. */
    data class Refused(val message: String) : StartDownloadOutcome
}

class DownloadsRepository(
    context: Context,
    private val engine: DownloadEngine = AndroidDownloadEngine(context),
    private val downloadSettings: DownloadSettingsRepository =
        DownloadSettingsRepository(context),
    private val environment: DownloadEnvironmentProvider =
        AndroidDownloadEnvironmentProvider(context),
    /** Cycle 5: schedules [DownloadWorker] transfers. */
    private val workScheduler: DownloadWorkScheduler =
        WorkManagerDownloadScheduler(context),
    /** Cycle 5: persisted transfer state (the resume points). */
    private val stateStore: DownloadStateStore = DownloadStateStore(context)
) {

    private val appContext = context.applicationContext

    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The folder finished downloads (and .part files) live in. */
    fun downloadsDir(): File =
        appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(appContext.filesDir, "downloads")

    /** The stored record exactly as saved (no engine refresh). */
    fun recordById(id: String): DownloadRecord? =
        readRecords().firstOrNull { it.id == id }

    /**
     * Starts downloading [file] for [title] and returns the record.
     * If the same file is already downloading or downloaded, the
     * existing record is returned — no duplicate downloads. A FAILED
     * Watchly-engine record RESUMES from its saved segment state on
     * retry (the bytes already on disk are kept); a legacy FAILED
     * record restarts, as the old engine always did.
     */
    fun startDownload(title: Title, file: PlayableFile): DownloadRecord {
        val id = "${title.stableKey}|${file.fileName}"
        val existing = readRecords().firstOrNull { it.id == id }
        if (existing != null && existing.status != DownloadStatus.FAILED) {
            return refreshOne(existing)
        }
        if (existing != null &&
            existing.engineKind == DownloadRecord.ENGINE_WATCHLY
        ) {
            // Retry of a failed Watchly download: the saved segments
            // in the state store are the resume point — re-enqueue
            // and the worker carries on from those exact bytes.
            val retry = existing.copy(
                status = DownloadStatus.QUEUED,
                userPaused = false,
                failureReason = null
            )
            replace(retry)
            stateStore.setUserPaused(id, false)
            workScheduler.start(retry)
            return retry
        }
        if (existing != null) {
            // Retry of a failed LEGACY download: drop the stale
            // engine entry; the new attempt uses the Watchly engine.
            runCatching { engine.remove(existing.downloadId) }
        }
        val record = DownloadRecord(
            id = id,
            titleKey = title.stableKey,
            titleName = title.name,
            posterUrl = title.posterUrl,
            mediaType = title.mediaType,
            fileName = file.fileName,
            url = file.url,
            sizeBytes = file.sizeBytes,
            qualityLabel = DownloadPolicy.qualityLabel(file),
            downloadId = -1,
            engineKind = DownloadRecord.ENGINE_WATCHLY,
            status = DownloadStatus.QUEUED,
            createdAtMs = System.currentTimeMillis()
        )
        writeRecords(readRecords().filter { it.id != id } + record)
        // A brand-new attempt starts from a clean transfer state.
        stateStore.clear(id)
        workScheduler.start(record)
        return record
    }

    /**
     * Starts a download only if the §7.3 rules allow it right now
     * (Wi-Fi only, low battery, free space, storage cap — see
     * [DownloadPolicy.evaluateStart]). The UI's download path goes
     * through here; [startDownload] stays the unconditional
     * primitive underneath (and what retries use).
     */
    fun startDownloadChecked(title: Title, file: PlayableFile): StartDownloadOutcome {
        val decision = DownloadPolicy.evaluateStart(
            settings = downloadSettings.settings(),
            environment = environment.current(),
            storedBytes = totalDownloadedBytes(),
            newFileBytes = file.sizeBytes
        )
        return when (decision) {
            is StartDecision.Blocked ->
                StartDownloadOutcome.Refused(decision.message)
            is StartDecision.AllowedWithWarning ->
                StartDownloadOutcome.Started(
                    startDownload(title, file),
                    decision.warning
                )
            StartDecision.Allowed ->
                StartDownloadOutcome.Started(startDownload(title, file), null)
        }
    }

    /** All downloads, newest first, with fresh engine statuses. */
    fun getDownloads(): List<DownloadRecord> {
        refreshAll()
        return readRecords().sortedByDescending { it.createdAtMs }
    }

    /** The download record for one title, refreshed, or null. */
    fun recordForTitle(titleKey: String): DownloadRecord? =
        getDownloads().firstOrNull { it.titleKey == titleKey }

    /** Deletes a download: engine work, files on disk, and the record. */
    fun delete(record: DownloadRecord) {
        if (record.engineKind == DownloadRecord.ENGINE_WATCHLY) {
            runCatching { workScheduler.stop(record.id) }
            runCatching {
                File(downloadsDir(), record.fileName).delete()
            }
            runCatching {
                File(downloadsDir(), record.fileName + DownloadWorker.PART_SUFFIX)
                    .delete()
            }
            stateStore.clear(record.id)
        } else {
            runCatching { engine.remove(record.downloadId) }
        }
        record.localPath?.let { path -> runCatching { File(path).delete() } }
        writeRecords(readRecords().filter { it.id != record.id })
    }

    /**
     * Pauses a download. Cycle 5 (Watchly engine): the pause flag
     * lands in the state store and the WorkManager work is
     * cancelled; the segment offsets stay saved, so Resume carries
     * on from the exact byte. Legacy records keep the pre-cycle-5
     * behaviour (engine entry removed; resume restarts — the old
     * engine has no other option).
     */
    fun pauseDownload(record: DownloadRecord): DownloadRecord {
        if (record.status == DownloadStatus.COMPLETED ||
            record.status == DownloadStatus.FAILED || record.userPaused
        ) {
            return record
        }
        if (record.engineKind == DownloadRecord.ENGINE_WATCHLY) {
            stateStore.setUserPaused(record.id, true)
            runCatching { workScheduler.stop(record.id) }
            val stored = stateStore.state(record.id)
            val paused = record.copy(
                status = DownloadStatus.PAUSED,
                userPaused = true,
                bytesDownloaded = stored?.bytesDownloaded ?: record.bytesDownloaded,
                totalBytes = stored?.totalBytes?.takeIf { it > 0 }
                    ?: record.totalBytes
            )
            replace(paused)
            return paused
        }
        runCatching { engine.remove(record.downloadId) }
        val paused = record.copy(
            status = DownloadStatus.PAUSED,
            userPaused = true
        )
        replace(paused)
        return paused
    }

    /**
     * Resumes a user-paused download. Watchly engine: re-enqueue
     * the worker — it picks up the saved segments and continues
     * from them (bytes already on disk are NOT re-downloaded).
     * Legacy: a fresh engine entry, as before.
     */
    fun resumeDownload(record: DownloadRecord): DownloadRecord {
        if (!record.userPaused) return record
        if (record.engineKind == DownloadRecord.ENGINE_WATCHLY) {
            val resumed = record.copy(
                status = DownloadStatus.QUEUED,
                userPaused = false,
                failureReason = null
            )
            replace(resumed)
            stateStore.setUserPaused(record.id, false)
            workScheduler.start(resumed)
            return resumed
        }
        val newId = engine.enqueue(record.url, record.fileName, record.titleName)
        val resumed = record.copy(
            downloadId = newId,
            status = DownloadStatus.QUEUED,
            userPaused = false,
            bytesDownloaded = 0,
            totalBytes = -1,
            failureReason = null
        )
        replace(resumed)
        return resumed
    }

    /** Total bytes of finished downloads (for the "space used" line). */
    fun totalDownloadedBytes(): Long =
        readRecords()
            .filter { it.status == DownloadStatus.COMPLETED }
            .sumOf { record ->
                val file = record.localPath?.let { File(it) }
                when {
                    file != null && file.exists() -> file.length()
                    record.sizeBytes != null -> record.sizeBytes
                    record.totalBytes > 0 -> record.totalBytes
                    else -> 0L
                }
            }

    // ------------------------------------------------------------------
    // Status refresh
    // ------------------------------------------------------------------

    private fun refreshAll() {
        val records = readRecords()
        val refreshed = records.map { refreshOne(it, persist = false) }
        if (refreshed != records) writeRecords(refreshed)
    }

    /** Pulls one record's live status from whichever engine owns it. */
    private fun refreshOne(record: DownloadRecord, persist: Boolean = true): DownloadRecord =
        if (record.engineKind == DownloadRecord.ENGINE_WATCHLY) {
            refreshWatchly(record, persist)
        } else {
            refreshLegacy(record, persist)
        }

    /**
     * Refresh for Watchly-engine records: the truth lives in the
     * [DownloadStateStore] the worker writes. A completed record
     * whose file vanished is still reported FAILED — an honest
     * state beats a Play button that can't play.
     */
    private fun refreshWatchly(record: DownloadRecord, persist: Boolean): DownloadRecord {
        val stored = stateStore.state(record.id)
        val updated = when {
            record.status == DownloadStatus.COMPLETED -> {
                val stillThere = record.localPath?.let { File(it).exists() } ?: false
                if (stillThere) {
                    record
                } else {
                    record.copy(
                        status = DownloadStatus.FAILED,
                        failureReason = "The downloaded file was deleted or moved."
                    )
                }
            }
            // A user pause is terminal until Resume: the worker is
            // cancelled, so the store can only hold the pause-time
            // snapshot — mirror its bytes for the row's progress bar.
            record.userPaused -> record.copy(
                bytesDownloaded = stored?.bytesDownloaded ?: record.bytesDownloaded,
                totalBytes = stored?.totalBytes?.takeIf { it > 0 }
                    ?: record.totalBytes
            )
            stored == null -> record // worker hasn't reported yet: QUEUED stands
            stored.status == StoredDownloadStatus.DONE -> {
                val finalFile = File(downloadsDir(), record.fileName)
                if (finalFile.exists()) {
                    record.copy(
                        status = DownloadStatus.COMPLETED,
                        bytesDownloaded = stored.bytesDownloaded
                            .takeIf { it > 0 } ?: finalFile.length(),
                        totalBytes = if (stored.totalBytes > 0) {
                            stored.totalBytes
                        } else {
                            finalFile.length()
                        },
                        localPath = finalFile.absolutePath,
                        failureReason = null
                    )
                } else {
                    record.copy(
                        status = DownloadStatus.FAILED,
                        failureReason = "The downloaded file was deleted or moved."
                    )
                }
            }
            stored.status == StoredDownloadStatus.FAILED -> record.copy(
                status = DownloadStatus.FAILED,
                bytesDownloaded = stored.bytesDownloaded,
                totalBytes = stored.totalBytes,
                failureReason = stored.failureReason
                    ?: "Something went wrong during the download."
            )
            else -> record.copy( // ACTIVE
                status = if (stored.bytesDownloaded > 0) {
                    DownloadStatus.RUNNING
                } else {
                    DownloadStatus.QUEUED
                },
                bytesDownloaded = stored.bytesDownloaded,
                totalBytes = stored.totalBytes,
                failureReason = null
            )
        }
        if (persist && updated != record) replace(updated)
        return updated
    }

    /**
     * Pulls one LEGACY (DownloadManager-era) record's live status
     * from the system engine. Terminal records (COMPLETED, and
     * FAILED already carrying a reason) are left alone, except that
     * a completed record whose file vanished is reported FAILED.
     */
    private fun refreshLegacy(record: DownloadRecord, persist: Boolean = true): DownloadRecord {
        // A user-paused record has no engine entry by design (see
        // pauseDownload) — refreshing it would misread the missing
        // entry as "removed from the system" and flip it to FAILED.
        if (record.userPaused) return record
        if (record.status == DownloadStatus.COMPLETED) {
            val stillThere = record.localPath?.let { File(it).exists() } ?: false
            return if (stillThere) {
                record
            } else {
                record.copy(
                    status = DownloadStatus.FAILED,
                    failureReason = "The downloaded file was deleted or moved."
                ).also { if (persist) replace(it) }
            }
        }
        val state = runCatching { engine.query(record.downloadId) }.getOrNull()
        val updated = if (state == null) {
            if (record.status == DownloadStatus.FAILED) {
                record
            } else {
                record.copy(
                    status = DownloadStatus.FAILED,
                    failureReason = "The download was removed from the system."
                )
            }
        } else {
            val status = DownloadRules.statusFromCode(state.statusCode)
            record.copy(
                status = status,
                bytesDownloaded = state.bytesDownloaded,
                totalBytes = state.totalBytes,
                localPath = if (status == DownloadStatus.COMPLETED) {
                    engine.localPathFor(record.fileName)
                } else {
                    record.localPath
                },
                failureReason = if (status == DownloadStatus.FAILED) {
                    DownloadRules.failureReason(state.reasonCode)
                } else {
                    null
                }
            )
        }
        if (persist && updated != record) replace(updated)
        return updated
    }

    private fun replace(record: DownloadRecord) {
        writeRecords(readRecords().map { if (it.id == record.id) record else it })
    }

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    private fun readRecords(): List<DownloadRecord> {
        val raw = prefs.getString(KEY_DOWNLOADS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        val out = ArrayList<DownloadRecord>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            out.add(
                DownloadRecord(
                    id = id,
                    titleKey = obj.optString("titleKey"),
                    titleName = obj.optString("titleName").ifBlank { "Unknown title" },
                    posterUrl = obj.optString("poster").takeIf {
                        it.isNotBlank() && it != "null"
                    },
                    mediaType = runCatching {
                        MediaType.valueOf(obj.optString("type"))
                    }.getOrElse { MediaType.MOVIE },
                    fileName = obj.optString("fileName"),
                    url = obj.optString("url"),
                    sizeBytes = if (obj.has("size") && !obj.isNull("size")) {
                        obj.optLong("size")
                    } else {
                        null
                    },
                    qualityLabel = obj.optString("quality").takeIf {
                        it.isNotBlank() && it != "null"
                    },
                    downloadId = obj.optLong("downloadId", -1),
                    // Records saved before cycle 5 have no engine
                    // field: they belong to the DownloadManager era.
                    engineKind = if (
                        obj.optString("engine") == DownloadRecord.ENGINE_WATCHLY
                    ) {
                        DownloadRecord.ENGINE_WATCHLY
                    } else {
                        DownloadRecord.ENGINE_DM
                    },
                    status = runCatching {
                        DownloadStatus.valueOf(obj.optString("status"))
                    }.getOrElse { DownloadStatus.FAILED },
                    bytesDownloaded = obj.optLong("bytes"),
                    totalBytes = obj.optLong("total", -1),
                    localPath = obj.optString("path").takeIf {
                        it.isNotBlank() && it != "null"
                    },
                    failureReason = obj.optString("reason").takeIf {
                        it.isNotBlank() && it != "null"
                    },
                    createdAtMs = obj.optLong("createdAt"),
                    userPaused = obj.optBoolean("userPaused", false)
                )
            )
        }
        return out
    }

    private fun writeRecords(records: List<DownloadRecord>) {
        val array = JSONArray()
        for (record in records) {
            array.put(
                JSONObject()
                    .put("id", record.id)
                    .put("titleKey", record.titleKey)
                    .put("titleName", record.titleName)
                    .put("poster", record.posterUrl ?: JSONObject.NULL)
                    .put("type", record.mediaType.name)
                    .put("fileName", record.fileName)
                    .put("url", record.url)
                    .put("size", record.sizeBytes ?: JSONObject.NULL)
                    .put("quality", record.qualityLabel ?: JSONObject.NULL)
                    .put("downloadId", record.downloadId)
                    .put("engine", record.engineKind)
                    .put("status", record.status.name)
                    .put("bytes", record.bytesDownloaded)
                    .put("total", record.totalBytes)
                    .put("path", record.localPath ?: JSONObject.NULL)
                    .put("reason", record.failureReason ?: JSONObject.NULL)
                    .put("createdAt", record.createdAtMs)
                    .put("userPaused", record.userPaused)
            )
        }
        prefs.edit().putString(KEY_DOWNLOADS, array.toString()).apply()
    }

    companion object {
        const val PREFS_NAME = "watchly_downloads"
        const val KEY_DOWNLOADS = "downloads"
    }
}
