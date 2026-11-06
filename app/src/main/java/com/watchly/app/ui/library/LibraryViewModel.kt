/*
 * Watchly — My Library state holder (spec screens §6, v1 slice).
 *
 * My Library is everything that belongs to the user: the TMDB key
 * entry point, Continue Watching (PlaybackRepository), the Watchlist
 * (WatchlistRepository) and Downloads (DownloadsRepository). All
 * sources are local — this screen works fully offline.
 *
 * Download progress: while the screen is open and any download is
 * active, [startPolling] refreshes statuses every 2 seconds. The
 * loop stops by itself as soon as nothing is active (and when the
 * ViewModel is cleared), so there is no permanent background ticker.
 */
package com.watchly.app.ui.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.watchly.app.data.downloads.DownloadRecord
import com.watchly.app.data.downloads.DownloadsRepository
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.data.playback.PlaybackProgress
import com.watchly.app.data.playback.PlaybackRepository
import com.watchly.app.data.settings.TmdbKeyRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class LibraryUiState(
    val isLoading: Boolean = true,
    val hasTmdbKey: Boolean = false,
    val continueWatching: List<PlaybackProgress> = emptyList(),
    val watchlist: List<Title> = emptyList(),
    val downloads: List<DownloadRecord> = emptyList(),
    val downloadsTotalBytes: Long = 0
) {
    /** True while any download can still make progress. */
    val hasActiveDownloads: Boolean
        get() = downloads.any { it.isActive }
}

class LibraryViewModel(
    private val watchlist: WatchlistRepository,
    private val playback: PlaybackRepository,
    private val downloads: DownloadsRepository,
    private val tmdbKeys: TmdbKeyRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init {
        refresh()
    }

    /** Reloads every section from the local repositories. */
    fun refresh() {
        val downloadList = downloads.getDownloads()
        _uiState.update {
            it.copy(
                isLoading = false,
                hasTmdbKey = tmdbKeys.hasKey(),
                continueWatching = playback.getRecentProgress(),
                watchlist = watchlist.getWatchlist(),
                downloads = downloadList,
                downloadsTotalBytes = downloads.totalDownloadedBytes()
            )
        }
    }

    /** Deletes one download (file + record), then refreshes. */
    fun deleteDownload(record: DownloadRecord) {
        downloads.delete(record)
        refresh()
    }

    /**
     * Refreshes download statuses every 2 s while any download is
     * active. Safe to call repeatedly — only one loop ever runs, and
     * it ends when no active downloads remain.
     */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                refresh()
                if (!_uiState.value.hasActiveDownloads) break
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    companion object {
        const val POLL_INTERVAL_MS = 2_000L
    }
}

/** Factory so the nav destination can create the ViewModel with a Context. */
class LibraryViewModelFactory(
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
            val app = context.applicationContext
            return LibraryViewModel(
                watchlist = WatchlistRepository(app),
                playback = PlaybackRepository(app),
                downloads = DownloadsRepository(app),
                tmdbKeys = TmdbKeyRepository(app)
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
    }
}

/**
 * Rebuilds a displayable [Title] from a stored stable key + snapshot
 * fields (same recovery the Watchlist store does for its entries):
 * "tmdb:123" → tmdbId 123, "archive:<id>" → archiveId, and so on.
 */
fun titleFromStableKey(
    stableKey: String,
    name: String,
    posterUrl: String?,
    mediaType: MediaType
): Title {
    val base = Title(name = name, posterUrl = posterUrl, mediaType = mediaType)
    val parts = stableKey.split(":", limit = 2)
    if (parts.size != 2) return base
    return when (parts[0]) {
        "tmdb" -> base.copy(tmdbId = parts[1].toIntOrNull())
        "tvmaze" -> base.copy(tvmazeId = parts[1].toIntOrNull())
        "anilist" -> base.copy(anilistId = parts[1].toIntOrNull())
        "archive" -> base.copy(archiveId = parts[1])
        "imdb" -> base.copy(imdbId = parts[1])
        else -> base
    }
}
