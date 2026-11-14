/*
 * Watchly — small persisted UI preferences (Phase D, cycle 5).
 *
 * The playability filter (All / Playable / Info only), shared by
 * Browse, Search and Home, and — cycle 5 — each Browse tab's
 * selected genre, persisted like the other settings so the choices
 * survive restarts. Kept separate from the key stores: this is
 * presentation state, not secrets.
 */
package com.watchly.app.data.settings

import android.content.Context
import com.watchly.app.data.model.PlayabilityFilter
import com.watchly.app.data.model.SubtitleBackground
import com.watchly.app.data.model.SubtitleStyle

class UiPreferencesRepository(context: Context) {

    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The saved playability filter; ALL when never set. */
    fun playabilityFilter(): PlayabilityFilter =
        runCatching {
            PlayabilityFilter.valueOf(
                prefs.getString(KEY_PLAYABILITY_FILTER, null) ?: ""
            )
        }.getOrElse { PlayabilityFilter.ALL }

    /** Persists the playability filter. */
    fun setPlayabilityFilter(filter: PlayabilityFilter) {
        prefs.edit().putString(KEY_PLAYABILITY_FILTER, filter.name).apply()
    }

    /**
     * The saved genre for one Browse tab ([scope] is the tab's
     * stable name: "movies" / "shows" / "anime"), or null when the
     * tab is on "All genres". Genres are per-tab because each
     * source speaks its own genre vocabulary.
     */
    fun genreFor(scope: String): String? =
        prefs.getString(genreKey(scope), null)?.takeIf { it.isNotBlank() }

    /** Persists one Browse tab's genre (null clears it). */
    fun setGenreFor(scope: String, genre: String?) {
        val editor = prefs.edit()
        if (genre.isNullOrBlank()) {
            editor.remove(genreKey(scope))
        } else {
            editor.putString(genreKey(scope), genre)
        }
        editor.apply()
    }

    private fun genreKey(scope: String): String = "genre_$scope"

    /**
     * Autoplay-next (cycle 5): when an episode/item finishes, offer
     * and start the next playable one after a countdown. ON by
     * default for episodes, per the feature brief.
     */
    fun autoplayNext(): Boolean =
        prefs.getBoolean(KEY_AUTOPLAY_NEXT, true)

    /** Persists the autoplay-next toggle. */
    fun setAutoplayNext(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOPLAY_NEXT, enabled).apply()
    }

    /**
     * Subtitle appearance (cycle 9, spec §6.2): text size + background,
     * applied live in the player and kept across sessions. Unknown or
     * out-of-range saved values fall back to the defaults — a corrupt
     * preference can never break subtitle rendering.
     */
    fun subtitleStyle(): SubtitleStyle {
        val size = prefs.getFloat(KEY_SUBTITLE_TEXT_SIZE, SubtitleStyle.DEFAULT_TEXT_SIZE_SP)
        val background = runCatching {
            SubtitleBackground.valueOf(
                prefs.getString(KEY_SUBTITLE_BACKGROUND, null) ?: ""
            )
        }.getOrElse { SubtitleBackground.SEMI }
        return SubtitleStyle(
            textSizeSp = if (size in SubtitleStyle.TEXT_SIZE_STEPS_SP) size
            else SubtitleStyle.DEFAULT_TEXT_SIZE_SP,
            background = background
        )
    }

    /** Persists the subtitle text size (snapped to the §6.2 steps). */
    fun setSubtitleTextSizeSp(sizeSp: Float) {
        val snapped = SubtitleStyle.TEXT_SIZE_STEPS_SP.minByOrNull {
            kotlin.math.abs(it - sizeSp)
        } ?: SubtitleStyle.DEFAULT_TEXT_SIZE_SP
        prefs.edit().putFloat(KEY_SUBTITLE_TEXT_SIZE, snapped).apply()
    }

    /** Persists the subtitle background choice. */
    fun setSubtitleBackground(background: SubtitleBackground) {
        prefs.edit().putString(KEY_SUBTITLE_BACKGROUND, background.name).apply()
    }

    /**
     * Data Saver (cycle 10, spec §14.1): when ON, image loads are
     * downscaled at the Coil layer (see WatchlyApplication's
     * interceptor + DataSaverImagePolicy) and downloads start with
     * the efficient file instead of asking. OFF by default — full
     * quality is the normal Watchly experience.
     */
    fun dataSaverEnabled(): Boolean =
        prefs.getBoolean(KEY_DATA_SAVER, false)

    /** Persists the Data Saver toggle. */
    fun setDataSaverEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DATA_SAVER, enabled).apply()
    }

    companion object {
        const val PREFS_NAME = "watchly_ui_prefs"
        private const val KEY_PLAYABILITY_FILTER = "playability_filter"
        private const val KEY_AUTOPLAY_NEXT = "autoplay_next"
        private const val KEY_SUBTITLE_TEXT_SIZE = "subtitle_text_size_sp"
        private const val KEY_SUBTITLE_BACKGROUND = "subtitle_background"
        private const val KEY_DATA_SAVER = "data_saver"
    }
}
