/*
 * Watchly — background transfer worker for the cycle-5 engine.
 *
 * A [CoroutineWorker] that runs one download (by record id) through
 * [SegmentedDownloader] with the OkHttp fetcher. It runs as a
 * foreground service with a progress notification, so a long
 * download survives the app being swiped away; WorkManager itself
 * persists the work across process death and reboots.
 *
 * State discipline: ALL progress goes to [DownloadStateStore]
 * (segment offsets included) — the worker keeps nothing only in
 * memory, so wherever it stops, the next run resumes from the
 * exact saved byte. The user's pause flag is honoured from the
 * store, and WorkManager cancellation surfaces as a pause, never
 * as a failure.
 */
package com.watchly.app.data.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val recordId = inputData.getString(KEY_RECORD_ID)
            ?: return Result.failure()
        val repository = DownloadsRepository(applicationContext)
        val record = repository.recordById(recordId) ?: return Result.success()
        val store = DownloadStateStore(applicationContext)

        // A user-paused (or since-deleted) download must not run.
        val stored = store.state(recordId)
        if (stored?.userPaused == true) return Result.success()

        val downloadsDir = repository.downloadsDir()
        downloadsDir.mkdirs()
        val partFile = File(downloadsDir, record.fileName + PART_SUFFIX)
        val finalFile = File(downloadsDir, record.fileName)
        if (finalFile.exists() && stored?.status == StoredDownloadStatus.DONE) {
            return Result.success()
        }

        setForeground(foregroundInfo(record.titleName, stored?.bytesDownloaded ?: 0, stored?.totalBytes ?: -1))

        val downloader = SegmentedDownloader(OkHttpRangeFetcher())
        // Latest snapshot, shared with the notification ticker below
        // (onState is not a suspend context, so it cannot call
        // setForeground itself — it only persists + publishes here).
        val latest = java.util.concurrent.atomic.AtomicReference<DownloadSnapshot?>(null)
        val outcome = try {
            kotlinx.coroutines.coroutineScope {
                val ticker = launch {
                    while (true) {
                        kotlinx.coroutines.delay(NOTIFICATION_THROTTLE_MS)
                        val snapshot = latest.get() ?: continue
                        runCatching {
                            setForeground(
                                foregroundInfo(
                                    record.titleName,
                                    snapshot.bytesDownloaded,
                                    snapshot.totalBytes
                                )
                            )
                        }
                    }
                }
                try {
                    downloader.download(
                        url = record.url,
                        targetFile = partFile,
                        savedSegments = stored?.segments?.takeIf { it.isNotEmpty() },
                        savedSingleStream = stored?.singleStream ?: false,
                        expectedTotal = record.sizeBytes,
                        shouldStop = {
                            isStopped ||
                                DownloadStateStore(applicationContext)
                                    .state(recordId)?.userPaused == true
                        },
                        onState = { snapshot ->
                            latest.set(snapshot)
                            store.saveProgress(recordId, snapshot)
                        }
                    )
                } finally {
                    ticker.cancel()
                }
            }
        } catch (e: CancellationException) {
            // WorkManager cancelled us (pause / constraints / delete):
            // the downloader already persisted its last snapshot.
            throw e
        }

        return when (outcome) {
            is DownloadOutcome.Completed -> {
                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    // Cross-filesystem or locked: fall back to copy.
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
                store.markDone(recordId)
                Result.success()
            }
            is DownloadOutcome.Paused -> {
                store.saveProgress(recordId, outcome.snapshot)
                Result.success()
            }
            is DownloadOutcome.Failed -> {
                // Keep the segments: Retry/Resume continues from them.
                if (outcome.snapshot.segments.isNotEmpty()) {
                    store.saveProgress(recordId, outcome.snapshot)
                }
                store.markFailed(recordId, outcome.reason)
                Result.failure()
            }
        }
    }

    private fun foregroundInfo(title: String, bytes: Long, total: Long): ForegroundInfo {
        ensureChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(
                if (total > 0) {
                    "Downloading… ${(bytes * 100 / total)}%"
                } else {
                    "Downloading…"
                }
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (total > 0) {
                    setProgress(100, (bytes * 100 / total).toInt(), false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_RECORD_ID = "record_id"
        const val PART_SUFFIX = ".part"
        private const val CHANNEL_ID = "watchly_downloads"
        private const val NOTIFICATION_ID = 4201
        private const val NOTIFICATION_THROTTLE_MS = 1_000L

        /** Creates the downloads notification channel once. */
        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Downloads",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }
}
