/*
 * Watchly — in-memory holder for the person a cast tap selected
 * (spec §3.3, cycle 11).
 *
 * Same hand-over pattern as TitleDetailsStore: the person route
 * carries no arguments; the tapped cast member's TMDB person id
 * (plus the name, so the page header is never blank while loading)
 * is handed over through this holder and cleared when the page is
 * left, so a stale person can never resurface. Only ids TMDB itself
 * supplied ever land here — cast from other sources has no TMDB
 * person id and never reaches this store.
 */
package com.watchly.app.ui.person

/** The cast tap's payload: TMDB person id + display name. */
data class SelectedPerson(
    val tmdbId: Int,
    val name: String
)

object PersonStore {

    private var selected: SelectedPerson? = null

    fun select(tmdbId: Int, name: String) {
        selected = SelectedPerson(tmdbId, name)
    }

    fun take(): SelectedPerson? = selected

    fun clear() {
        selected = null
    }
}
