/*
 * Watchly — the user's own video files on this device (cycle 5).
 *
 * Stores ONLY the granted content:// URIs and display names (SAF
 * grants live in the OS, not here). Two kinds of entries:
 *  - individual files the user picked (OpenMultipleDocuments), and
 *  - files found inside a granted folder (OpenDocumentTree); those
 *    carry the folder's tree URI so removing the folder removes
 *    its files in one go.
 *
 * Removing an entry never deletes the user's file — it only drops
 * Watchly's record of the grant. No permission beyond SAF is
 * involved anywhere in this feature.
 */
package com.watchly.app.data.local

import android.content.Context
import android.content.SharedPreferences
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import org.json.JSONArray
import org.json.JSONObject

/** One stored on-device video. */
data class LocalFileEntry(
    /** content:// URI string of the video file itself. */
    val uri: String,
    /** The file's display name as the provider reported it. */
    val displayName: String,
    /** Filename-derived display title (see [deriveTitleFromFileName]). */
    val title: String,
    val addedAtMs: Long,
    /** Tree URI when this file came from a granted folder; else null. */
    val treeUri: String? = null
) {
    /** The entry as a playable [Title]. */
    fun toTitle(): Title = Title(
        localUri = uri,
        name = title,
        mediaType = MediaType.MOVIE
    )
}

class LocalFilesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME, Context.MODE_PRIVATE
        )

    /** All stored entries, oldest first. */
    fun entries(): List<LocalFileEntry> = readEntries()

    /** All stored entries as playable titles (Library's section). */
    fun titles(): List<Title> = readEntries().map { it.toTitle() }

    /**
     * Adds individually picked files. [files] is (uri, displayName)
     * pairs as reported by the document provider. Re-adding a file
     * already stored is a no-op (its entry is kept as-is).
     */
    fun addFiles(files: List<Pair<String, String>>, nowMs: Long) {
        if (files.isEmpty()) return
        val current = readEntries()
        val known = current.map { it.uri }.toSet()
        val additions = files
            .filter { (uri, _) -> uri.isNotBlank() && uri !in known }
            .map { (uri, name) ->
                LocalFileEntry(
                    uri = uri,
                    displayName = name,
                    title = deriveTitleFromFileName(name),
                    addedAtMs = nowMs
                )
            }
        if (additions.isNotEmpty()) writeEntries(current + additions)
    }

    /**
     * Adds the video files found inside a granted folder. [treeUri]
     * is the folder's URI; [files] is (uri, displayName) pairs from
     * the scan. Files already stored (individually or via another
     * folder) are not duplicated.
     */
    fun addFolderFiles(
        treeUri: String,
        files: List<Pair<String, String>>,
        nowMs: Long
    ) {
        if (files.isEmpty()) return
        val current = readEntries()
        val known = current.map { it.uri }.toSet()
        val additions = files
            .filter { (uri, _) -> uri.isNotBlank() && uri !in known }
            .map { (uri, name) ->
                LocalFileEntry(
                    uri = uri,
                    displayName = name,
                    title = deriveTitleFromFileName(name),
                    addedAtMs = nowMs,
                    treeUri = treeUri
                )
            }
        if (additions.isNotEmpty()) writeEntries(current + additions)
    }

    /** Drops one entry (the file itself is never touched). */
    fun remove(uri: String) {
        writeEntries(readEntries().filterNot { it.uri == uri })
    }

    /** Drops every entry that came from the folder [treeUri]. */
    fun removeTree(treeUri: String) {
        writeEntries(readEntries().filterNot { it.treeUri == treeUri })
    }

    /** Distinct granted folder URIs currently represented. */
    fun treeUris(): Set<String> =
        readEntries().mapNotNull { it.treeUri }.toSet()

    // ------------------------------------------------------------------
    // Storage: one JSON array in SharedPreferences.
    // ------------------------------------------------------------------

    private fun readEntries(): List<LocalFileEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        val out = ArrayList<LocalFileEntry>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val uri = obj.optString("uri").takeIf { it.isNotBlank() } ?: continue
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: continue
            out.add(
                LocalFileEntry(
                    uri = uri,
                    displayName = name,
                    title = obj.optString("title")
                        .takeIf { it.isNotBlank() }
                        ?: deriveTitleFromFileName(name),
                    addedAtMs = obj.optLong("added"),
                    treeUri = obj.optString("tree")
                        .takeIf { it.isNotBlank() && it != "null" }
                )
            )
        }
        return out
    }

    private fun writeEntries(entries: List<LocalFileEntry>) {
        val array = JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject()
                    .put("uri", entry.uri)
                    .put("name", entry.displayName)
                    .put("title", entry.title)
                    .put("added", entry.addedAtMs)
                    .put("tree", entry.treeUri ?: JSONObject.NULL)
            )
        }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    companion object {
        const val PREFS_NAME = "watchly_local_files"
        private const val KEY_ENTRIES = "entries"
    }
}
