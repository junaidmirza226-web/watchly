/*
 * Watchly — persisted transfer state for the cycle-5 engine.
 *
 * The worker ([DownloadWorker]) writes progress here as it goes;
 * [DownloadsRepository] reads it to render the Downloads list.
 * Because the state lives in SharedPreferences — not in any
 * process's memory — a pause, an app restart or a process death
 * loses nothing: the segment offsets on disk ARE the resume point.
 *
 * One JSON object per download record id. Reads are null-safe in
 * the codebase's usual way (org.json's optString returns the
 * literal "null" for JSON null — see the cycle-4 audit).
 */
package com.watchly.app.data.downloads

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Lifecycle of the transfer itself, as the worker last reported. */
enum class StoredDownloadStatus {
    /** Enqueued or actively transferring. */
    ACTIVE,

    /** Finished and verified; the .part file has been renamed. */
    DONE,

    /** Stopped with an error; segments stay saved for a resume. */
    FAILED
}

data class StoredDownloadState(
    val status: StoredDownloadStatus,
    val segments: List<DownloadSegment> = emptyList(),
    /** Total size when known; -1 when the source never said. */
    val totalBytes: Long = -1,
    val singleStream: Boolean = false,
    val failureReason: String? = null,
    /** The user pressed Pause: no worker may run until Resume. */
    val userPaused: Boolean = false,
    val updatedAtMs: Long = 0
) {
    val bytesDownloaded: Long get() = segments.sumOf { it.downloaded }
}

class DownloadStateStore(context: Context) {

    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The stored state for one download, or null when none exists. */
    fun state(id: String): StoredDownloadState? {
        val raw = prefs.getString(id, null) ?: return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val segments = ArrayList<DownloadSegment>()
        val array = obj.optJSONArray("segments")
        if (array != null) {
            for (i in 0 until array.length()) {
                val seg = array.optJSONObject(i) ?: continue
                segments += DownloadSegment(
                    start = seg.optLong("s"),
                    endInclusive = seg.optLong("e"),
                    downloaded = seg.optLong("d")
                )
            }
        }
        return StoredDownloadState(
            status = runCatching {
                StoredDownloadStatus.valueOf(obj.optString("status"))
            }.getOrElse { StoredDownloadStatus.ACTIVE },
            segments = segments,
            totalBytes = obj.optLong("total", -1),
            singleStream = obj.optBoolean("single", false),
            failureReason = obj.optString("reason").takeIf {
                it.isNotBlank() && it != "null"
            },
            userPaused = obj.optBoolean("userPaused", false),
            updatedAtMs = obj.optLong("updated")
        )
    }

    /** Persists live progress from the worker (status → ACTIVE). */
    fun saveProgress(id: String, snapshot: DownloadSnapshot) {
        val existing = state(id)
        write(
            id,
            StoredDownloadState(
                status = StoredDownloadStatus.ACTIVE,
                segments = snapshot.segments,
                totalBytes = snapshot.totalBytes,
                singleStream = snapshot.singleStream,
                failureReason = null,
                userPaused = existing?.userPaused ?: false
            )
        )
    }

    /** Marks the transfer complete (file renamed by the worker). */
    fun markDone(id: String) {
        val existing = state(id) ?: StoredDownloadState(StoredDownloadStatus.DONE)
        write(id, existing.copy(status = StoredDownloadStatus.DONE, failureReason = null))
    }

    /** Marks the transfer failed, KEEPING the segments for resume. */
    fun markFailed(id: String, reason: String) {
        val existing = state(id)
            ?: StoredDownloadState(StoredDownloadStatus.FAILED)
        write(
            id,
            existing.copy(status = StoredDownloadStatus.FAILED, failureReason = reason)
        )
    }

    /** Sets/clears the user's pause flag without touching progress. */
    fun setUserPaused(id: String, paused: Boolean) {
        val existing = state(id)
            ?: StoredDownloadState(StoredDownloadStatus.ACTIVE)
        write(id, existing.copy(userPaused = paused))
    }

    /** Drops all state for one download (delete / fresh start). */
    fun clear(id: String) {
        prefs.edit().remove(id).apply()
    }

    private fun write(id: String, state: StoredDownloadState) {
        val segments = JSONArray()
        for (segment in state.segments) {
            segments.put(
                JSONObject()
                    .put("s", segment.start)
                    .put("e", segment.endInclusive)
                    .put("d", segment.downloaded)
            )
        }
        val obj = JSONObject()
            .put("status", state.status.name)
            .put("segments", segments)
            .put("total", state.totalBytes)
            .put("single", state.singleStream)
            .put("reason", state.failureReason ?: JSONObject.NULL)
            .put("userPaused", state.userPaused)
            .put("updated", System.currentTimeMillis())
        prefs.edit().putString(id, obj.toString()).apply()
    }

    companion object {
        const val PREFS_NAME = "watchly_download_state"
    }
}
