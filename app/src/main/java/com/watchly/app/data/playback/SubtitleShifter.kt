/*
 * Watchly — subtitle sync shifting (cycle 9, spec §6.3).
 *
 * Media3 has no "subtitle delay" knob for sidecar files, so a sync
 * offset is applied the honest way: the downloaded subtitle file's
 * cue timestamps are rewritten, shifted by the offset, and the
 * shifted copy is what gets attached. The original cached file is
 * never touched, so every shift starts from the true timings and
 * offsets never compound.
 *
 * Pure text transforms (no Android types) so the JVM suite can pin
 * the behaviour exactly: SRT comma-millis and both VTT shapes
 * (with and without the hours field), clamped at zero — a cue can
 * never start before the video does.
 */
package com.watchly.app.data.playback

object SubtitleShifter {

    /** Spec §6.3 bounds: −5.0s … +5.0s. */
    const val MIN_OFFSET_MS: Long = -5_000L
    const val MAX_OFFSET_MS: Long = 5_000L

    /** Quick-step sizes offered in the player (§6.3). */
    const val FINE_STEP_MS: Long = 100L
    const val QUICK_STEP_MS: Long = 500L

    /** Clamps a requested offset into the supported range. */
    fun clampOffset(offsetMs: Long): Long =
        offsetMs.coerceIn(MIN_OFFSET_MS, MAX_OFFSET_MS)

    /** Shifts every cue time in an SRT document by [offsetMs]. */
    fun shiftSrt(content: String, offsetMs: Long): String =
        if (offsetMs == 0L) content else shift(content, offsetMs, srt = true)

    /** Shifts every cue time in a WebVTT document by [offsetMs]. */
    fun shiftVtt(content: String, offsetMs: Long): String =
        if (offsetMs == 0L) content else shift(content, offsetMs, srt = false)

    private fun shift(content: String, offsetMs: Long, srt: Boolean): String {
        val pattern = if (srt) SRT_TIMECODE else VTT_TIMECODE
        // Only cue timing lines (they carry "-->") are rewritten —
        // a timecode-shaped string inside dialogue is dialogue.
        return content.split("\n").joinToString("\n") { line ->
            if ("-->" in line) shiftLine(line, pattern, offsetMs, srt) else line
        }
    }

    private fun shiftLine(
        line: String,
        pattern: Regex,
        offsetMs: Long,
        srt: Boolean
    ): String =
        pattern.replace(line) { match ->
            val groups = match.groupValues
            // Both patterns end with the millis group; the VTT
            // short form has no hours group (index 1 empty).
            val hasHours = groups[1].isNotEmpty()
            val hours = if (hasHours) groups[1].toLong() else 0L
            val minutes = groups[2].toLong()
            val seconds = groups[3].toLong()
            val millis = groups[4].toLong()
            val shifted = (hours * 3_600_000L + minutes * 60_000L +
                seconds * 1_000L + millis + offsetMs).coerceAtLeast(0L)
            format(shifted, hasHours, srt)
        }

    private fun format(totalMs: Long, withHours: Boolean, srt: Boolean): String {
        val millis = totalMs % 1_000L
        val totalSeconds = totalMs / 1_000L
        val seconds = totalSeconds % 60L
        val totalMinutes = totalSeconds / 60L
        val minutes = totalMinutes % 60L
        val hours = totalMinutes / 60L
        val sep = if (srt) "," else "."
        return if (withHours) {
            "%02d:%02d:%02d%s%03d".format(hours, minutes, seconds, sep, millis)
        } else {
            "%02d:%02d%s%03d".format(totalMinutes, seconds, sep, millis)
        }
    }

    // SRT: 00:01:02,500 — hours always present, comma separator.
    private val SRT_TIMECODE = Regex("""(\d{2}):(\d{2}):(\d{2}),(\d{3})""")

    // VTT: 00:01:02.500 or the short 01:02.500 — dot separator.
    private val VTT_TIMECODE = Regex("""(?:(\d{2}):)?(\d{2}):(\d{2})\.(\d{3})""")
}
