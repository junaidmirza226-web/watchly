/*
 * Watchly — saved playback progress + speed (spec §5.4, §5.5, §10.1).
 *
 * Stored in SharedPreferences as one small JSON object per title
 * (stable key -> position, duration, title snapshot, updated time).
 * Everything here works fully offline; nothing is sent anywhere.
 * The recent-progress list is what the Continue Watching row (§10)
 * reads in the next cycle — it is recorded from the first playback,
 * so no history is lost while that screen is still being built.
 */
package com.watchly.app.data.playback

import android.content.Context
import android.content.SharedPreferences
import com.watchly.app.data.model.MediaType
import org.json.JSONObject

/** One saved resume point, enough to render a Continue Watching card. */
data class PlaybackProgress(
    val key: String,
    val titleName: String,
    val posterUrl: String?,
    val mediaType: MediaType,
    val episodeLabel: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long
) {
    /** Fraction watched, 0..1 (0 when the duration is unknown). */
    val fraction: Float
        get() = if (durationMs > 0) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

class PlaybackRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Saves (or refreshes) the resume point for one title. */
    fun saveProgress(progress: PlaybackProgress) {
        prefs.edit()
            .putString(KEY_PREFIX + progress.key, toJson(progress))
            .apply()
    }

    /** The saved resume point for a title, or null when never played. */
    fun getProgress(key: String): PlaybackProgress? {
        val raw = prefs.getString(KEY_PREFIX + key, null) ?: return null
        return runCatching { fromJson(key, JSONObject(raw)) }.getOrNull()
    }

    /** Drops the resume point (title finished, or user removed it). */
    fun clearProgress(key: String) {
        prefs.edit().remove(KEY_PREFIX + key).apply()
    }

    /**
     * Most recently played titles, newest first — the Continue Watching
     * list. Entries past 95% are finished and are not returned.
     */
    fun getRecentProgress(limit: Int = 20): List<PlaybackProgress> {
        return prefs.all.entries
            .filter { it.key.startsWith(KEY_PREFIX) }
            .mapNotNull { (prefKey, value) ->
                val key = prefKey.removePrefix(KEY_PREFIX)
                (value as? String)?.let { raw ->
                    runCatching { fromJson(key, JSONObject(raw)) }.getOrNull()
                }
            }
            .filter { !PlaybackRules.isFinished(it.positionMs, it.durationMs) }
            .sortedByDescending { it.updatedAtMs }
            .take(limit)
    }

    /** The last speed the user picked (spec §5.5: remembered per profile). */
    fun getSpeed(): Float =
        PlaybackRules.nearestSpeed(prefs.getFloat(KEY_SPEED, 1.0f))

    fun saveSpeed(speed: Float) {
        prefs.edit().putFloat(KEY_SPEED, PlaybackRules.nearestSpeed(speed)).apply()
    }

    private fun toJson(p: PlaybackProgress): String =
        JSONObject()
            .put("name", p.titleName)
            .put("poster", p.posterUrl ?: JSONObject.NULL)
            .put("type", p.mediaType.name)
            .put("episode", p.episodeLabel ?: JSONObject.NULL)
            .put("pos", p.positionMs)
            .put("dur", p.durationMs)
            .put("at", p.updatedAtMs)
            .toString()

    private fun fromJson(key: String, obj: JSONObject): PlaybackProgress =
        PlaybackProgress(
            key = key,
            titleName = obj.optString("name").takeIf { it.isNotBlank() } ?: "Unknown title",
            posterUrl = obj.optString("poster").takeIf { it.isNotBlank() && it != "null" },
            mediaType = runCatching {
                MediaType.valueOf(obj.optString("type"))
            }.getOrElse { MediaType.MOVIE },
            episodeLabel = obj.optString("episode").takeIf { it.isNotBlank() && it != "null" },
            positionMs = obj.optLong("pos"),
            durationMs = obj.optLong("dur"),
            updatedAtMs = obj.optLong("at")
        )

    companion object {
        const val PREFS_NAME = "watchly_playback"
        const val KEY_PREFIX = "progress:"
        const val KEY_SPEED = "speed"
    }
}
