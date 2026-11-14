/*
 * Watchly — resolves what the player can actually start for a
 * title (cycle 5). Used by autoplay-next (the player must know the
 * next item REALLY plays before offering it) and by List Detail's
 * Play button.
 *
 * Order of truth:
 *  1. The user's on-device SAF file.
 *  2. A completed download of the title (offline beats streaming).
 *  3. The title's Internet Archive files (fetched live).
 *  4. Multi-source streaming providers (TMDB / IMDb live streams).
 *  5. An official-YouTube video id (plays in the official player).
 * Anything else resolves to "not playable" — the badge rule alone
 * is never trusted for a continuation the user didn't tap.
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
    val youtubeVideoId: String? = null
) {
    val isPlayable: Boolean
        get() = files.isNotEmpty() || youtubeVideoId != null

    companion object {
        val NOT_PLAYABLE = ResolvedPlayable()
    }
}

object PlayableSourceResolver {

    /** Resolves [title]; [downloads] may be null (tests, previews). */
    suspend fun resolve(
        title: Title,
        downloads: DownloadsRepository?
    ): ResolvedPlayable {
        // 0) The user's own on-device file (cycle 5): direct source.
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
        // 2) Internet Archive files, fetched live.
        val archiveId = title.archiveId
        if (archiveId != null) {
            val files = runCatching {
                ArchiveApi.fetchPlayableFiles(archiveId)
            }.getOrDefault(emptyList())
            if (files.isNotEmpty()) return ResolvedPlayable(files = files)
        }
        // 2.5) Multi-source streaming providers (TMDB / IMDb)
        if (title.tmdbId != null || title.imdbId != null) {
            val streamSources = runCatching {
                StreamAggregator.getStreams(title)
            }.getOrDefault(emptyList())
            if (streamSources.isNotEmpty()) {
                val files = streamSources.map { stream ->
                    PlayableMediaHeaders.register(stream.url, stream.headers)
                    PlayableFile(
                        url = stream.url,
                        fileName = "${title.name} (${stream.providerName} - ${stream.quality})",
                        format = if (stream.isHls) "HLS" else "MP4",
                        qualityLabel = "${stream.quality} • ${stream.providerName}",
                        headers = stream.headers
                    )
                }
                return ResolvedPlayable(files = files)
            }
        }
        // 3) Official YouTube video (embedded player).
        val youtubeId = title.youtubeId
        if (youtubeId != null) {
            return ResolvedPlayable(youtubeVideoId = youtubeId)
        }
        return ResolvedPlayable.NOT_PLAYABLE
    }
}
