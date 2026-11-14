package com.watchly.app.data.metadata

/**
 * Reference implementation exercised by CanonicalIdMapperTest.
 * Copy to: app/src/main/java/com/watchly/app/data/metadata/
 * Skip this file if the app already has an equivalent class — adapt the test instead.
 */

enum class MediaType { MOVIE, TV, ANIME }

enum class IdProvider { TMDB, IMDB, TVDB, ANILIST, MAL }

/** Raw IDs for one title, as returned by the various metadata providers. */
data class ExternalIds(
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val tvdbId: Int? = null,
    val anilistId: Int? = null,
    val malId: Int? = null,
)

data class ParsedCanonicalId(
    val mediaType: MediaType,
    val provider: IdProvider,
    val externalId: String,
)

/**
 * Builds Watchly's stable, provider-independent ID for a title, e.g.
 *   watchly:movie:tmdb:603
 *   watchly:anime:anilist:21
 *
 * The same title must always produce the same canonical ID, regardless of
 * which provider the app happened to fetch it from first — so the provider
 * choice follows a fixed priority per media type, never "first non-null
 * in whatever order a response arrived".
 */
object CanonicalIdMapper {

    fun toCanonical(mediaType: MediaType, ids: ExternalIds): String {
        val parsed = select(mediaType, ids)
        return "watchly:${mediaType.name.lowercase()}:" +
            "${parsed.provider.name.lowercase()}:${parsed.externalId}"
    }

    fun parse(canonicalId: String): ParsedCanonicalId {
        val parts = canonicalId.trim().lowercase().split(":")
        require(parts.size == 4 && parts[0] == "watchly") {
            "Not a Watchly canonical ID: '$canonicalId'"
        }
        val mediaType = MediaType.entries.firstOrNull { it.name.lowercase() == parts[1] }
            ?: throw IllegalArgumentException("Unknown media type in canonical ID: '$canonicalId'")
        val provider = IdProvider.entries.firstOrNull { it.name.lowercase() == parts[2] }
            ?: throw IllegalArgumentException("Unknown provider in canonical ID: '$canonicalId'")
        require(parts[3].isNotBlank()) { "Empty external ID in canonical ID: '$canonicalId'" }
        return ParsedCanonicalId(mediaType, provider, parts[3])
    }

    private fun select(mediaType: MediaType, ids: ExternalIds): ParsedCanonicalId {
        // Anime catalogues key on AniList/MyAnimeList first; live-action on TMDB.
        val priority: List<IdProvider> = if (mediaType == MediaType.ANIME) {
            listOf(IdProvider.ANILIST, IdProvider.MAL, IdProvider.TMDB, IdProvider.IMDB, IdProvider.TVDB)
        } else {
            listOf(IdProvider.TMDB, IdProvider.IMDB, IdProvider.TVDB, IdProvider.ANILIST, IdProvider.MAL)
        }
        for (provider in priority) {
            val value = valueOf(provider, ids)
            if (value != null) return ParsedCanonicalId(mediaType, provider, value)
        }
        throw IllegalArgumentException("No usable external ID for $mediaType: $ids")
    }

    private fun valueOf(provider: IdProvider, ids: ExternalIds): String? = when (provider) {
        IdProvider.TMDB -> ids.tmdbId?.takeIf { it > 0 }?.toString()
        IdProvider.TVDB -> ids.tvdbId?.takeIf { it > 0 }?.toString()
        IdProvider.ANILIST -> ids.anilistId?.takeIf { it > 0 }?.toString()
        IdProvider.MAL -> ids.malId?.takeIf { it > 0 }?.toString()
        // IMDb IDs are strings ("tt0133093"); normalise case/whitespace, treat blank as absent.
        IdProvider.IMDB -> ids.imdbId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    }
}
