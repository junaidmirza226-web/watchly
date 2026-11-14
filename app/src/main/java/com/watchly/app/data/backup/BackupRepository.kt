/*
 * Watchly — backup & export of the user's own data (spec §15.2,
 * cycle 10).
 *
 * One JSON file holds everything that is *his*: the watchlist and
 * watched marks, custom lists, continue-watching progress, the
 * per-title player choices (quality / audio language / subtitle
 * offset), the playback speed, his personal ratings, and the app
 * settings (filters, autoplay, subtitle style, Data Saver,
 * download rules). Exported via the system file picker (SAF) and
 * importable on any install, so a reinstall can never again cost
 * him his lists — the cycle-6/7 lesson, fixed for good.
 *
 * API KEYS ARE NEVER EXPORTED. The key stores ("watchly_settings")
 * are not in the store list below at all — there is no code path
 * that reads them here — and the UI copy says so plainly. The same
 * goes for caches and device-bound state (download records point
 * at files inside one install; "On this device" SAF grants are
 * per-install), which stay out by design.
 *
 * Format (JSON, format version 1):
 *   { "app": "watchly", "format": 1, "exportedAt": <ms>,
 *     "stores": { "<prefs file>": { "<key>": {"t": <tag>, "v": …} } } }
 * Type tags: "s" String, "b" Boolean, "i" Int, "l" Long,
 * "f" Float, "set" string set. The envelope is deliberately typed:
 * SharedPreferences values are typed, and a lossless round-trip
 * (proven by BackupRepositoryTest) needs the types back exactly.
 *
 * Restore is a REPLACE of the included stores/keys: restoring an
 * older backup makes the device match the backup, not a merge of
 * the two. Unknown stores, keys and value types in the file are
 * ignored, so a backup written by a newer Watchly never breaks an
 * older one beyond what it understands.
 *
 * Profiles (cycle 12, §8): the backup carries the profile list
 * itself ("watchly_profiles") plus EVERY profile's data files
 * (library / playback / ratings, suffixed per profile). Export
 * enumerates the profiles on this device; restore ALSO accepts
 * the scoped files named in the backup, so restoring on a fresh
 * install — where only the default profile exists so far —
 * brings back all of the backup's profiles with their data.
 * Downloads and API keys stay out, exactly as before.
 */
package com.watchly.app.data.backup

import android.content.Context
import android.content.SharedPreferences
import com.watchly.app.data.downloads.DownloadSettingsRepository
import com.watchly.app.data.library.ListsRepository
import com.watchly.app.data.library.ProfilesRepository
import com.watchly.app.data.library.RatingsRepository
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.playback.PlaybackRepository
import com.watchly.app.data.settings.UiPreferencesRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/** Thrown when a file is not a readable Watchly backup. */
class BackupFormatException(message: String) : Exception(message)

/** What a successful restore wrote, for the UI's plain summary. */
data class BackupRestoreResult(
    val storesRestored: Int,
    val entriesRestored: Int
)

class BackupRepository(private val context: Context) {

    /**
     * One SharedPreferences file taking part in the backup.
     * [keys] null = the whole file is user data; otherwise only
     * the listed keys travel (the library file's contract).
     */
    private data class StoreSpec(val prefsName: String, val keys: Set<String>?)

    /** The library file's user-data keys (the rest of that file
     * is not the user's library and never travels). */
    private val libraryKeys: Set<String> = setOf(
        WatchlistRepository.KEY_WATCHLIST,
        WatchlistRepository.KEY_WATCHED,
        WatchlistRepository.KEY_WATCHED_EPISODES,
        ListsRepository.KEY_LISTS
    )

    /**
     * The stores THIS device exports: the profile list, every
     * profile's three data files, and the two global settings
     * files. Computed per call — a profile created a moment ago
     * is included in the next export.
     */
    private fun deviceStoreSpecs(): List<StoreSpec> {
        val specs = ArrayList<StoreSpec>()
        specs += StoreSpec(ProfilesRepository.PREFS_NAME, null)
        for (profile in ProfilesRepository(context).profiles()) {
            val suffix = profile.fileSuffix
            specs += StoreSpec(WatchlistRepository.PREFS_NAME + suffix, libraryKeys)
            specs += StoreSpec(PlaybackRepository.PREFS_NAME + suffix, null)
            specs += StoreSpec(RatingsRepository.PREFS_NAME + suffix, null)
        }
        specs += StoreSpec(UiPreferencesRepository.PREFS_NAME, null)
        specs += StoreSpec(DownloadSettingsRepository.PREFS_NAME, null)
        return specs
    }

