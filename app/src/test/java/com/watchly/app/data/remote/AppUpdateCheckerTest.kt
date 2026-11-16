package com.watchly.app.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The in-app updater's comparison + asset picking. Per-build versions
 * ("1.5.1.37") must compare strictly newer than the installed one so every
 * CI build triggers the update banner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdateCheckerTest {

    @Test
    fun isNewerVersionComparesSemverCorrectly() {
        // Minor bump
        assertTrue(AppUpdateChecker.isNewerVersion(current = "1.4.2", remote = "1.5.0"))
        // Major bump
        assertTrue(AppUpdateChecker.isNewerVersion(current = "1.4.2", remote = "2.0.0"))
        // Patch bump
        assertTrue(AppUpdateChecker.isNewerVersion(current = "1.4.2", remote = "1.4.3"))
        // Prefixed with 'v'
        assertTrue(AppUpdateChecker.isNewerVersion(current = "v1.4.2", remote = "v1.4.10"))
        assertTrue(AppUpdateChecker.isNewerVersion(current = "1.4.2", remote = "v1.5.0"))
        // Equal version
        assertFalse(AppUpdateChecker.isNewerVersion(current = "1.4.2", remote = "1.4.2"))
        assertFalse(AppUpdateChecker.isNewerVersion(current = "v1.4.2", remote = "1.4.2"))
        // Older remote
        assertFalse(AppUpdateChecker.isNewerVersion(current = "1.4.2", remote = "1.4.1"))
        assertFalse(AppUpdateChecker.isNewerVersion(current = "2.0.0", remote = "1.9.9"))
    }

    @Test
    fun perBuildVersionsCompareNumerically() {
        // Per-build versions (1.5.1.<run>) — every CI build is strictly newer.
        assertTrue(AppUpdateChecker.isNewerVersion("1.5.1", "1.5.1.37"))
        assertTrue(AppUpdateChecker.isNewerVersion("1.5.1.36", "1.5.1.37"))
        assertFalse(AppUpdateChecker.isNewerVersion("1.5.1.37", "1.5.1.37"))
        assertFalse(AppUpdateChecker.isNewerVersion("1.5.1.38", "1.5.1.37"))
        assertTrue(AppUpdateChecker.isNewerVersion("1.5.1.37", "1.6.0"))
    }

    @Test
    fun apkAssetIsPickedPreferringWatchlyApk() {
        val json = JSONObject(
            """
            {"assets": [
              {"name": "notes.txt", "browser_download_url": "https://x/notes.txt"},
              {"name": "watchly.apk", "browser_download_url": "https://x/watchly.apk"},
              {"name": "other.apk", "browser_download_url": "https://x/other.apk"}
            ]}
            """.trimIndent()
        )
        assertEquals("https://x/watchly.apk", AppUpdateChecker.pickApkAsset(json))
    }

    @Test
    fun apkAssetFallsBackToAnyApkAndNullWhenNone() {
        val any = JSONObject(
            """
            {"assets": [
              {"name": "other.apk", "browser_download_url": "https://x/other.apk"}
            ]}
            """.trimIndent()
        )
        assertEquals("https://x/other.apk", AppUpdateChecker.pickApkAsset(any))
        assertNull(AppUpdateChecker.pickApkAsset(JSONObject("""{"assets": []}""")))
        assertNull(AppUpdateChecker.pickApkAsset(JSONObject("{}")))
    }

    @Test
    fun newestVersionPicksTheNumericallyNewestTag() {
        // Ordering in the list must not matter — newest wins even when
        // GitHub serves releases in a shuffled order.
        assertEquals(
            "1.5.1.74",
            AppUpdateChecker.newestVersion(listOf("v1.5.1.72", "v1.5.1.74", "v1.5.1.70"))
        )
        assertEquals(
            "1.5.1.74",
            AppUpdateChecker.newestVersion(listOf("v1.5.1.74", "1.5.1.7", "1.5.1"))
        )
        assertEquals("1.6.0", AppUpdateChecker.newestVersion(listOf("v1.5.1.74", "v1.6.0")))
        // Garbage tags are ignored entirely.
        assertEquals(
            "1.5.1.71",
            AppUpdateChecker.newestVersion(listOf("nightly", "v1.5.1.71", "", "v0.0.1-broken"))
        )
        assertNull(AppUpdateChecker.newestVersion(emptyList()))
    }
}
