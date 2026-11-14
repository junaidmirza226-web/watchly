/*
 * Watchly — the player's Audio and Quality menus (cycle 9).
 *
 * Audio (§5.6): lists the audio tracks inside the file actually
 * playing — the screen only offers this menu when the file has
 * more than one, so a single-track file never shows a fake choice.
 *
 * Quality (§5.7): lists the real files this title offers on its
 * source (e.g. the Internet Archive's 720p and 360p encodes). With
 * one file the menu says so plainly and lists just that one — no
 * invented "1080p" rows. Picking one keeps the position and is
 * remembered for the title (PlayerViewModel.switchQuality).
 */
package com.watchly.app.ui.player

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.playback.AudioTrackOption
import com.watchly.app.ui.theme.WatchlyGold

@Composable
fun AudioTrackDialog(
    tracks: List<AudioTrackOption>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Audio", color = WatchlyGold) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(tracks, key = { it.id }) { track ->
                    MenuRow(
                        label = track.label,
                        detail = null,
                        selected = track.isSelected,
                        onClick = { onPick(track.id) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun QualityDialog(
    files: List<PlayableFile>,
    currentIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Quality", color = WatchlyGold) },
        text = {
            Column {
                if (files.size == 1) {
                    Text(
                        text = "This title's source offers one quality:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed(files) { index, file ->
                        MenuRow(
                            label = file.qualityLabel ?: file.format ?: file.fileName,
                            detail = listOfNotNull(
                                file.format?.takeIf { it != file.qualityLabel },
                                file.sizeLabel
                            ).joinToString(" • ").ifEmpty { null },
                            selected = index == currentIndex,
                            onClick = { onPick(index) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/** One selectable menu row: gold check + label on the current pick. */
@Composable
private fun MenuRow(
    label: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // 48dp minimum touch target (cycle 11 audit): a
            // label-only row (no detail line) is ~24dp of content
            // + 20dp of padding ≈ 44dp — under the bar.
            .heightIn(min = 48.dp)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = WatchlyGold
            )
        } else {
            Spacer(Modifier.width(24.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) WatchlyGold else Color.Unspecified
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
