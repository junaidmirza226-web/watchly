package com.watchly.app.data.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Cycle 9 (§6.3): subtitle sync shifting. SRT comma-millis and
 * both VTT shapes, clamped at zero, dialogue untouched.
 */
class SubtitleShifterTest {

    private val srt = """
        1
        00:00:01,000 --> 00:00:03,500
        Hello there.

        2
        00:00:04,000 --> 00:00:06,000
        General Kenobi.
    """.trimIndent()

    @Test
    fun `srt shift forward moves both cue ends`() {
        val shifted = SubtitleShifter.shiftSrt(srt, 500)
        assertEquals(true, shifted.contains("00:00:01,500 --> 00:00:04,000"))
        assertEquals(true, shifted.contains("00:00:04,500 --> 00:00:06,500"))
    }

    @Test
    fun `srt shift backward clamps at zero`() {
        val shifted = SubtitleShifter.shiftSrt(srt, -2_000)
        // First cue would start at -1s: clamped to 0, end at 1.5s.
        assertEquals(true, shifted.contains("00:00:00,000 --> 00:00:01,500"))
        assertEquals(true, shifted.contains("00:00:02,000 --> 00:00:04,000"))
    }

    @Test
    fun `zero offset returns the input unchanged`() {
        assertEquals(srt, SubtitleShifter.shiftSrt(srt, 0))
    }

    @Test
    fun `dialogue lines are never rewritten`() {
        val tricky = """
            1
            00:00:10,000 --> 00:00:12,000
            It happened at 00:11:22,333 exactly.
        """.trimIndent()
        val shifted = SubtitleShifter.shiftSrt(tricky, 1_000)
        assertEquals(true, shifted.contains("00:00:11,000 --> 00:00:13,000"))
        // The timecode inside the spoken line stays as written.
        assertEquals(true, shifted.contains("It happened at 00:11:22,333 exactly."))
    }

    @Test
    fun `vtt long form shifts with dot separator`() {
        val vtt = """
            WEBVTT

            00:00:01.000 --> 00:00:03.500
            Hello there.
        """.trimIndent()
        val shifted = SubtitleShifter.shiftVtt(vtt, 250)
        assertEquals(true, shifted.contains("00:00:01.250 --> 00:00:03.750"))
    }

    @Test
    fun `vtt short form (no hours) shifts and keeps its shape`() {
        val vtt = """
            WEBVTT

            01:02.500 --> 01:04.000
            Short form cue.
        """.trimIndent()
        val shifted = SubtitleShifter.shiftVtt(vtt, 1_000)
        assertEquals(true, shifted.contains("01:03.500 --> 01:05.000"))
    }

    @Test
    fun `clamp keeps offsets inside the spec range`() {
        assertEquals(5_000L, SubtitleShifter.clampOffset(99_000L))
        assertEquals(-5_000L, SubtitleShifter.clampOffset(-99_000L))
        assertEquals(300L, SubtitleShifter.clampOffset(300L))
    }
}
