/*
 * Watchly — download start policy (pure Kotlin, spec §7.1 + §7.3).
 *
 * Two decisions live here, both expressed without any Android type
 * so they are unit-testable on a plain JVM:
 *
 *  1. Quality choice (§7.1): when a title offers more than one
 *     playable file, the user picks before the download starts.
 *     The files themselves are the choices, best (largest) first.
 *
 *  2. Start rules (§7.3): downloads respect the phone's network,
 *     battery and space —
 *       · Wi-Fi only (a setting, ON by default): a download never
 *         starts on mobile data while it is on.
 *       · Pause on low battery (a setting): a download does not
 *         start while the battery is low and not charging.
 *       · Storage cap: unlimited by default — the phone's own free
 *         space is the limit. The cap machinery stays for installs
 *         that still carry an old finite setting.
 *       · Free space: a download bigger than the phone's free
 *         space is refused up front, instead of dying halfway.
 *     Mid-download, the system DownloadManager still stops a
 *     download that runs out of space; DownloadRules phrases that
 *     reason as the pause it is, and Retry restarts it.
 */
package com.watchly.app.data.downloads

import com.watchly.app.data.model.PlayableFile

/** The user's download settings, as a plain snapshot (spec §7.3). */
data class DownloadSettings(
    val wifiOnly: Boolean = true,
    val pauseOnLowBattery: Boolean = true,
    val storageCapBytes: Long = DEFAULT_STORAGE_CAP_BYTES
) {
    companion object {
        /** No artificial cap: downloads fill the phone's free space. */
        const val DEFAULT_STORAGE_CAP_BYTES: Long = Long.MAX_VALUE

        /** Fraction of the cap at which the user gets a warning. */
        const val CAP_WARNING_FRACTION = 0.9
    }
}

/** What kind of network the phone is on, in download terms. */
enum class NetworkKind {
    /** On Wi-Fi (or another unmetered link treated as Wi-Fi). */
    WIFI,

    /** Connected, but over mobile data / a metered link. */
    METERED,

    /** No usable connection at all. */
    OFFLINE
}

/**
 * The phone state the start rules read. Collected by the platform
 * layer (DownloadsRepository's environment provider); the policy
 * itself only ever sees this snapshot.
 */
data class DownloadEnvironment(
    val network: NetworkKind,
    /** Battery charge 0..100, or null when it can't be read. */
    val batteryPercent: Int?,
    val batteryCharging: Boolean,
    /** Free bytes on the downloads volume, or null when unknown. */
    val freeBytes: Long?
)

/** Outcome of evaluating a would-be download start. */
sealed interface StartDecision {
    /** Start now, nothing to tell the user. */
    data object Allowed : StartDecision

    /** Start now, but show [warning] with the confirmation. */
    data class AllowedWithWarning(val warning: String) : StartDecision

    /** Do not start; [message] explains why in plain language. */
    data class Blocked(val message: String) : StartDecision
}

object DownloadPolicy {

    /** Android's own low-battery threshold (the system warning level). */
    const val LOW_BATTERY_PERCENT = 15

    // -----------------------------------------------------------------
    // Quality choice (spec §7.1)
    // -----------------------------------------------------------------

    /**
     * The quality choices for a title: its playable files, best
     * first (largest file = highest quality, the Archive ordering),
     * with exact duplicates (same file name) removed. One entry
     * means no choice is needed.
     */
    fun qualityOptions(files: List<PlayableFile>): List<PlayableFile> =
        files.distinctBy { it.fileName }
            .sortedByDescending { it.sizeBytes ?: 0L }

    /** True when the user must pick a quality before downloading. */
    fun needsQualityChoice(files: List<PlayableFile>): Boolean =
        qualityOptions(files).size > 1

    /**
     * The Data Saver default (cycle 10, §14.1): when the user has
     * Data Saver on, a download starts without the quality picker,
     * with the source's own first file. Source order is a contract,
     * not an accident: ArchiveApi returns its files efficient-first
     * (smallest adequate MP4 derivative first — see its ranking),
     * so the first entry IS the efficient pick, and single-source
     * lists (one file) are trivially their own default.
     */
    fun efficientDefault(files: List<PlayableFile>): PlayableFile? =
        files.firstOrNull()

    /** Display label for one choice: quality, then format, then name. */
    fun qualityLabel(file: PlayableFile): String =
        file.qualityLabel ?: file.format ?: file.fileName

    // -----------------------------------------------------------------
    // Start rules (spec §7.3)
    // -----------------------------------------------------------------

    /** Classifies raw network facts into a [NetworkKind]. */
    fun networkKind(
        connected: Boolean,
        onWifiTransport: Boolean
    ): NetworkKind = when {
        !connected -> NetworkKind.OFFLINE
        onWifiTransport -> NetworkKind.WIFI
        else -> NetworkKind.METERED
    }

    /** Low battery = at/under the system threshold and not charging. */
    fun isBatteryLow(batteryPercent: Int?, charging: Boolean): Boolean =
        batteryPercent != null && !charging &&
            batteryPercent <= LOW_BATTERY_PERCENT

    /**
     * Decides whether a download of [newFileBytes] (null = size
     * unknown) may start, given the [settings], the phone's
     * [environment] and how much Watchly already stores
     * ([storedBytes], finished downloads only).
     *
     * Blocks are checked in the order a user would want to hear
     * them: connection first, then the Wi-Fi-only rule, then
     * battery, then space — free space before the Watchly cap,
     * because "the phone is full" beats "the app limit is full".
     */
    fun evaluateStart(
        settings: DownloadSettings,
        environment: DownloadEnvironment,
        storedBytes: Long,
        newFileBytes: Long?
    ): StartDecision {
        if (environment.network == NetworkKind.OFFLINE) {
            return StartDecision.Blocked(
                "You're offline — downloads will work again when " +
                    "you're back online."
            )
        }
        if (settings.wifiOnly && environment.network == NetworkKind.METERED) {
            return StartDecision.Blocked(
                "Downloads are set to Wi-Fi only, and you're on " +
                    "mobile data. Connect to Wi-Fi, or turn Wi-Fi only " +
                    "off in download settings."
            )
        }
        if (settings.pauseOnLowBattery &&
            isBatteryLow(environment.batteryPercent, environment.batteryCharging)
        ) {
            return StartDecision.Blocked(
                "Your battery is low, so downloads are paused. " +
                    "Charge the phone or try again later."
            )
        }
        val free = environment.freeBytes
        if (newFileBytes != null && free != null && newFileBytes > free) {
            return StartDecision.Blocked(
                "Not enough space on the phone for this download. " +
                    "Free up some space and try again."
            )
        }
        val cap = settings.storageCapBytes
        if (cap > 0) {
            val projected = storedBytes + (newFileBytes ?: 0L)
            if (newFileBytes != null && projected > cap) {
                return StartDecision.Blocked(
                    "This would go over your Watchly download limit. " +
                        "Delete a download or raise the limit in " +
                        "download settings."
                )
            }
            if (projected >= (cap * DownloadSettings.CAP_WARNING_FRACTION).toLong()) {
                return StartDecision.AllowedWithWarning(
                    "Heads up: Watchly downloads are close to your " +
                        "storage limit."
                )
            }
        }
        return StartDecision.Allowed
    }
}
