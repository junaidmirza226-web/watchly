package com.watchly.app.data.downloads

import com.watchly.app.data.model.PlayableFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-JVM tests for the download start policy (spec §7.1 quality
 * choice + §7.3 download rules). No Android types involved — the
 * platform facts arrive as a [DownloadEnvironment] snapshot.
 */
class DownloadPolicyTest {

    private fun file(name: String, size: Long?, format: String? = null) =
        PlayableFile(
            url = "https://archive.org/download/x/$name",
            fileName = name,
            format = format,
            sizeBytes = size,
            qualityLabel = format
        )

    private fun env(
        network: NetworkKind = NetworkKind.WIFI,
        battery: Int? = 80,
        charging: Boolean = false,
        free: Long? = 50_000_000_000L
    ) = DownloadEnvironment(network, battery, charging, free)

    // -----------------------------------------------------------------
    // Quality choice (§7.1)
    // -----------------------------------------------------------------

    @Test
    fun `quality options are largest first with duplicates removed`() {
        val options = DownloadPolicy.qualityOptions(
            listOf(
                file("small.mp4", 100),
                file("big.mp4", 900),
                file("big.mp4", 900), // same file listed twice
                file("mid.mp4", 400)
            )
        )
        assertEquals(
            listOf("big.mp4", "mid.mp4", "small.mp4"),
            options.map { it.fileName }
        )
    }

    @Test
    fun `a choice is only needed when more than one quality exists`() {
        assertFalse(DownloadPolicy.needsQualityChoice(emptyList()))
        assertFalse(DownloadPolicy.needsQualityChoice(listOf(file("one.mp4", 10))))
        assertTrue(
            DownloadPolicy.needsQualityChoice(
                listOf(file("a.mp4", 10), file("b.mp4", 20))
            )
        )
    }

    @Test
    fun `quality label falls back quality then format then file name`() {
        assertEquals("h.264", DownloadPolicy.qualityLabel(file("a.mp4", 1, "h.264")))
        val bare = PlayableFile(url = "u", fileName = "bare.webm")
        assertEquals("bare.webm", DownloadPolicy.qualityLabel(bare))
    }

    // -----------------------------------------------------------------
    // Network + battery primitives (§7.3)
    // -----------------------------------------------------------------

    @Test
    fun `network kind classification`() {
        assertEquals(
            NetworkKind.OFFLINE,
            DownloadPolicy.networkKind(connected = false, onWifiTransport = true)
        )
        assertEquals(
            NetworkKind.WIFI,
            DownloadPolicy.networkKind(connected = true, onWifiTransport = true)
        )
        assertEquals(
            NetworkKind.METERED,
            DownloadPolicy.networkKind(connected = true, onWifiTransport = false)
        )
    }

    @Test
    fun `battery is low only at the threshold and not charging`() {
        assertTrue(DownloadPolicy.isBatteryLow(15, charging = false))
        assertTrue(DownloadPolicy.isBatteryLow(3, charging = false))
        assertFalse(DownloadPolicy.isBatteryLow(16, charging = false))
        assertFalse(DownloadPolicy.isBatteryLow(10, charging = true))
        // Unknown battery state must never block a download.
        assertFalse(DownloadPolicy.isBatteryLow(null, charging = false))
    }

    // -----------------------------------------------------------------
    // evaluateStart (§7.3)
    // -----------------------------------------------------------------

    @Test
    fun `a normal start on wifi is simply allowed`() {
        val decision = DownloadPolicy.evaluateStart(
            DownloadSettings(), env(), storedBytes = 0, newFileBytes = 1_000
        )
        assertEquals(StartDecision.Allowed, decision)
    }

    @Test
    fun `offline blocks with a plain message`() {
        val decision = DownloadPolicy.evaluateStart(
            DownloadSettings(), env(network = NetworkKind.OFFLINE),
            storedBytes = 0, newFileBytes = 1_000
        )
        assertTrue(decision is StartDecision.Blocked)
        assertTrue((decision as StartDecision.Blocked).message.contains("offline"))
    }

    @Test
    fun `wifi only blocks mobile data but the setting can allow it`() {
        val blocked = DownloadPolicy.evaluateStart(
            DownloadSettings(wifiOnly = true), env(network = NetworkKind.METERED),
            storedBytes = 0, newFileBytes = 1_000
        )
        assertTrue(blocked is StartDecision.Blocked)
        assertTrue((blocked as StartDecision.Blocked).message.contains("Wi-Fi only"))

        val allowed = DownloadPolicy.evaluateStart(
            DownloadSettings(wifiOnly = false), env(network = NetworkKind.METERED),
            storedBytes = 0, newFileBytes = 1_000
        )
        assertEquals(StartDecision.Allowed, allowed)
    }

    @Test
    fun `low battery blocks only when the setting is on`() {
        val blocked = DownloadPolicy.evaluateStart(
            DownloadSettings(pauseOnLowBattery = true),
            env(battery = 10, charging = false),
            storedBytes = 0, newFileBytes = 1_000
        )
        assertTrue(blocked is StartDecision.Blocked)
        assertTrue((blocked as StartDecision.Blocked).message.contains("battery"))

        val allowed = DownloadPolicy.evaluateStart(
            DownloadSettings(pauseOnLowBattery = false),
            env(battery = 10, charging = false),
            storedBytes = 0, newFileBytes = 1_000
        )
        assertEquals(StartDecision.Allowed, allowed)

        // Charging phones are never "low battery" for this rule.
        val charging = DownloadPolicy.evaluateStart(
            DownloadSettings(pauseOnLowBattery = true),
            env(battery = 5, charging = true),
            storedBytes = 0, newFileBytes = 1_000
        )
        assertEquals(StartDecision.Allowed, charging)
    }

    @Test
    fun `a file bigger than the free space is refused up front`() {
        val decision = DownloadPolicy.evaluateStart(
            DownloadSettings(), env(free = 500),
            storedBytes = 0, newFileBytes = 1_000
        )
        assertTrue(decision is StartDecision.Blocked)
        assertTrue((decision as StartDecision.Blocked).message.contains("space"))
    }

    @Test
    fun `crossing the storage cap blocks, crossing ninety percent warns`() {
        val settings = DownloadSettings(storageCapBytes = 10_000)

        val blocked = DownloadPolicy.evaluateStart(
            settings, env(), storedBytes = 9_500, newFileBytes = 1_000
        )
        assertTrue(blocked is StartDecision.Blocked)
        assertTrue((blocked as StartDecision.Blocked).message.contains("limit"))

        val warned = DownloadPolicy.evaluateStart(
            settings, env(), storedBytes = 8_500, newFileBytes = 1_000
        )
        assertTrue(warned is StartDecision.AllowedWithWarning)

        val fine = DownloadPolicy.evaluateStart(
            settings, env(), storedBytes = 1_000, newFileBytes = 1_000
        )
        assertEquals(StartDecision.Allowed, fine)
    }

    @Test
    fun `unknown file size never blocks on space or cap`() {
        val decision = DownloadPolicy.evaluateStart(
            DownloadSettings(storageCapBytes = 100), env(free = 1),
            storedBytes = 50, newFileBytes = null
        )
        // 50 stored is below 90% of the 100 cap, and an unknown-size
        // file can't be judged — it starts.
        assertEquals(StartDecision.Allowed, decision)
    }
}
