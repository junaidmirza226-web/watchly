/*
 * Watchly — local profiles (spec §8, cycle 12).
 *
 * Up to [MAX_PROFILES] people share one phone, each with their
 * OWN library: watchlist, custom lists, watched state, continue-
 * watching progress, personal ratings and per-title player
 * choices. Downloads (they are files on the device) and API keys
 * (they are the phone owner's accounts) stay GLOBAL — the
 * Library's Profiles card says exactly that.
 *
 * Storage: the profile list + the active profile id live in this
 * file ("watchly_profiles"). Each profile's data lives in its own
 * SharedPreferences files, derived from the shared bases:
 *   watchly_library<suffix>, watchly_playback<suffix>,
 *   watchly_ratings<suffix>.
 *
 * MIGRATION (zero loss, by construction): the FIRST profile —
 * "Profile 1", created lazily the first time anything asks — has
 * suffix "", i.e. it uses the exact files every Watchly up to
 * v1.3.0 already wrote. Nothing is copied or moved; the legacy
 * data simply becomes Profile 1's data the moment the metadata
 * exists. Profiles created later get a suffix from their id, so
 * their files start empty and can never touch another profile's.
 */
package com.watchly.app.data.library

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One local profile: a name and an avatar colour. */
data class WatchlyProfile(
    val id: String,
    val name: String,
    val colorIndex: Int
) {
    /**
     * Suffix on this profile's data files: "" for the default
     * profile (the legacy files), "_<id head>" for later ones.
     */
    val fileSuffix: String get() = ProfilesRepository.fileSuffixFor(id)

    /** Avatar colour as an ARGB hex string ("#D4AF37"). */
    val colorHex: String
        get() = ProfilesRepository.AVATAR_COLOR_HEX[
            colorIndex.coerceIn(ProfilesRepository.AVATAR_COLOR_HEX.indices)
        ]

    /** Spoken name of the avatar colour ("Gold"). */
    val colorName: String
        get() = ProfilesRepository.AVATAR_COLOR_NAMES[
            colorIndex.coerceIn(ProfilesRepository.AVATAR_COLOR_NAMES.indices)
        ]

    /** Monogram letter: the first letter of the name. */
    val monogram: String
        get() = name.trim().firstOrNull()?.uppercase() ?: "?"
}

class ProfilesRepository(context: Context) {

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // Reads (every one migrates first — see the file header)
    // ------------------------------------------------------------------

    /** All profiles, in creation order. Never empty. */
    fun profiles(): List<WatchlyProfile> {
        ensureMigrated()
        return readProfiles()
    }

    /** The profile the app is currently showing data for. */
    fun activeProfile(): WatchlyProfile {
        ensureMigrated()
        val stored = readProfiles()
        val activeId = prefs.getString(KEY_ACTIVE, null)
        return stored.firstOrNull { it.id == activeId } ?: stored.first()
    }

    /** The active profile's id (the scoped stores' entry point). */
    fun activeProfileId(): String = activeProfile().id

    // ------------------------------------------------------------------
    // Changes
    // ------------------------------------------------------------------

    /** Makes [profileId] the active profile. False when unknown. */
    fun setActiveProfile(profileId: String): Boolean {
        ensureMigrated()
        if (readProfiles().none { it.id == profileId }) return false
        prefs.edit().putString(KEY_ACTIVE, profileId).apply()
        return true
    }

