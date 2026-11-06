package com.watchly.app.data.downloads

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-JVM tests for the DownloadManager code mapping. The integer
 * codes are the platform's stable constants (see DownloadRules).
 */
class DownloadRulesTest {

    @Test
    fun `maps platform status codes onto watchly statuses`() {
        assertEquals(DownloadStatus.QUEUED, DownloadRules.statusFromCode(1))
        assertEquals(DownloadStatus.RUNNING, DownloadRules.statusFromCode(2))
        assertEquals(DownloadStatus.PAUSED, DownloadRules.statusFromCode(4))
        assertEquals(DownloadStatus.COMPLETED, DownloadRules.statusFromCode(8))
        assertEquals(DownloadStatus.FAILED, DownloadRules.statusFromCode(16))
        // Unknown codes must not crash — treat as still queued.
        assertEquals(DownloadStatus.QUEUED, DownloadRules.statusFromCode(999))
    }

    @Test
    fun `active means queued running or paused`() {
        assertTrue(DownloadRules.isActive(DownloadStatus.QUEUED))
        assertTrue(DownloadRules.isActive(DownloadStatus.RUNNING))
        assertTrue(DownloadRules.isActive(DownloadStatus.PAUSED))
        assertFalse(DownloadRules.isActive(DownloadStatus.COMPLETED))
        assertFalse(DownloadRules.isActive(DownloadStatus.FAILED))
    }

    @Test
    fun `failure reasons are plain language`() {
        // §7.3: out-of-space mid-download is presented as a pause.
        assertTrue(DownloadRules.failureReason(1004).contains("space", ignoreCase = true))
        assertTrue(DownloadRules.failureReason(1004).contains("Paused"))
        assertTrue(DownloadRules.failureReason(1001).contains("save", ignoreCase = true))
        assertTrue(DownloadRules.failureReason(404).contains("404"))
        assertTrue(DownloadRules.failureReason(1000).isNotBlank())
    }

    @Test
    fun `progress fraction clamps and handles unknown totals`() {
        assertEquals(0.5f, DownloadRules.progressFraction(500, 1000))
        assertEquals(1f, DownloadRules.progressFraction(1500, 1000))
        assertEquals(0f, DownloadRules.progressFraction(-5, 1000))
        assertNull(DownloadRules.progressFraction(10, 0))
        assertNull(DownloadRules.progressFraction(10, -1))
    }
}
