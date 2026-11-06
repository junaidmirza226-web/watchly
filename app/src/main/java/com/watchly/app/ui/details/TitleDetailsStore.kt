/*
 * Watchly — in-memory holder for the title a poster tap selected.
 *
 * The details route carries no arguments: the selected Title (which is
 * not Parcelable and can be large) is handed over through this holder
 * instead of being serialised into the back stack. The holder is
 * cleared when the details screen is left, so a stale title can never
 * resurface. This is the standard single-activity pattern for passing
 * rich objects between Compose destinations.
 */
package com.watchly.app.ui.details

import com.watchly.app.data.model.Title

object TitleDetailsStore {

    private var selected: Title? = null

    fun select(title: Title) {
        selected = title
    }

    fun take(): Title? = selected

    fun clear() {
        selected = null
    }
}
