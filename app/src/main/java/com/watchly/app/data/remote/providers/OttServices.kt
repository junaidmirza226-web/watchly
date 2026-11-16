/*
 * Watchly — consumer streaming-service sources.
 *
 * The Sources list and the in-player Server Switcher show the services
 * people actually look for — Netflix, Amazon Prime Video, Disney+ Hotstar,
 * Zee5, SonyLIV, JioCinema … — instead of only raw embed hostnames. Each
 * service maps to a multi-server partner player that indexes the title by
 * TMDB/IMDb id and exposes that service's streams in its picker (the
 * `?s=<slug>` hint preselects it where the partner supports it).
 *
 * URL templates live here (single place, no network) so the mapping can be
 * retuned without touching the aggregator, and every builder is unit-tested.
 */
package com.watchly.app.data.remote.providers

import com.watchly.app.data.model.MediaType

/** One consumer OTT service label for the Sources list. */
data class OttService(
    /** URL selector slug used with partner players. */
    val slug: String,
    /** Consumer-facing name shown in Sources / Servers. */
    val displayName: String
)

object OttServices {

    /** The global + South-Asian services Watchly surfaces as first-class sources. */
    val ALL: List<OttService> = listOf(
        OttService("netflix", "Netflix"),
        OttService("prime", "Amazon Prime Video"),
        OttService("hotstar", "Disney+ Hotstar"),
        OttService("disney", "Disney+"),
        OttService("hulu", "Hulu"),
        OttService("max", "Max"),
        OttService("appletv", "Apple TV+"),
        OttService("peacock", "Peacock"),
        OttService("paramount", "Paramount+"),
        OttService("zee5", "Zee5"),
        OttService("sonyliv", "SonyLIV"),
        OttService("jiocinema", "JioCinema"),
        OttService("crunchyroll", "Crunchyroll")
    )

    /** Partner players that index by TMDB/IMDb id and list per-service sources. */
    private val PARTNER_BASES = listOf(
        "https://vidsrc.vip",
        "https://vidsrc.to",
        "https://vidsrc.xyz"
    )

    /**
     * Best-effort embed URL for [service] on the given title. The first
     * valid partner URL wins; every partner page carries its own service
     * picker, so an ignored `?s=` hint still lands on the right title.
     */
    internal fun embedUrl(
        service: OttService,
        tmdbId: Int?,
        imdbId: String?,
        mediaType: MediaType,
        season: Int?,
        episode: Int?
    ): String? {
        val id = tmdbId?.toString() ?: imdbId ?: return null
        val isMovie = mediaType == MediaType.MOVIE
        val s = season ?: 1
        val e = episode ?: 1
        return PARTNER_BASES.asSequence().map { base ->
            if (isMovie) {
                "$base/embed/movie/$id?s=${service.slug}"
            } else {
                "$base/embed/tv/$id/$s/$e?s=${service.slug}"
            }
        }.first()
    }

    /** Instant [StreamSource]s, one per service, for Sources + Servers. */
    fun buildSources(
        tmdbId: Int?,
        imdbId: String?,
        mediaType: MediaType,
        season: Int? = null,
        episode: Int? = null
    ): List<StreamSource> =
        ALL.mapNotNull { service ->
            val url = embedUrl(service, tmdbId, imdbId, mediaType, season, episode)
                ?: return@mapNotNull null
            StreamSource(
                providerName = service.displayName,
                url = url,
                quality = "Auto HD",
                isHls = false,
                headers = mapOf(
                    "Referer" to "https://vidsrc.vip/",
                    "Origin" to "https://vidsrc.vip"
                )
            )
        }
}
