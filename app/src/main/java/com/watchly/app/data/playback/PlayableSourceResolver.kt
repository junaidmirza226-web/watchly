/*
 * Watchly — resolves what the player can actually start for a
 * title. Prioritizes local files, completed downloads, and fast multi-source
 * cloud streaming providers (VidSrc, SuperStream, 2Embed, AutoEmbed, Embed.su, etc.)
 * over slow legacy archive sources.
 */
package com.watchly.app.data.playback

import com.watchly.app.data.downloads.DownloadStatus
import com.watchly.app.data.downloads.DownloadsRepository
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.PlayableMediaHeaders
import com.watchly.app.data.model.Title
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.providers.StreamAggregator

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
                files = listOf(
                    PlayableFile(url = uri, fileName = title.name)
                )
            )
        }
        // 1) A finished download is the best source: offline, fast.
        val record = downloads?.recordForTitle(title.stableKey)
        if (record != null && record.status == DownloadStatus.COMPLETED) {
            val path = record.localPath
            if (path != null && java.io.File(path).exists()) {
                return ResolvedPlayable(
                    files = listOf(
                        PlayableFile(
                            url = path,
                            fileName = record.fileName,
                            sizeBytes = record.sizeBytes
                        )
                    )
                )
            }
        }
        // 2) Fast multi-source streaming providers (VidSrc, AutoEmbed, SuperStream, 2Embed, Embed.su)
        if (title.imdbId != null || title.tmdbId != null || title.name.isNotBlank()) {
            val streamSources = runCatching {
                StreamAggregator.getStreams(title, season = season, episode = episode)
            }.getOrDefault(emptyList())

            if (streamSources.isNotEmpty()) {
                val directFiles = mutableListOf<PlayableFile>()
                var firstEmbedUrl: String? = null

                for (stream in streamSources) {
                    PlayableMediaHeaders.register(stream.url, stream.headers)
                    val isDirectStream = stream.url.contains(".m3u8", ignoreCase = true) ||
                        stream.url.contains(".mp4", ignoreCase = true) ||
                        stream.url.contains(".mkv", ignoreCase = true)
                    val isWebEmbed = !isDirectStream

                    if (isWebEmbed) {
                        if (firstEmbedUrl == null) firstEmbedUrl = stream.url
                        directFiles.add(
                            PlayableFile(
                                url = stream.url,
                                fileName = "${title.name} (${stream.providerName} - Web Player)",
                                format = "EMBED",
                                qualityLabel = "HD Web • ${stream.providerName}",
                                headers = stream.headers
                            )
                        )
                    } else {
                        directFiles.add(
                            PlayableFile(
                                url = stream.url,
                                fileName = "${title.name} (${stream.providerName} - ${stream.quality})",
                                format = if (stream.isHls) "HLS" else "MP4",
                                qualityLabel = "${stream.quality} • ${stream.providerName}",
                                headers = stream.headers
                            )
                        )
                    }
                }

                if (directFiles.isNotEmpty()) {
                    return ResolvedPlayable(
                        files = directFiles,
                        webEmbedUrl = firstEmbedUrl
                    )
                }
            }
        }
        // 3) Internet Archive files, fetched live (fallback).
        val archiveId = title.archiveId
        if (archiveId != null) {
            val files = runCatching {
                ArchiveApi.fetchPlayableFiles(archiveId)
            }.getOrDefault(emptyList())
            if (files.isNotEmpty()) return ResolvedPlayable(files = files)
        }
        // 4) Official YouTube video (embedded player).
        val youtubeId = title.youtubeId
        if (youtubeId != null) {
            return ResolvedPlayable(youtubeVideoId = youtubeId)
        }
        return ResolvedPlayable.NOT_PLAYABLE
    }
}
