/*
 * Watchly — persisted recent searches (spec §3.1).
 *
 * The last few search terms, kept in SharedPreferences so they survive
 * app restarts — like Netflix/Plex showing recent searches on the empty
 * search screen. Search terms only; no results, no history of what was
 * tapped, nothing leaves the device.
 */
package com.watchly.app.data.search

import android.content.Context
import org.json.JSONArray

class SearchHistoryRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Recent terms, most recent first. Empty when nothing searched yet. */
    fun load(): List<String> {
        val raw = prefs.getString(KEY_RECENT, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            array.optString(i).takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        return out
    }

    /** Records a search term (most recent first, de-duplicated, capped). */
    fun save(query: String) {
        val term = query.trim()
        if (term.isEmpty()) return
        val updated = (listOf(term) + load())
            .distinct().take(MAX_RECENT)
        write(updated)
    }

    /** Clears all recent searches. */
    fun clear() {
        write(emptyList())
    }

    private fun write(terms: List<String>) {
        val array = JSONArray()
        for (term in terms) array.put(term)
        prefs.edit().putString(KEY_RECENT, array.toString()).apply()
    }

    companion object {
        const val PREFS_NAME = "watchly_search"
        const val KEY_RECENT = "recent_searches"
        const val MAX_RECENT = 8
    }
}