    /**
     * Adds a profile and returns it, or null when the cap
     * ([MAX_PROFILES]) is reached. A blank name falls back to
     * "Untitled profile" (the UI validates first; the store stays
     * honest whatever calls it). The new profile does NOT become
     * active — switching is the switcher's own step.
     */
    fun createProfile(name: String, colorIndex: Int): WatchlyProfile? {
        ensureMigrated()
        val stored = readProfiles()
        if (stored.size >= MAX_PROFILES) return null
        val profile = WatchlyProfile(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "Untitled profile" },
            colorIndex = colorIndex.coerceIn(AVATAR_COLOR_HEX.indices)
        )
        writeProfiles(stored + profile)
        return profile
    }

    /** Renames a profile. False for a blank name or unknown id. */
    fun renameProfile(profileId: String, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        ensureMigrated()
        val stored = readProfiles().toMutableList()
        val index = stored.indexOfFirst { it.id == profileId }
        if (index < 0) return false
        stored[index] = stored[index].copy(name = trimmed)
        writeProfiles(stored)
        return true
    }

    /**
     * Deletes a profile AND its data files. False when it is the
     * last remaining profile or the id is unknown. Deleting the
     * active profile activates the first remaining one.
     */
    fun deleteProfile(profileId: String): Boolean {
        ensureMigrated()
        val stored = readProfiles()
        if (stored.size <= 1) return false
        val target = stored.firstOrNull { it.id == profileId } ?: return false
        val remaining = stored.filterNot { it.id == profileId }
        writeProfiles(remaining)
        if (prefs.getString(KEY_ACTIVE, null) == profileId) {
            prefs.edit().putString(KEY_ACTIVE, remaining.first().id).apply()
        }
        // Wipe the profile's three data files; an empty file shell
        // may remain on disk, but none of its data does.
        for (base in SCOPED_BASES) {
            appContext.getSharedPreferences(base + target.fileSuffix, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
        return true
    }

    // ------------------------------------------------------------------
    // Migration + storage
    // ------------------------------------------------------------------

    /**
     * First-run migration: when no profile metadata exists yet,
     * create "Profile 1" and make it active. The data itself is
     * NOT touched — Profile 1's files are the legacy files (see
     * the header), so everything the user already had is simply
     * there, under Profile 1. Idempotent; also repairs an active
     * id that points at a profile which no longer exists.
     */
    fun ensureMigrated() {
        val stored = readProfiles()
        if (stored.isEmpty()) {
            writeProfiles(listOf(DEFAULT_PROFILE))
            prefs.edit().putString(KEY_ACTIVE, DEFAULT_PROFILE_ID).apply()
            return
        }
        val activeId = prefs.getString(KEY_ACTIVE, null)
        if (stored.none { it.id == activeId }) {
            prefs.edit().putString(KEY_ACTIVE, stored.first().id).apply()
        }
    }

    private fun readProfiles(): List<WatchlyProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        val out = ArrayList<WatchlyProfile>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: continue
            out.add(
                WatchlyProfile(
                    id = id,
                    name = name,
                    colorIndex = obj.optInt("color", 0)
                        .coerceIn(AVATAR_COLOR_HEX.indices)
                )
            )
        }
        return out
    }

    private fun writeProfiles(profiles: List<WatchlyProfile>) {
        val array = JSONArray()
        for (profile in profiles) {
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("color", profile.colorIndex)
            )
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    companion object {
        const val PREFS_NAME = "watchly_profiles"
        const val KEY_PROFILES = "profiles"
        const val KEY_ACTIVE = "active_profile_id"

        /** Spec §8: up to four profiles on one phone. */
        const val MAX_PROFILES = 4

        /** The first profile's id — its files are the legacy ones. */
        const val DEFAULT_PROFILE_ID = "default"
        const val DEFAULT_PROFILE_NAME = "Profile 1"

        private val DEFAULT_PROFILE = WatchlyProfile(
            id = DEFAULT_PROFILE_ID,
            name = DEFAULT_PROFILE_NAME,
            colorIndex = 0
        )

        /** The three per-profile data files (bases; suffix appended). */
        val SCOPED_BASES: List<String> = listOf(
            WatchlistRepository.PREFS_NAME,
            com.watchly.app.data.playback.PlaybackRepository.PREFS_NAME,
            RatingsRepository.PREFS_NAME
        )

        /** Avatar palette (ARGB hex) + spoken names, same order. */
        val AVATAR_COLOR_HEX: List<String> = listOf(
            "#D4AF37", "#7FB3D5", "#82E0AA", "#F1948A", "#BB8FCE", "#F0B27A"
        )
        val AVATAR_COLOR_NAMES: List<String> = listOf(
            "Gold", "Blue", "Green", "Rose", "Purple", "Orange"
        )

        /**
         * The data-file suffix for [profileId]: "" for the default
         * profile (legacy files), "_<first 12 id alphanumerics>"
         * otherwise. Pure and deterministic, so any store can find
         * a profile's files from its id alone.
         */
        fun fileSuffixFor(profileId: String): String =
            if (profileId == DEFAULT_PROFILE_ID) {
                ""
            } else {
                "_" + profileId.filter { it.isLetterOrDigit() }.take(12)
            }
    }
}

