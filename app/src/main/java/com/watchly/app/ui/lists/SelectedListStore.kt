/*
 * Watchly — in-memory holder for the list a card tap selected.
 *
 * Same pattern as TitleDetailsStore: the list-detail route carries
 * no arguments; the selected list id is handed over through this
 * holder and cleared when the screen is left, so a stale list can
 * never resurface on a later entry.
 */
package com.watchly.app.ui.lists

object SelectedListStore {

    private var selectedId: String? = null

    fun select(listId: String) {
        selectedId = listId
    }

    fun take(): String? = selectedId

    fun clear() {
        selectedId = null
    }
}
