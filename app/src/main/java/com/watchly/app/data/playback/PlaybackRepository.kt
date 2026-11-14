/*
 * Watchly — saved playback progress + speed (spec §5.4, §5.5, §10.1).
 *
 * Stored in SharedPreferences as one small JSON object per title
 * (stable key -> position, duration, title snapshot, updated time).
 * Everything here works fully offline; nothing is sent anywhere.
 * Per-profile since cycle 12 (§8): each profile has its own file —
 * the default profile's is the legacy [PREFS_NAME], later profiles
 * append their suffix — so progress, speed and the per-title
 * player choices below are each profile's own.
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

class PlaybackRepository(
    context: Context,
    profileId: String? = null
) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME +
            com.watchly.app.data.library.ProfilesRepository.fileSuffixFor(
                profileId
                    ?: com.watchly.app.data.library.ProfilesRepository(context)
                        .activeProfileId()
            ),
        Context.MODE_PRIVATE
    )

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

    // ---------------------------------------------------------------
    // Cycle 9 player pack: per title/episode remembered choices.
    // Keys are the caller's scoped key (title stable key + episode
    // label), so a series remembers per episode, a movie per movie.
    // ---------------------------------------------------------------

    /**
     * The file URL the user last picked in the player's Quality
     * menu for this title/episode (spec §5.7: the choice is
     * remembered and re-applied next time).
     */
    fun getQualityUrl(key: String): String? =
        prefs.getString(KEY_QUALITY_PREFIX + key, null)?.takeIf { it.isNotBlank() }

    fun saveQualityUrl(key: String, url: String) {
        prefs.edit().putString(KEY_QUALITY_PREFIX + key, url).apply()
    }

    /**
     * The audio language the user last picked for this
     * title/episode (spec §5.6). A language, not a track id,
     * because ids are per-file while the language carries over.
     */
    fun getAudioLanguage(key: String): String? =
        prefs.getString(KEY_AUDIO_PREFIX + key, null)?.takeIf { it.isNotBlank() }

    fun saveAudioLanguage(key: String, language: String) {
        prefs.edit().putString(KEY_AUDIO_PREFIX + key, language).apply()
    }

    /** Clears the remembered audio language (back to the file's default). */
    fun clearAudioLanguage(key: String) {
        prefs.edit().remove(KEY_AUDIO_PREFIX + key).apply()
    }

    /** The saved subtitle sync offset for this title/episode (§6.3). */
    fun getSubtitleOffsetMs(key: String): Long =
        SubtitleShifter.clampOffset(
            prefs.getLong(KEY_SUBOFFSET_PREFIX + key, 0L)
        )

    fun saveSubtitleOffsetMs(key: String, offsetMs: Long) {
        prefs.edit()
            .putLong(KEY_SUBOFFSET_PREFIX + key, SubtitleShifter.clampOffset(offsetMs))
            .apply()
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
        const val KEY_QUALITY_PREFIX = "quality:"
        const val KEY_AUDIO_PREFIX = "audio:"
        const val KEY_SUBOFFSET_PREFIX = "suboffset:"
    }
}
