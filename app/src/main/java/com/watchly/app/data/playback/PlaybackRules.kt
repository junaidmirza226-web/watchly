/*
 * Watchly — pure playback rules (spec §5.1, §5.4, §5.5).
 *
 * No Android dependency: every rule the player and the tests share
 * lives here, so the behaviour is identical on the phone and in the
 * JVM test run. Resume follows the spec: a position between 5% and
 * 95% of the duration resumes; under 5% starts from the beginning;
 * over 95% counts as watched.
 */
package com.watchly.app.data.playback

object PlaybackRules {

    /** Seek step for the ±10s buttons and double-tap gestures. */
    const val SEEK_STEP_MS: Long = 10_000L

    /** Resume only inside this fraction window of the total duration. */
    const val RESUME_MIN_FRACTION: Double = 0.05
    const val RESUME_MAX_FRACTION: Double = 0.95

    /** At or past this fraction the title counts as finished. */
    const val FINISHED_FRACTION: Double = 0.95

    /** Speeds offered in the player (spec §5.5), always in this order. */
    val SPEEDS: List<Float> = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    /** True when playback should resume from [positionMs] instead of 0. */
    fun shouldResume(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0 || positionMs <= 0) return false
        val fraction = positionMs.toDouble() / durationMs.toDouble()
        return fraction > RESUME_MIN_FRACTION && fraction < RESUME_MAX_FRACTION
    }

    /** True when the title counts as finished/watched. */
    fun isFinished(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0) return false
        return positionMs.toDouble() / durationMs.toDouble() >= FINISHED_FRACTION
    }

    /** The position playback should start from (saved spot, or 0). */
    fun resumePosition(positionMs: Long, durationMs: Long): Long =
        if (shouldResume(positionMs, durationMs)) positionMs else 0L

    /** A seek target clamped inside [0, durationMs]. */
    fun clampSeek(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
        val target = positionMs + deltaMs
        return when {
            target < 0L -> 0L
            durationMs > 0 && target > durationMs -> durationMs
            else -> target
        }
    }

    /** "1:23:45" for long videos, "12:34" otherwise; "--:--" when unknown. */
    fun formatTime(positionMs: Long): String {
        if (positionMs < 0) return "--:--"
        val totalSeconds = positionMs / 1_000
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    /** "42 min left" / "1 h 12 min left" for the Continue Watching card. */
    fun timeLeftLabel(positionMs: Long, durationMs: Long): String {
        val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
        val totalMinutes = remainingMs / 60_000
        return if (totalMinutes >= 60) {
            "${totalMinutes / 60} h ${totalMinutes % 60} min left"
        } else {
            "$totalMinutes min left"
        }
    }

    /** Nearest offered speed to [speed] (used when restoring a saved speed). */
    fun nearestSpeed(speed: Float): Float =
        SPEEDS.minByOrNull { kotlin.math.abs(it - speed) } ?: 1.0f
}
