/*
 * Watchly — the user's own ratings (spec §4.2, cycle 10).
 *
 * A personal 1–10 score per title, stored locally in its own
 * SharedPreferences file ("watchly_ratings": stable key -> int).
 * Purely the user's opinion — it is never sent anywhere, never
 * blended with provider ratings, and it travels with the backup
 * (§15.2) so a reinstall keeps it. 0 / absent means "not rated".
 * Per-profile since cycle 12 (§8): each profile rates in its own
 * file — the default profile's is the legacy [PREFS_NAME], later
 * profiles append their suffix.
 */
package com.watchly.app.data.library

import android.content.Context

class RatingsRepository(
    context: Context,
    profileId: String? = null
) {

    private val prefs = context.getSharedPreferences(
        PREFS_NAME + ProfilesRepository.fileSuffixFor(
            profileId ?: ProfilesRepository(context).activeProfileId()
        ),
        Context.MODE_PRIVATE
    )

    /** The user's rating for [stableKey], 1..10, or null. */
    fun ratingFor(stableKey: String): Int? =
        prefs.getInt(stableKey, 0).takeIf { it in MIN_RATING..MAX_RATING }

    /**
     * Sets the rating for [stableKey]; null (or an out-of-range
     * value) clears it. Out-of-range numbers are treated as a
     * clear rather than stored — a corrupt value must never
     * render as a score.
     */
    fun setRating(stableKey: String, rating: Int?) {
        val editor = prefs.edit()
        if (rating == null || rating !in MIN_RATING..MAX_RATING) {
            editor.remove(stableKey)
        } else {
            editor.putInt(stableKey, rating)
        }
        editor.apply()
    }

    companion object {
        const val PREFS_NAME = "watchly_ratings"
        const val MIN_RATING = 1
        const val MAX_RATING = 10
    }
}
