/*
 * Watchly — offline downloads (spec §7, v1 slice).
 *
 * Downloads are done by the system DownloadManager (survives the app
 * being closed, shows in the phone's notification shade) into
 * app-private storage: files play from inside Watchly only, per spec
 * §7.1. Records live in SharedPreferences as JSON; the engine —
 * [DownloadEngine] — is an interface so the repository's logic is
 * testable with a fake engine under Robolectric.
 *
 * Only titles with a playable file in Watchly can be downloaded in
 * v1: public-domain Internet Archive films (and, later, personal
 * library files). Catalogue-only titles have no file to save, and
 * the UI says so honestly instead of faking a download.
 */
package com.watchly.app.data.downloads

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
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
    /** System DownloadManager id (-1 only for hand-built records). */
    val downloadId: Long,
    val status: DownloadStatus,
    val bytesDownloaded: Long = 0,
    /** Total size as reported by the download engine, -1 when unknown. */
    val totalBytes: Long = -1,
    /** Absolute path of the finished file, set once COMPLETED. */
    val localPath: String? = null,
    /** Plain-language failure reason, set when FAILED. */
    val failureReason: String? = null,
    val createdAtMs: Long
) {
    /** 0..1 progress for the progress bar, null when indeterminate. */
    val progressFraction: Float?
        get() = DownloadRules.progressFraction(
            bytesDownloaded,
            if (totalBytes > 0) totalBytes else (sizeBytes ?: -1)
        )

    val isActive: Boolean
        get() = DownloadRules.isActive(status)
}

/** Snapshot of one download as reported by the platform engine. */
data class EngineDownloadState(
    val statusCode: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val reasonCode: Int
)

/**
 * The platform download engine behind an interface.
 * [AndroidDownloadEngine] is the real one; tests substitute a fake.
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

class DownloadsRepository(
    context: Context,
    private val engine: DownloadEngine = AndroidDownloadEngine(context)
) {

    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Starts downloading [file] for [title] and returns the record.
     * If the same file is already downloading or downloaded, the
     * existing record is returned — no duplicate downloads. A FAILED
     * record is retried fresh.
     */
    fun startDownload(title: Title, file: PlayableFile): DownloadRecord {
        val id = "${title.stableKey}|${file.fileName}"
        val existing = readRecords().firstOrNull { it.id == id }
        if (existing != null && existing.status != DownloadStatus.FAILED) {
            return refreshOne(existing)
        }
        if (existing != null) {
            // Retry of a failed download: drop the stale engine entry.
            runCatching { engine.remove(existing.downloadId) }
        }
        val downloadId = engine.enqueue(file.url, file.fileName, title.name)
        val record = DownloadRecord(
            id = id,
            titleKey = title.stableKey,
            titleName = title.name,
            posterUrl = title.posterUrl,
            mediaType = title.mediaType,
            fileName = file.fileName,
            url = file.url,
            sizeBytes = file.sizeBytes,
            downloadId = downloadId,
            status = DownloadStatus.QUEUED,
            createdAtMs = System.currentTimeMillis()
        )
        writeRecords(readRecords().filter { it.id != id } + record)
        return record
    }

    /** All downloads, newest first, with fresh engine statuses. */
    fun getDownloads(): List<DownloadRecord> {
        refreshAll()
        return readRecords().sortedByDescending { it.createdAtMs }
    }

    /** The download record for one title, refreshed, or null. */
    fun recordForTitle(titleKey: String): DownloadRecord? =
        getDownloads().firstOrNull { it.titleKey == titleKey }

    /** Deletes a download: engine entry, file on disk, and the record. */
    fun delete(record: DownloadRecord) {
        runCatching { engine.remove(record.downloadId) }
        record.localPath?.let { path -> runCatching { File(path).delete() } }
        writeRecords(readRecords().filter { it.id != record.id })
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

    /**
     * Pulls one record's live status from the engine. Terminal records
     * (COMPLETED, and FAILED already carrying a reason) are left alone,
     * except that a completed record whose file vanished is reported
     * FAILED — an honest state beats a Play button that can't play.
     */
    private fun refreshOne(record: DownloadRecord, persist: Boolean = true): DownloadRecord {
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
                    downloadId = obj.optLong("downloadId", -1),
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
                    createdAtMs = obj.optLong("createdAt")
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
                    .put("downloadId", record.downloadId)
                    .put("status", record.status.name)
                    .put("bytes", record.bytesDownloaded)
                    .put("total", record.totalBytes)
                    .put("path", record.localPath ?: JSONObject.NULL)
                    .put("reason", record.failureReason ?: JSONObject.NULL)
                    .put("createdAt", record.createdAtMs)
            )
        }
        prefs.edit().putString(KEY_DOWNLOADS, array.toString()).apply()
    }

    companion object {
        const val PREFS_NAME = "watchly_downloads"
        const val KEY_DOWNLOADS = "downloads"
    }
}
