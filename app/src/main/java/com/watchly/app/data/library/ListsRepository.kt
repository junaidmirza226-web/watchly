/*
 * Watchly — user-created lists (spec §9.2, screens §6.2).
 *
 * Named lists like "Weekend" or "Best Anime": create, rename,
 * delete, add/remove titles, and reorder by drag. Stored in the
 * same SharedPreferences file as the watchlist ("watchly_library"),
 * as one JSON array under KEY_LISTS — each list holds small title
 * snapshots (stable key, name, poster, year, type), enough to
 * render offline without re-fetching, exactly like the watchlist.
 *
 * Lists are per profile (§8, cycle 12): this store shares the
 * watchlist's per-profile file, so a profile's lists live and
 * switch together with its watchlist. Pure storage logic: no
 * network, works fully offline.
 */
package com.watchly.app.data.library

import android.content.Context
import android.content.SharedPreferences
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One user list, titles in the user's own (drag) order. */
data class UserList(
    val id: String,
    val name: String,
    val titles: List<Title>
) {
    val size: Int get() = titles.size
}

class ListsRepository(
    context: Context,
    profileId: String? = null
) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME + ProfilesRepository.fileSuffixFor(
            profileId ?: ProfilesRepository(context).activeProfileId()
        ),
        Context.MODE_PRIVATE
    )

    // ------------------------------------------------------------------
    // Lists themselves
    // ------------------------------------------------------------------

    /** All lists, in creation order. */
    fun getLists(): List<UserList> = readLists().map { it.toUserList() }

    /** One list by id, or null when it doesn't exist. */
    fun getList(listId: String): UserList? =
        readLists().firstOrNull { it.id == listId }?.toUserList()

    /**
     * Creates a list and returns it. A blank name falls back to
     * "Untitled list" (the UI validates first; this keeps the store
     * honest whatever calls it).
     */
    fun createList(name: String): UserList {
        val stored = StoredList(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "Untitled list" },
            items = emptyList()
        )
        writeLists(readLists() + stored)
        return stored.toUserList()
    }

    /** Renames a list. False when the list doesn't exist. */
    fun renameList(listId: String, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        val lists = readLists().toMutableList()
        val index = lists.indexOfFirst { it.id == listId }
        if (index < 0) return false
        lists[index] = lists[index].copy(name = trimmed)
        writeLists(lists)
        return true
    }

    /** Deletes a list and its contents. False when it doesn't exist. */
    fun deleteList(listId: String): Boolean {
        val lists = readLists()
        if (lists.none { it.id == listId }) return false
        writeLists(lists.filterNot { it.id == listId })
        return true
    }

    // ------------------------------------------------------------------
    // Titles inside a list
    // ------------------------------------------------------------------

    /** True when the title is already in the list. */
    fun containsTitle(listId: String, title: Title): Boolean =
        readLists().firstOrNull { it.id == listId }
            ?.items?.any { it.stableKey == title.stableKey } == true

    /**
     * Adds the title at the end of the list. False when the list
     * doesn't exist or the title is already in it (no duplicates —
     * a list is a set with an order, not a bag).
     */
    fun addTitle(listId: String, title: Title): Boolean {
        val lists = readLists().toMutableList()
        val index = lists.indexOfFirst { it.id == listId }
        if (index < 0) return false
        val list = lists[index]
        if (list.items.any { it.stableKey == title.stableKey }) return false
        lists[index] = list.copy(items = list.items + StoredTitle.from(title))
        writeLists(lists)
        return true
    }

    /** Removes the title from the list. False when it wasn't there. */
    fun removeTitle(listId: String, title: Title): Boolean {
        val lists = readLists().toMutableList()
        val index = lists.indexOfFirst { it.id == listId }
        if (index < 0) return false
        val list = lists[index]
        if (list.items.none { it.stableKey == title.stableKey }) return false
        lists[index] = list.copy(
            items = list.items.filterNot { it.stableKey == title.stableKey }
        )
        writeLists(lists)
        return true
    }

    /**
     * Moves the item at [fromIndex] to [toIndex] — the persistence
     * behind drag reordering. False for a missing list or indices
     * that don't both point at real items.
     */
    fun moveTitle(listId: String, fromIndex: Int, toIndex: Int): Boolean {
        val lists = readLists().toMutableList()
        val index = lists.indexOfFirst { it.id == listId }
        if (index < 0) return false
        val items = lists[index].items.toMutableList()
        if (fromIndex !in items.indices || toIndex !in items.indices) return false
        if (fromIndex == toIndex) return true
        val moved = items.removeAt(fromIndex)
        items.add(toIndex, moved)
        lists[index] = lists[index].copy(items = items)
        writeLists(lists)
        return true
    }

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    private fun readLists(): List<StoredList> {
        val raw = prefs.getString(KEY_LISTS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        val out = ArrayList<StoredList>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: continue
            val itemsArray = obj.optJSONArray("items") ?: JSONArray()
            val items = ArrayList<StoredTitle>(itemsArray.length())
            for (j in 0 until itemsArray.length()) {
                val item = itemsArray.optJSONObject(j) ?: continue
                val key = item.optString("key").takeIf { it.isNotBlank() } ?: continue
                val itemName = item.optString("name").takeIf { it.isNotBlank() } ?: continue
                items.add(
                    StoredTitle(
                        stableKey = key,
                        name = itemName,
                        // Null-safe reads (see WatchlistRepository's
                        // optNullable — the literal string "null"
                        // must never reach a card).
                        posterUrl = item.optNullable("poster"),
                        year = item.optNullable("year"),
                        mediaType = runCatching {
                            MediaType.valueOf(item.optString("type"))
                        }.getOrElse { MediaType.MOVIE }
                    )
                )
            }
            out.add(StoredList(id = id, name = name, items = items))
        }
        return out
    }

    private fun writeLists(lists: List<StoredList>) {
        val array = JSONArray()
        for (list in lists) {
            val items = JSONArray()
            for (entry in list.items) {
                items.put(
                    JSONObject()
                        .put("key", entry.stableKey)
                        .put("name", entry.name)
                        .put("poster", entry.posterUrl ?: JSONObject.NULL)
                        .put("year", entry.year ?: JSONObject.NULL)
                        .put("type", entry.mediaType.name)
                )
            }
            array.put(
                JSONObject()
                    .put("id", list.id)
                    .put("name", list.name)
                    .put("items", items)
            )
        }
        prefs.edit().putString(KEY_LISTS, array.toString()).apply()
    }

    private data class StoredList(
        val id: String,
        val name: String,
        val items: List<StoredTitle>
    ) {
        fun toUserList(): UserList = UserList(
            id = id,
            name = name,
            titles = items.mapNotNull { it.toTitle() }
        )
    }

    /** Minimal snapshot of a title — same shape as the watchlist's. */
    private data class StoredTitle(
        val stableKey: String,
        val name: String,
        val posterUrl: String?,
        val year: String?,
        val mediaType: MediaType
    ) {
        companion object {
            fun from(title: Title) = StoredTitle(
                stableKey = title.stableKey,
                name = title.name,
                posterUrl = title.posterUrl,
                year = title.year,
                mediaType = title.mediaType
            )
        }

        /** Rebuilds a displayable Title, recovering provider ids
         * from the stable key like the watchlist store does. */
        fun toTitle(): Title? {
            val parts = stableKey.split(":", limit = 2)
            val base = Title(
                name = name,
                posterUrl = posterUrl,
                mediaType = mediaType,
                year = year
            )
            if (parts.size != 2) return base
            return when (parts[0]) {
                "tvmaze" -> base.copy(tvmazeId = parts[1].toIntOrNull())
                "anilist" -> base.copy(anilistId = parts[1].toIntOrNull())
                "archive" -> base.copy(archiveId = parts[1])
                "youtube" -> base.copy(youtubeId = parts[1])
                "imdb" -> base.copy(imdbId = parts[1])
                "tmdb" -> base.copy(tmdbId = parts[1].toIntOrNull())
                else -> base
            }
        }
    }

    companion object {
        /** Same file as the watchlist — one local library store. */
        const val PREFS_NAME = "watchly_library"
        const val KEY_LISTS = "custom_lists"
    }
}