    /**
     * The spec for one store name found in a backup file, or null
     * when the name is not a store a backup may write. Scoped
     * (per-profile) files are recognised by their base + suffix
     * shape, so a restore accepts profiles this device has never
     * seen (the fresh-install case). Names outside the known
     * families — caches, key stores, download state — are refused.
     */
    private fun classifyStore(name: String): StoreSpec? = when {
        name == ProfilesRepository.PREFS_NAME -> StoreSpec(name, null)
        name == UiPreferencesRepository.PREFS_NAME -> StoreSpec(name, null)
        name == DownloadSettingsRepository.PREFS_NAME -> StoreSpec(name, null)
        isScopedName(name, WatchlistRepository.PREFS_NAME) ->
            StoreSpec(name, libraryKeys)
        isScopedName(name, PlaybackRepository.PREFS_NAME) ->
            StoreSpec(name, null)
        isScopedName(name, RatingsRepository.PREFS_NAME) ->
            StoreSpec(name, null)
        else -> null
    }

    /** [base] itself, or [base] + "_" + id alphanumerics. */
    private fun isScopedName(name: String, base: String): Boolean {
        if (name == base) return true
        if (!name.startsWith(base + "_")) return false
        val suffix = name.removePrefix(base + "_")
        return suffix.isNotEmpty() && suffix.all { it.isLetterOrDigit() }
    }

    /**
     * The stores a restore may write: this device's own specs
     * UNION the specs for every store the backup names. The union
     * is what lets a fresh install regain profiles it has never
     * seen locally.
     */
    private fun restoreStoreSpecs(storesJson: JSONObject): List<StoreSpec> {
        val byName = LinkedHashMap<String, StoreSpec>()
        for (spec in deviceStoreSpecs()) {
            byName[spec.prefsName] = spec
        }
        val names = storesJson.keys()
        while (names.hasNext()) {
            val name = names.next()
            if (name !in byName) {
                classifyStore(name)?.let { byName[name] = it }
            }
        }
        return byName.values.toList()
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    /** The whole backup as one JSON string. */
    fun buildBackupJson(exportedAtMs: Long): String {
        val storesJson = JSONObject()
        for (spec in deviceStoreSpecs()) {
            val prefs = prefs(spec.prefsName)
            val entries = sortedEntries(prefs, spec.keys)
            if (entries.isEmpty()) continue
            val storeJson = JSONObject()
            for ((key, value) in entries) {
                storeJson.put(key, encodeValue(value))
            }
            storesJson.put(spec.prefsName, storeJson)
        }
        return JSONObject()
            .put("app", APP_TAG)
            .put("format", FORMAT_VERSION)
            .put("exportedAt", exportedAtMs)
            .put("stores", storesJson)
            .toString(2)
    }

    /** Writes the backup JSON to [out] (a SAF stream in the UI). */
    fun writeBackupTo(out: OutputStream, exportedAtMs: Long) {
        out.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(buildBackupJson(exportedAtMs))
        }
    }

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    /**
     * Restores from [json], replacing the included stores' current
     * contents. Returns what was written. Throws
     * [BackupFormatException] — before touching any store — when
     * the file is not a Watchly backup this version can read.
     */
    fun restoreFromJson(json: String): BackupRestoreResult {
        val root = runCatching { JSONObject(json) }
            .getOrElse {
                throw BackupFormatException("That file isn't a Watchly backup.")
            }
        if (root.optString("app") != APP_TAG) {
            throw BackupFormatException("That file isn't a Watchly backup.")
        }
        val format = root.optInt("format", -1)
        if (format < 1) {
            throw BackupFormatException("That file isn't a Watchly backup.")
        }
        if (format > FORMAT_VERSION) {
            throw BackupFormatException(
                "That backup was made by a newer Watchly — update the app first."
            )
        }
        val storesJson = root.optJSONObject("stores")
            ?: throw BackupFormatException("That backup has nothing in it to restore.")

        // Plan first, write second: every entry is validated
        // (known store, allowed key, known type) BEFORE any store
        // is touched, so a file with nothing restorable in it is
        // rejected with the device exactly as it was — a restore
        // must never half-happen.
        val plan = ArrayList<Pair<StoreSpec, List<Pair<String, JSONObject>>>>()
        var plannedEntries = 0
        for (spec in restoreStoreSpecs(storesJson)) {
            val storeJson = storesJson.optJSONObject(spec.prefsName) ?: continue
            val writes = ArrayList<Pair<String, JSONObject>>()
            val keys = storeJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (spec.keys != null && key !in spec.keys) continue
                val entry = storeJson.optJSONObject(key) ?: continue
                if (entry.optString("t") !in KNOWN_TYPES) continue
                writes.add(key to entry)
            }
            if (writes.isNotEmpty()) {
                plan.add(spec to writes)
                plannedEntries += writes.size
            }
        }
        if (plannedEntries == 0) {
            throw BackupFormatException("That backup has nothing in it to restore.")
        }

