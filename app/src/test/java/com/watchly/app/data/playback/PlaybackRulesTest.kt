package com.watchly.app.data.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaybackRulesTest {

    @Test
    fun `resume only between 5 and 95 percent`() {
        val duration = 3_600_000L
        assertFalse(PlaybackRules.shouldResume(0L, duration))
        assertFalse(PlaybackRules.shouldResume(108_000L, duration)) // 3%
        assertFalse(PlaybackRules.shouldResume(180_000L, duration)) // exactly 5%
        assertTrue(PlaybackRules.shouldResume(181_000L, duration))
        assertTrue(PlaybackRules.shouldResume(1_800_000L, duration)) // 50%
        assertTrue(PlaybackRules.shouldResume(3_419_000L, duration)) // just under 95%
        assertFalse(PlaybackRules.shouldResume(3_420_000L, duration)) // exactly 95%
        assertFalse(PlaybackRules.shouldResume(3_600_000L, duration))
    }

    @Test
    fun `resume needs a known duration`() {
        assertFalse(PlaybackRules.shouldResume(1_000L, 0L))
        assertFalse(PlaybackRules.shouldResume(1_000L, -1L))
    }

    @Test
    fun `finished at 95 percent and above`() {
        val duration = 3_600_000L
        assertFalse(PlaybackRules.isFinished(3_419_000L, duration))
        assertTrue(PlaybackRules.isFinished(3_420_000L, duration))
        assertTrue(PlaybackRules.isFinished(3_600_000L, duration))
        assertFalse(PlaybackRules.isFinished(1_000L, 0L))
    }

    @Test
    fun `resume position is the saved spot or zero`() {
        assertEquals(1_800_000L, PlaybackRules.resumePosition(1_800_000L, 3_600_000L))
        assertEquals(0L, PlaybackRules.resumePosition(108_000L, 3_600_000L))
        assertEquals(0L, PlaybackRules.resumePosition(3_500_000L, 3_600_000L))
    }

    @Test
    fun `seek clamps inside the duration`() {
        assertEquals(0L, PlaybackRules.clampSeek(5_000L, -10_000L, 3_600_000L))
        assertEquals(3_600_000L, PlaybackRules.clampSeek(3_595_000L, 10_000L, 3_600_000L))
        assertEquals(15_000L, PlaybackRules.clampSeek(5_000L, 10_000L, 3_600_000L))
        assertEquals(0L, PlaybackRules.clampSeek(5_000L, -10_000L, 0L))
    }

    @Test
    fun `format time shows hours only when needed`() {
        assertEquals("0:00", PlaybackRules.formatTime(0L))
        assertEquals("1:05", PlaybackRules.formatTime(65_000L))
        assertEquals("12:34", PlaybackRules.formatTime(754_000L))
        assertEquals("1:01:01", PlaybackRules.formatTime(3_661_000L))
        assertEquals("--:--", PlaybackRules.formatTime(-1L))
    }

    @Test
    fun `time left label switches to hours past an hour`() {
        assertEquals("42 min left", PlaybackRules.timeLeftLabel(1_800_000L, 4_320_000L))
        assertEquals("1 h 12 min left", PlaybackRules.timeLeftLabel(0L, 4_320_000L))
        assertEquals("0 min left", PlaybackRules.timeLeftLabel(3_600_000L, 3_600_000L))
    }

    @Test
    fun `nearest speed snaps to an offered speed`() {
        assertEquals(1.25f, PlaybackRules.nearestSpeed(1.3f))
        assertEquals(2.0f, PlaybackRules.nearestSpeed(2.5f))
        assertEquals(1.0f, PlaybackRules.nearestSpeed(1.0f))
        assertEquals(PlaybackRules.SPEEDS, PlaybackRules.SPEEDS)
    }
}
