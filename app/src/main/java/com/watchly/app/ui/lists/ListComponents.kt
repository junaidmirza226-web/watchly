/*
 * Watchly — shared list UI pieces (spec §9.2, screens §6/§6.2):
 * the list card with its 4-poster collage, used by My Library and
 * the Lists screen, and the name dialog used for create / rename.
 * Black + gold theme throughout.
 */
package com.watchly.app.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.watchly.app.data.library.UserList
import com.watchly.app.ui.theme.WatchlyGold

/**
 * One list as a card: 2x2 poster collage (monogram fallbacks like
 * everywhere else), name, and title count. [trailing] hosts the
 * overflow menu on the Lists screen; My Library passes none.
 */
@Composable
fun ListCard(
    list: UserList,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ListCollage(list = list)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = list.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when (list.size) {
                    0 -> "No titles yet"
                    1 -> "1 title"
                    else -> "${list.size} titles"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        trailing?.invoke()
    }
}

/**
 * 2x2 collage of the first four posters; gold list icon when empty.
 *
 * No semantics of its own (cycle 8): the collage sits in the clickable
 * list row, whose merged node already speaks the list name from the
 * adjacent text — the old "${list.name} cover" description made
 * TalkBack say the name twice in one node (the speakable-duplicate
 * class fixed for posters/thumbs in cycles 5-6). The cell posters
 * below are decorative parts of the row.
 */
@Composable
private fun ListCollage(list: UserList) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        if (list.titles.isEmpty()) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.List,
                contentDescription = null,
                tint = WatchlyGold,
                modifier = Modifier.size(26.dp)
            )
        } else {
            // Fixed 28dp cells (2x2 = the 56dp box): deliberately
            // no weights/fill tricks — the simplest layout that can
            // render, identical on every screen.
            Column {
                listOf(0, 1).forEach { row ->
                    Row {
                        listOf(0, 1).forEach { col ->
                            val title = list.titles.getOrNull(row * 2 + col)
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                // Letter fallback when the poster
                                // is missing OR fails to load
                                // (Phase D) — never a blank cell.
                                var cellFailed by remember(title?.posterUrl) {
                                    mutableStateOf(false)
                                }
                                if (title?.posterUrl != null && !cellFailed) {
                                    AsyncImage(
                                        model = title.posterUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        onError = { cellFailed = true },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else if (title != null) {
                                    Text(
                                        text = title.name.take(1).uppercase(),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = WatchlyGold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Name entry dialog for creating or renaming a list. Confirm is
 * disabled for a blank name — an unnamed list is never stored.
 */
@Composable
fun ListNameDialog(
    heading: String,
    confirmLabel: String,
    initialName: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = heading,
                style = MaterialTheme.typography.titleLarge,
                color = WatchlyGold
            )
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("List name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) {
                Text(confirmLabel, color = WatchlyGold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = WatchlyGold)
            }
        }
    )
}
