/*
 * Watchly — schedules [DownloadWorker] runs (cycle 5).
 *
 * Behind an interface so repository tests can substitute a fake
 * that just records start/stop calls (WorkManager itself needs a
 * real Android runtime). The production implementation enqueues
 * UNIQUE work per download record: starting an already-scheduled
 * download replaces the old request, and the engine-level §7.3
 * Wi-Fi-only rule is expressed as a WorkManager network constraint
 * (UNMETERED), so a download cannot spend mobile data even if the
 * connection changes mid-transfer.
 */
package com.watchly.app.data.downloads

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/** Starts/stops background transfer work for download records. */
interface DownloadWorkScheduler {
    /** Enqueues (or re-enqueues) the transfer for [record]. */
    fun start(record: DownloadRecord)

    /** Cancels any scheduled/running transfer for [recordId]. */
    fun stop(recordId: String)
}

/** Production scheduler: WorkManager unique work per record. */
class WorkManagerDownloadScheduler(context: Context) : DownloadWorkScheduler {

    private val appContext = context.applicationContext
    private val settings = DownloadSettingsRepository(appContext)

    override fun start(record: DownloadRecord) {
        // §7.3 at the engine level: while Wi-Fi only is on, the
        // work itself requires an unmetered network — WorkManager
        // will not start (and will pause) it on mobile data.
        val networkType = if (settings.settings().wifiOnly) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(DownloadWorker.KEY_RECORD_ID, record.id)
                    .build()
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(networkType).build()
            )
            .addTag(TAG)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            uniqueWorkName(record.id),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    override fun stop(recordId: String) {
        WorkManager.getInstance(appContext).cancelUniqueWork(uniqueWorkName(recordId))
    }

    companion object {
        const val TAG = "watchly_download"

        /** Unique WorkManager name for one download record. */
        fun uniqueWorkName(recordId: String): String = "watchly-dl-$recordId"
    }
}
