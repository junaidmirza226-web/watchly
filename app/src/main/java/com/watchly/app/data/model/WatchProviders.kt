/*
 * Watchly — where-to-watch model (legal availability, TMDB data).
 *
 * Dependencies: none beyond the Kotlin standard library, so the
 * merge/region rules below are pure-JVM unit-tested.
 *
 * TMDB's watch-provider data (powered by JustWatch) says which
 * official services stream, rent or sell a title in a given region.
 * Watchly only ever *points* at those services — playback of their
 * catalogues happens in their own apps, never inside Watchly.
 */
package com.watchly.app.data.model

/** How a service offers the title in a region. */
enum class WatchCategory(val label: String) {
    STREAM("Stream"),
    RENT("Rent"),
    BUY("Buy")
}

/** One official service offering a title in a region. */
data class WatchProvider(
    val name: String,
    val logoUrl: String?,
    val category: WatchCategory
)

/**
 * Where one title can officially be watched in [region].
 *
 * @param region       ISO 3166-1 country code the options apply to.
 *                     When TMDB has no entry for the device's region,
 *                     the parser falls back to "US" and this field
 *                     says so — the UI prints it, never hides it.
 * @param watchPageUrl TMDB's watch page for this title + region; the
 *                     one link TMDB supplies per region. Tapping any
 *                     provider opens it. Null when TMDB gave no link.
 * @param providers    Merged display list (see [combineWatchProviders]).
 */
data class WatchProviders(
    val region: String,
    val watchPageUrl: String?,
    val providers: List<WatchProvider>
) {
    val isEmpty: Boolean
        get() = providers.isEmpty()

    fun providersIn(category: WatchCategory): List<WatchProvider> =
        providers.filter { it.category == category }
}

/**
 * Merges TMDB's separate flatrate / rent / buy lists into one display
 * list. A service appearing in several lists is shown once, under its
 * best category — streaming beats renting beats buying — matched
 * case-insensitively by name. Order: category first, then name A–Z,
 * so the list reads the way people decide: "can I stream it?"
 */
fun combineWatchProviders(
    stream: List<WatchProvider>,
    rent: List<WatchProvider>,
    buy: List<WatchProvider>
): List<WatchProvider> {
    val best = LinkedHashMap<String, WatchProvider>()
    // Best-category-first iteration + putIfAbsent: the first sighting
    // of a service name is the category it keeps.
    for (provider in stream + rent + buy) {
        best.putIfAbsent(provider.name.lowercase(), provider)
    }
    return best.values.sortedWith(
        compareBy<WatchProvider> { it.category.ordinal }
            .thenBy { it.name.lowercase() }
    )
}

/**
 * The region to ask TMDB about: the device's country when it is a
 * usable 2-letter code, otherwise "US" (TMDB's most complete region).
 */
fun preferredWatchRegion(deviceCountry: String?): String {
    val code = deviceCountry?.trim()?.uppercase().orEmpty()
    return if (code.length == 2 && code.all { it.isLetter() }) code else "US"
}
