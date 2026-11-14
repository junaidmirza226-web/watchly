package com.watchly.app.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {

    @Test
    fun `isNewerVersion compares semver correctly`() {
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
}
