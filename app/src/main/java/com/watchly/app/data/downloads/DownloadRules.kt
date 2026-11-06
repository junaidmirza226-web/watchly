/*
 * Watchly — download status / failure mapping (pure Kotlin, spec §7.2).
 *
 * The Android DownloadManager reports integer status and reason
 * codes. This file maps them onto Watchly's own [DownloadStatus] and
 * plain-language failure reasons WITHOUT importing any Android class,
 * so the mapping is unit-testable on a plain JVM. The integer values
 * below mirror android.app.DownloadManager's constants (stable since
 * API 9 — they cannot change without breaking every Android app):
 *
 *   STATUS_PENDING = 1, STATUS_RUNNING = 2, STATUS_PAUSED = 4,
 *   STATUS_SUCCESSFUL = 8, STATUS_FAILED = 16
 *   ERROR_UNKNOWN = 1000, ERROR_FILE_ERROR = 1001,
 *   ERROR_HTTP_DATA_ERROR = 1002, ERROR_TOO_MANY_REDIRECTS = 1003,
 *   ERROR_INSUFFICIENT_SPACE = 1004, ERROR_DEVICE_NOT_FOUND = 1005,
 *   ERROR_CANNOT_RESUME = 1006, ERROR_FILE_ALREADY_EXISTS = 1007
 *   PAUSED_WAITING_TO_RETRY = 1, PAUSED_WAITING_FOR_NETWORK = 2,
 *   PAUSED_QUEUED_FOR_WIFI = 3, PAUSED_UNKNOWN = 4
 */
package com.watchly.app.data.downloads

/** Watchly's own download lifecycle, independent of any platform API. */
enum class DownloadStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED
}

object DownloadRules {

    // DownloadManager status codes (mirrored — see header).
    const val CODE_PENDING = 1
    const val CODE_RUNNING = 2
    const val CODE_PAUSED = 4
    const val CODE_SUCCESSFUL = 8
    const val CODE_FAILED = 16

    /** Maps a DownloadManager status code onto [DownloadStatus]. */
    fun statusFromCode(code: Int): DownloadStatus = when (code) {
        CODE_PENDING -> DownloadStatus.QUEUED
        CODE_RUNNING -> DownloadStatus.RUNNING
        CODE_PAUSED -> DownloadStatus.PAUSED
        CODE_SUCCESSFUL -> DownloadStatus.COMPLETED
        CODE_FAILED -> DownloadStatus.FAILED
        else -> DownloadStatus.QUEUED
    }

    /** True while the download can still make progress on its own. */
    fun isActive(status: DownloadStatus): Boolean =
        status == DownloadStatus.QUEUED ||
            status == DownloadStatus.RUNNING ||
            status == DownloadStatus.PAUSED

    /**
     * Plain-language reason for a failed download, safe to show the
     * user (spec §7.2: failures say why). [reasonCode] is the
     * DownloadManager COLUMN_REASON value for a failed download.
     */
    fun failureReason(reasonCode: Int): String = when (reasonCode) {
        1004 -> "Not enough space on the phone."
        1001, 1005 -> "Couldn't save the file to storage."
        1002, 1003 -> "The source sent bad data. Try again."
        1006 -> "The download couldn't be resumed."
        1007 -> "That file is already downloaded."
        in 400..599 -> "The source is unavailable right now (HTTP $reasonCode)."
        else -> "Something went wrong during the download."
    }

    /** Progress as a 0..1 fraction, or null when it can't be known. */
    fun progressFraction(bytesDownloaded: Long, totalBytes: Long): Float? =
        if (totalBytes > 0) {
            (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
}
