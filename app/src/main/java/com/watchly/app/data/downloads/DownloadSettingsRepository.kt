/*
 * Watchly — stored download settings (spec §7.3).
 *
 * The three rules from §7.3 that are the user's to change:
 * Wi-Fi only (on by default), pause on low battery, and the
 * storage cap for Watchly downloads (10 GB by default). They live
 * in SharedPreferences and are read as a pure [DownloadSettings]
 * snapshot, which [DownloadPolicy] evaluates. A Settings screen
 * edits these same keys; the rules work from the defaults until
 * the user ever opens it.
 */
package com.watchly.app.data.downloads

import android.content.Context

class DownloadSettingsRepository(context: Context) {

    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The current settings as one pure snapshot. */
    fun settings(): DownloadSettings = DownloadSettings(
        wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, true),
        pauseOnLowBattery = prefs.getBoolean(KEY_PAUSE_LOW_BATTERY, true),
        storageCapBytes = prefs.getLong(
            KEY_STORAGE_CAP_BYTES,
            DownloadSettings.DEFAULT_STORAGE_CAP_BYTES
        )
    )

    fun setWifiOnly(value: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()
    }

    fun setPauseOnLowBattery(value: Boolean) {
        prefs.edit().putBoolean(KEY_PAUSE_LOW_BATTERY, value).apply()
    }

    fun setStorageCapBytes(value: Long) {
        prefs.edit().putLong(KEY_STORAGE_CAP_BYTES, value).apply()
    }

    companion object {
        const val PREFS_NAME = "watchly_download_settings"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_PAUSE_LOW_BATTERY = "pause_on_low_battery"
        const val KEY_STORAGE_CAP_BYTES = "storage_cap_bytes"
    }
}
