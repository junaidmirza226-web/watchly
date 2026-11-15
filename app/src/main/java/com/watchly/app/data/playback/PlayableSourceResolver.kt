/*
 * Watchly — resolves what the player can actually start for a title.
 *
 * Priority order:
 *  0) On-device SAF video file ([Title.localUri]) -> native ExoPlayer
 *  1) Completed offline download -> native ExoPlayer (zero data usage)
 *  2) Multi-server cloud streams (TMDB/IMDb, plus auto-bridged AniList/TVmaze)
 *  3) Internet Archive direct files ([Title.archiveId]) -> native ExoPlayer
 *  4) Official YouTube embedded player ([Title.youtubeId])
 */
package com.watchly.app.data.playback

import com.watchly.app.data.downloads.DownloadStatus
import com.watchly.app.data.downloads.DownloadsRepository
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.PlayableMediaHeaders
import com.watchly.app.data.model.Title
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.TmdbApi
import com.watchly.app.data.remote.config.RemoteProviderConfig
import com.watchly.app.data.remote.providers.StreamAggregator
import kotlinx.coroutines.withTimeoutOrNull

/** What the player can start for one title. */
data class ResolvedPlayable(
    val files: List<PlayableFile> = emptyList(),
    val youtubeVideoId: String? = null,
    val webEmbedUrl: String? = null
) {
    val isPlayable: Boolean
        get() = files.isNotEmpty() || youtubeVideoId != null || webEmbedUrl != null

    companion object {
        val NOT_PLAYABLE = ResolvedPlayable()
    }
}

object PlayableSourceResolver {

    /** Resolves [title]; [downloads] may be null (tests, previews). */
    suspend fun resolve(
        title: Title,
        downloads: DownloadsRepository?,
        season: Int? = null,
        episode: Int? = null
    ): ResolvedPlayable {
        // 0) The user's own on-device file: direct source.
        title.localUri?.let { uri ->
            return ResolvedPlayable(
                files = listOf(PlayableFile(url = uri, fileName = title.name))
            )
        }

        // 1) A finished offline download is always fastest and zero-data.
        val record = downloads?.recordForTitle(title.stableKey)
        if (record != null && record.status == DownloadStatus.COMPLETED) {
            val path = record.localPath
            if (path != null && java.io.File(path).exists()) {
                return ResolvedPlayable(
                    files = listOf(
                        PlayableFile(
                            url = path,
                            fileName = record.fileName,
                            sizeBytes = record.sizeBytes,
                            qualityLabel = record.qualityLabel
                        )
                    )
                )
            }
        }

        // 2) Archive-native public domain films prefer direct Archive MP4s first
        // so native ExoPlayer + byte-range downloads always light up cleanly.
        val archiveId = title.archiveId
        if (archiveId != null) {
            val archiveFiles = runCatching {
                ArchiveApi.fetchPlayableFiles(archiveId)
            }.getOrDefault(emptyList())
            if (archiveFiles.isNotEmpty()) {
                return ResolvedPlayable(files = archiveFiles)
            }
        }

        // 3) Official YouTube video (trailers & official YouTube channels).
        val youtubeId = title.youtubeId
        if (youtubeId != null) {
            return ResolvedPlayable(youtubeVideoId = youtubeId)
        }

        // 4) Auto-bridge AniList / TVmaze titles to TMDB ID when needed so
        // Anime and TVmaze series stream seamlessly across cloud servers.
        val effectiveTitle = ensureStreamableIds(title)

        // 5) Multi-source cloud streaming providers
        if (effectiveTitle.tmdbId != null || effectiveTitle.imdbId != null) {
            val instantEmbeds = StreamAggregator.getInstantEmbedServers(
                effectiveTitle,
                season = season,
                episode = episode
            )
            val probedStreams = runCatching {
                StreamAggregator.getStreams(
                    effectiveTitle,
                    season = season,
                    episode = episode
                )
            }.getOrDefault(emptyList())

            val combined = (probedStreams + instantEmbeds).distinctBy { it.url }
            if (combined.isNotEmpty()) {
                val resolvedFiles = mutableListOf<PlayableFile>()
                var firstEmbedUrl: String? = null

                for (stream in combined) {
                    PlayableMediaHeaders.register(stream.url, stream.headers)
                    val isDirectStream = stream.url.contains(".m3u8", ignoreCase = true) ||
                        stream.url.contains(".mp4", ignoreCase = true) ||
                        stream.url.contains(".mkv", ignoreCase = true)

                    if (!isDirectStream) {
                        if (firstEmbedUrl == null) firstEmbedUrl = stream.url
                        resolvedFiles.add(
                            PlayableFile(
                                url = stream.url,
                                fileName = "${effectiveTitle.name} (${stream.providerName})",
                                format = "EMBED",
                                qualityLabel = stream.providerName,
                                headers = stream.headers
                            )
                        )
                    } else {
                        resolvedFiles.add(
                            PlayableFile(
                                url = stream.url,
                                fileName = "${effectiveTitle.name} (${stream.providerName} - ${stream.quality})",
                                format = if (stream.isHls) "HLS" else "MP4",
                                qualityLabel = "${stream.quality} • ${stream.providerName}",
                                headers = stream.headers
                            )
                        )
                    }
                }

                if (resolvedFiles.isNotEmpty()) {
                    return ResolvedPlayable(
                        files = resolvedFiles,
                        webEmbedUrl = firstEmbedUrl
                    )
                }
            }
        }

        return ResolvedPlayable.NOT_PLAYABLE
    }

    /**
     * Bridges an AniList or TVmaze title lacking a TMDB ID by querying TMDB
     * (`/find` when an IMDb ID exists, or `/search/multi` by title + mediaType).
     */
    private suspend fun ensureStreamableIds(title: Title): Title {
        if (title.tmdbId != null) return title
        val apiKey = RemoteProviderConfig.tmdbApiKey?.takeIf { it.isNotBlank() } ?: return title
        return withTimeoutOrNull(3_000L) {
            val byImdb = title.imdbId?.let { imdb ->
                runCatching { TmdbApi.findByImdbId(apiKey, imdb, title.mediaType) }.getOrNull()
            }
            if (byImdb != null) {
                return@withTimeoutOrNull title.copy(tmdbId = byImdb)
            }
            if (title.anilistId != null || title.tvmazeId != null) {
                val results = runCatching { TmdbApi.searchMulti(apiKey, title.name) }.getOrNull()
                    .orEmpty()
                val match = results.firstOrNull { candidate ->
                    val typeMatches = if (title.mediaType == MediaType.MOVIE) {
                        candidate.mediaType == MediaType.MOVIE
                    } else {
                        candidate.mediaType == MediaType.TV
                    }
                    typeMatches && (title.year == null || candidate.year == null || candidate.year == title.year)
                } ?: results.firstOrNull()
                if (match?.tmdbId != null) {
                    return@withTimeoutOrNull title.copy(tmdbId = match.tmdbId)
                }
            }
            title
        } ?: title
    }

    /** Parses `"S1E2"`, `"S1 E2"`, or `"S2:E5 - Title"` into `(season, episode)`. */
    fun parseSeasonEpisode(episodeLabel: String?): Pair<Int, Int>? {
        if (episodeLabel.isNullOrBlank()) return null
        val match = EPISODE_LABEL_REGEX.find(episodeLabel) ?: return null
        val s = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val e = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        return s to e
    }

    private val EPISODE_LABEL_REGEX = Regex("""[sS](\d+)\s*[:eE\s]\s*[eE]?(\d+)""")
}