        for ((spec, writes) in plan) {
            val editor = prefs(spec.prefsName).edit()
            // Replace, not merge: clear exactly what the backup
            // covers, then write the backup's values.
            if (spec.keys == null) {
                editor.clear()
            } else {
                spec.keys.forEach { editor.remove(it) }
            }
            for ((key, entry) in writes) {
                writeValue(editor, key, entry)
            }
            editor.commit()
        }
        return BackupRestoreResult(plan.size, plannedEntries)
    }

    /** Reads and restores a backup from [input] (a SAF stream). */
    fun readBackupFrom(input: InputStream): BackupRestoreResult {
        val text = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return restoreFromJson(text)
    }

    // ------------------------------------------------------------------
    // Typed envelope
    // ------------------------------------------------------------------

    private fun prefs(name: String): SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** The store's entries in stable (sorted-key) order. */
    private fun sortedEntries(
        prefs: SharedPreferences,
        allowedKeys: Set<String>?
    ): List<Pair<String, Any>> {
        val all: Map<String, *> = prefs.all
        return all.entries
            .filter { entry ->
                allowedKeys == null || entry.key in allowedKeys
            }
            .mapNotNull { entry ->
                entry.value?.let { value -> entry.key to value }
            }
            .sortedBy { it.first }
    }

    private fun encodeValue(value: Any): JSONObject = when (value) {
        is String -> JSONObject().put("t", "s").put("v", value)
        is Boolean -> JSONObject().put("t", "b").put("v", value)
        is Int -> JSONObject().put("t", "i").put("v", value)
        is Long -> JSONObject().put("t", "l").put("v", value)
        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
        is Set<*> -> JSONObject().put("t", "set").put(
            "v",
            JSONArray(value.filterIsInstance<String>().sorted())
        )
        else -> JSONObject().put("t", "s").put("v", value.toString())
    }

    /** Writes one envelope entry; false when its type is unknown. */
    private fun writeValue(
        editor: SharedPreferences.Editor,
        key: String,
        entry: JSONObject
    ): Boolean {
        when (entry.optString("t")) {
            "s" -> editor.putString(key, entry.optString("v"))
            "b" -> editor.putBoolean(key, entry.optBoolean("v"))
            "i" -> editor.putInt(key, entry.optInt("v"))
            "l" -> editor.putLong(key, entry.optLong("v"))
            "f" -> editor.putFloat(key, entry.optDouble("v").toFloat())
            "set" -> {
                val array = entry.optJSONArray("v") ?: return false
                val values = HashSet<String>(array.length())
                for (i in 0 until array.length()) {
                    array.optString(i).takeIf { it.isNotBlank() }
                        ?.let { values.add(it) }
                }
                editor.putStringSet(key, values)
            }
            else -> return false
        }
        return true
    }

    companion object {
        const val APP_TAG = "watchly"
        const val FORMAT_VERSION = 1

        /** The envelope type tags [writeValue] can restore. */
        private val KNOWN_TYPES = setOf("s", "b", "i", "l", "f", "set")

        /** Default file name offered in the save picker. */
        const val DEFAULT_FILE_NAME = "watchly-backup.json"
    }
}
