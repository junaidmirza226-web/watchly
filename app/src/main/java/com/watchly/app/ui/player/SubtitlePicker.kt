/*
 * Watchly — the player's subtitle picker (cycle 4, OpenSubtitles).
 *
 * A plain dialog over the player: it renders exactly the states
 * PlayerViewModel produces — searching, a message (no key / none
 * found / quota spent / provider down), the result list, and the
 * "turn off" row when a track is attached. Picking a row downloads
 * and attaches it (see PlayerViewModel.pickSubtitle).
 */
package com.watchly.app.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.watchly.app.data.model.SubtitleBackground
import com.watchly.app.data.model.SubtitleResult
import com.watchly.app.data.model.SubtitleStyle
import com.watchly.app.data.playback.SubtitleShifter
import com.watchly.app.ui.theme.WatchlyGold

@Composable
fun SubtitlePickerDialog(
    state: PlayerUiState,
    onPick: (SubtitleResult) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    onTextSizePick: (Float) -> Unit = {},
    onBackgroundPick: (SubtitleBackground) -> Unit = {},
    onOffsetChange: (Long) -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Subtitles", color = WatchlyGold) },
        text = {
            Column {
                if (state.subtitlesLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = WatchlyGold,
                            modifier = Modifier.height(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Working…",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                state.subtitleMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.subtitleResults.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(state.subtitleResults, key = { it.fileId }) { result ->
                            SubtitleRow(result = result, onClick = { onPick(result) })
                        }
                    }
                }
                if (state.activeSubtitleLabel != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "On: ${state.activeSubtitleLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = WatchlyGold
                    )
                }
                // Cycle 9 (§6.3): sync controls — only meaningful
                // with a subtitle attached, so they only exist then.
                if (state.activeSubtitleLabel != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Subtitle sync",
                        style = MaterialTheme.typography.labelLarge,
                        color = WatchlyGold
                    )
                    Text(
                        text = formatSubtitleOffset(state.subtitleOffsetMs) +
                            " — saved for this title",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                onOffsetChange(
                                    state.subtitleOffsetMs - SubtitleShifter.QUICK_STEP_MS
                                )
                            }
                        ) { Text("−0.5s") }
                        TextButton(
                            onClick = {
                                onOffsetChange(
                                    state.subtitleOffsetMs - SubtitleShifter.FINE_STEP_MS
                                )
                            }
                        ) { Text("−0.1s") }
                        TextButton(
                            onClick = {
                                onOffsetChange(
                                    state.subtitleOffsetMs + SubtitleShifter.FINE_STEP_MS
                                )
                            }
                        ) { Text("+0.1s") }
                        TextButton(
                            onClick = {
                                onOffsetChange(
                                    state.subtitleOffsetMs + SubtitleShifter.QUICK_STEP_MS
                                )
                            }
                        ) { Text("+0.5s") }
                    }
                    if (state.subtitleOffsetMs != 0L) {
                        TextButton(onClick = { onOffsetChange(0L) }) {
                            Text("Reset sync")
                        }
                    }
                }
                // Cycle 9 (§6.2): appearance — applies at once to
                // whatever subtitle is on screen, and is kept.
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Subtitle appearance",
                    style = MaterialTheme.typography.labelLarge,
                    color = WatchlyGold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Text size",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SubtitleStyle.TEXT_SIZE_STEPS_SP.forEachIndexed { index, size ->
                        FilterChip(
                            selected = state.subtitleStyle.textSizeSp == size,
                            onClick = { onTextSizePick(size) },
                            label = { Text(SubtitleStyle.TEXT_SIZE_LABELS[index]) },
                            colors = goldChipColors()
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Background",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = state.subtitleStyle.background ==
                            SubtitleBackground.NONE,
                        onClick = { onBackgroundPick(SubtitleBackground.NONE) },
                        label = { Text("None") },
                        colors = goldChipColors()
                    )
                    FilterChip(
                        selected = state.subtitleStyle.background ==
                            SubtitleBackground.SEMI,
                        onClick = { onBackgroundPick(SubtitleBackground.SEMI) },
                        label = { Text("Semi-black") },
                        colors = goldChipColors()
                    )
                    FilterChip(
                        selected = state.subtitleStyle.background ==
                            SubtitleBackground.SOLID,
                        onClick = { onBackgroundPick(SubtitleBackground.SOLID) },
                        label = { Text("Solid black") },
                        colors = goldChipColors()
                    )
                }
            }
        },
        confirmButton = {
            if (state.activeSubtitleLabel != null) {
                TextButton(onClick = onClear) { Text("Turn off") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/** One downloadable subtitle: language, release, popularity. */
@Composable
private fun SubtitleRow(result: SubtitleResult, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Text(
            text = result.displayLabel,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2
        )
        val meta = buildList {
            add(result.language.uppercase())
            if (result.downloadCount > 0) add("↓ ${result.downloadCount}")
            result.rating?.let { add("★ ${trimRating(it)}") }
            if (result.hearingImpaired) add("SDH")
        }.joinToString(" • ")
        Text(
            text = meta,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 8.0 not 8.0000001: provider ratings arrive as raw doubles. */
private fun trimRating(rating: Double): String =
    if (rating % 1.0 == 0.0) rating.toInt().toString() else rating.toString()

/** "+0.5s" / "−1.2s" / "In sync" for the sync section (§6.3). */
private fun formatSubtitleOffset(offsetMs: Long): String = when {
    offsetMs == 0L -> "In sync"
    offsetMs > 0 -> "+%.1fs".format(offsetMs / 1000.0)
    else -> "−%.1fs".format(-offsetMs / 1000.0)
}

/** The picker's chips in the app's black + gold (selected = gold). */
@Composable
private fun goldChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = WatchlyGold,
    selectedLabelColor = Color(0xFF1A1405)
)
