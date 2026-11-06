/*
 * Watchly — My Library screen (spec screens §6, v1 slice).
 *
 * Sections, top to bottom:
 *  - TMDB card: connected state, or the entry point to add the key.
 *  - Continue Watching: in-progress titles with a gold progress bar.
 *  - Watchlist: saved titles as poster cards.
 *  - Downloads: every offline download with status, progress,
 *    offline Play (completed) and Delete. Space used at the top.
 * Everything here is local data — the screen works fully offline.
 */
package com.watchly.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.watchly.app.data.downloads.DownloadRecord
import com.watchly.app.data.downloads.DownloadStatus
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.Title
import com.watchly.app.data.playback.PlaybackProgress
import com.watchly.app.ui.components.PosterCard
import com.watchly.app.ui.theme.WatchlyGold
import com.watchly.app.ui.theme.WatchlyGoldLight
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    onTitleClick: (Title) -> Unit = {},
    onTmdbClick: () -> Unit = {},
    onPlayDownload: (Title, PlayableFile) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val viewModel: LibraryViewModel = viewModel(
        factory = LibraryViewModelFactory(context)
    )
    val state by viewModel.uiState.collectAsState()

    // Fresh data (and live download progress) while the tab is open.
    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.startPolling()
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "My Library",
                    style = MaterialTheme.typography.displayMedium,
                    color = WatchlyGold
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = WatchlyGold
            )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item { TmdbCard(hasKey = state.hasTmdbKey, onClick = onTmdbClick) }

            if (state.continueWatching.isNotEmpty()) {
                item {
                    ContinueWatchingRow(
                        items = state.continueWatching,
                        onClick = { progress ->
                            onTitleClick(
                                titleFromStableKey(
                                    stableKey = progress.key,
                                    name = progress.titleName,
                                    posterUrl = progress.posterUrl,
                                    mediaType = progress.mediaType
                                )
                            )
                        }
                    )
                }
            }

            item {
                WatchlistSection(
                    titles = state.watchlist,
                    onTitleClick = onTitleClick
                )
            }

            item {
                DownloadsSection(
                    downloads = state.downloads,
                    totalBytes = state.downloadsTotalBytes,
                    onPlay = { record ->
                        val path = record.localPath ?: return@DownloadsSection
                        onPlayDownload(
                            titleFromStableKey(
                                stableKey = record.titleKey,
                                name = record.titleName,
                                posterUrl = record.posterUrl,
                                mediaType = record.mediaType
                            ),
                            PlayableFile(
                                url = path,
                                fileName = record.fileName,
                                sizeBytes = record.sizeBytes
                            )
                        )
                    },
                    onDelete = viewModel::deleteDownload
                )
            }
        }
    }
}

// ---------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------

@Composable
private fun TmdbCard(hasKey: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Text(
            text = if (hasKey) "TMDB — Connected ✓" else "Unlock the full catalogue",
            style = MaterialTheme.typography.titleLarge,
            color = WatchlyGold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (hasKey) {
                "Bollywood, Tollywood, Hollywood, Korean and more — " +
                    "updated by TMDB every day."
            } else {
                "Right now you're seeing free public-domain films. Add your " +
                    "free TMDB key to unlock every industry — Bollywood, " +
                    "Tollywood, Hollywood, Korean and more — always up to date."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = WatchlyGold,
                contentColor = Color(0xFF1A1405)
            )
        ) {
            Text(if (hasKey) "Manage TMDB Key" else "Add TMDB Key")
        }
    }
}

@Composable
private fun ContinueWatchingRow(
    items: List<PlaybackProgress>,
    onClick: (PlaybackProgress) -> Unit
) {
    Column {
        SectionHeading("Continue Watching")
        Spacer(Modifier.height(12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { it.key }) { progress ->
                Column(modifier = Modifier.width(120.dp)) {
                    PosterCard(
                        title = titleFromStableKey(
                            stableKey = progress.key,
                            name = progress.titleName,
                            posterUrl = progress.posterUrl,
                            mediaType = progress.mediaType
                        ),
                        onClick = { onClick(progress) }
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        color = WatchlyGold,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                    progress.episodeLabel?.let { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchlistSection(
    titles: List<Title>,
    onTitleClick: (Title) -> Unit
) {
    Column {
        SectionHeading("Watchlist")
        Spacer(Modifier.height(12.dp))
        if (titles.isEmpty()) {
            Text(
                text = "Nothing saved yet — tap “+ Watchlist” on any title to keep it here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(titles, key = { it.stableKey }) { title ->
                    PosterCard(
                        title = title,
                        onClick = { onTitleClick(title) },
                        modifier = Modifier.width(120.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadsSection(
    downloads: List<DownloadRecord>,
    totalBytes: Long,
    onPlay: (DownloadRecord) -> Unit,
    onDelete: (DownloadRecord) -> Unit
) {
    Column {
        SectionHeading("Downloads")
        Spacer(Modifier.height(12.dp))
        if (downloads.isEmpty()) {
            Text(
                text = "No downloads yet. Public-domain films can be saved " +
                    "from their page for offline watching.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        } else {
            Text(
                text = "${downloads.size} download${if (downloads.size == 1) "" else "s"} • " +
                    "${formatBytes(totalBytes)} used • stored inside Watchly only",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                downloads.forEach { record ->
                    DownloadRow(
                        record = record,
                        onPlay = { onPlay(record) },
                        onDelete = { onDelete(record) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    record: DownloadRecord,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(45.dp)
                .height(68.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            if (record.posterUrl != null) {
                AsyncImage(
                    model = record.posterUrl,
                    contentDescription = record.titleName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.titleName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = downloadStatusText(record),
                style = MaterialTheme.typography.bodySmall,
                color = if (record.status == DownloadStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    WatchlyGoldLight
                }
            )
            if (record.isActive) {
                Spacer(Modifier.height(6.dp))
                val fraction = record.progressFraction
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        color = WatchlyGold,
                        trackColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(
                        color = WatchlyGold,
                        trackColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        if (record.status == DownloadStatus.COMPLETED) {
            IconButton(onClick = onPlay) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Play offline",
                    tint = WatchlyGold
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Delete download",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        color = WatchlyGold,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

/** "Downloading 45% • 120 MB of 596 MB" style status line. */
private fun downloadStatusText(record: DownloadRecord): String = when (record.status) {
    DownloadStatus.QUEUED -> "Queued"
    DownloadStatus.RUNNING -> {
        val pct = record.progressFraction?.let {
            " ${String.format(Locale.US, "%.0f", it * 100)}%"
        } ?: ""
        val sizes = if (record.totalBytes > 0) {
            " • ${formatBytes(record.bytesDownloaded)} of ${formatBytes(record.totalBytes)}"
        } else {
            ""
        }
        "Downloading$pct$sizes"
    }
    DownloadStatus.PAUSED -> "Paused — will resume automatically"
    DownloadStatus.COMPLETED -> {
        // Include the quality the user picked (spec §7.2 list shows
        // quality) when the record carries one.
        val quality = record.qualityLabel?.let { "$it • " } ?: ""
        "Downloaded • $quality${formatBytes(record.sizeBytes ?: record.totalBytes)}"
    }
    DownloadStatus.FAILED ->
        "Failed — ${record.failureReason ?: "something went wrong"}"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.US, "%d MB", bytes / 1_000_000)
    bytes >= 1_000 -> String.format(Locale.US, "%d KB", bytes / 1_000)
    bytes > 0 -> "$bytes B"
    else -> "0 MB"
}
