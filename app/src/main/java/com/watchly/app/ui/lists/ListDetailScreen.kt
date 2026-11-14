/*
 * Watchly — List Detail screen (spec §9.2, screens §6.2).
 *
 * One list, in the user's own order: poster rows with the honest
 * Playable / Info only badge, tap → Title Details, remove per
 * item. Edit mode adds drag handles (long-press the handle, drag
 * — every swap is persisted immediately, so the order survives
 * leaving mid-drag). "Add from Watchlist" offers the saved titles
 * that aren't in the list yet. Rename / Delete live in the
 * overflow menu; deleting pops back to where the user came from.
 */
package com.watchly.app.ui.lists

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.watchly.app.data.model.Title
import com.watchly.app.data.model.playability
import com.watchly.app.ui.components.PlayabilityBadge
import com.watchly.app.ui.theme.WatchlyGold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListDetailScreen(
    listId: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onTitleClick: (Title) -> Unit = {},
    /** Cycle 5: a resolved play request from a row's Play button. */
    onPlayRequest: (com.watchly.app.ui.player.PlaybackRequest) -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: ListDetailViewModel = viewModel(
        key = "list-detail-$listId",
        factory = ListDetailViewModelFactory(listId, context)
    )
    val state by viewModel.uiState.collectAsState()
    val list = state.list

    // The list is gone (deleted here, or a stale id): leave the
    // same way back would — never sit on a dead screen.
    LaunchedEffect(list) {
        if (list == null) onBack()
    }

    // One-shot play event (cycle 5): hand it to nav, then clear.
    val playRequest by viewModel.playRequest.collectAsState()
    LaunchedEffect(playRequest) {
        playRequest?.let { request ->
            onPlayRequest(request)
            viewModel.playRequestConsumed()
        }
    }

    BackHandler { onBack() }

    var editMode by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showAddPicker by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = list?.name ?: "List",
                    style = MaterialTheme.typography.displayMedium,
                    color = WatchlyGold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = WatchlyGold
                    )
                }
            },
            actions = {
                if (list != null && list.titles.isNotEmpty()) {
                    TextButton(onClick = { editMode = !editMode }) {
                        Text(
                            text = if (editMode) "Done" else "Edit",
                            color = WatchlyGold
                        )
                    }
                }
                ListDetailOverflowMenu(
                    listName = list?.name ?: "",
                    onRename = { showRename = true },
                    onDelete = { showDeleteConfirm = true }
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = WatchlyGold
            )
        )

        if (list == null) {
            // Briefly visible only while the pop above runs.
            return@Column
        }

        if (editMode) {
            Text(
                text = "Long-press a handle and drag to reorder. " +
                    "The new order is saved as you go.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        if (list.titles.isEmpty()) {
            Text(
                text = "This list is empty. Add titles with “Add to " +
                    "List…” on any title's page, or from your Watchlist below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        } else {
            DraggableTitleList(
                titles = list.titles,
                editMode = editMode,
                onTitleClick = onTitleClick,
                onRemove = viewModel::removeTitle,
                onMove = viewModel::moveTitle,
                onPlay = viewModel::playFrom,
                modifier = Modifier.weight(1f)
            )
        }

        OutlinedButton(
            onClick = { showAddPicker = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text("Add from Watchlist", color = WatchlyGold)
        }
    }

    if (showRename && list != null) {
        ListNameDialog(
            heading = "Rename list",
            confirmLabel = "Rename",
            initialName = list.name,
            onConfirm = { name ->
                viewModel.rename(name)
                showRename = false
            },
            onDismiss = { showRename = false }
        )
    }
    if (showDeleteConfirm && list != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text(
                    text = "Delete “${list.name}”?",
                    style = MaterialTheme.typography.titleLarge,
                    color = WatchlyGold
                )
            },
            text = {
                Text(
                    text = "The list goes away. The titles themselves " +
                        "stay in your library.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteList()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = WatchlyGold)
                }
            }
        )
    }
    if (showAddPicker) {
        AddFromWatchlistDialog(
            candidates = state.addableFromWatchlist,
            onAdd = viewModel::addFromWatchlist,
            onDismiss = { showAddPicker = false }
        )
    }
}

// ---------------------------------------------------------------------
// Ordered, drag-reorderable rows
// ---------------------------------------------------------------------

@Composable
private fun DraggableTitleList(
    titles: List<Title>,
    editMode: Boolean,
    onTitleClick: (Title) -> Unit,
    onRemove: (Title) -> Unit,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onPlay: (Title) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var draggingOffset by remember { mutableFloatStateOf(0f) }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        itemsIndexed(titles, key = { _, title -> title.stableKey }) { _, title ->
            val isDragging = draggingKey == title.stableKey
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ROW_HEIGHT)
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (isDragging) draggingOffset else 0f
                    }
                    .background(
                        if (isDragging) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.background
                        }
                    )
                    .then(
                        if (editMode) Modifier else Modifier.clickable {
                            onTitleClick(title)
                        }
                    )
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Poster thumb (monogram fallback, like the cards)
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (title.posterUrl != null) {
                        AsyncImage(
                            model = title.posterUrl,
                            // Decorative: the name is rendered as text
                            // right beside the thumb — a matching image
                            // description would be spoken twice
                            // (Test Lab content labeling, cycle 6).
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = title.name.take(1).uppercase(),
                            style = MaterialTheme.typography.titleLarge,
                            color = WatchlyGold
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        title.year?.let { year ->
                            Text(
                                text = year,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        PlayabilityBadge(playability = title.playability())
                    }
                }
                // Play (cycle 5): only for titles badged playable —
                // playback then continues down the list (autoplay).
                if (!editMode &&
                    title.playability() == com.watchly.app.data.model.Playability.PLAYABLE
                ) {
                    IconButton(onClick = { onPlay(title) }) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Play ${title.name}",
                            tint = WatchlyGold
                        )
                    }
                }
                IconButton(onClick = { onRemove(title) }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        // Names the title: a bare "Remove from
                        // list" repeats identically on every row
                        // (duplicate-label class, Test Lab run 9).
                        contentDescription = "Remove ${title.name} from list",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (editMode) {
                    Box(
                        modifier = Modifier
                            // 48dp drag target (Test Lab run 4:
                            // touch target) — the glyph stays small,
                            // the grip area meets the minimum.
                            .size(48.dp)
                            .pointerInput(title.stableKey) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingKey = title.stableKey
                                        draggingOffset = 0f
                                    },
                                    onDragEnd = {
                                        draggingKey = null
                                        draggingOffset = 0f
                                    },
                                    onDragCancel = {
                                        draggingKey = null
                                        draggingOffset = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        draggingOffset += dragAmount.y
                                        val visible =
                                            listState.layoutInfo.visibleItemsInfo
                                        val draggingItem = visible.firstOrNull {
                                            it.key == title.stableKey
                                        } ?: return@detectDragGesturesAfterLongPress
                                        val center = draggingItem.offset +
                                            draggingOffset + draggingItem.size / 2
                                        val target = visible.firstOrNull {
                                            it.key != title.stableKey &&
                                                center >= it.offset &&
                                                center < it.offset + it.size
                                        } ?: return@detectDragGesturesAfterLongPress
                                        onMove(draggingItem.index, target.index)
                                        // Keep the dragged row visually
                                        // continuous across the swap.
                                        draggingOffset +=
                                            draggingItem.offset - target.offset
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DragHandle,
                            contentDescription = "Drag to reorder",
                            tint = WatchlyGold
                        )
                    }
                }
            }
        }
    }
}

private val ROW_HEIGHT = 84.dp

// ---------------------------------------------------------------------
// Overflow menu + add picker
// ---------------------------------------------------------------------

@Composable
private fun ListDetailOverflowMenu(
    listName: String,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(
            imageVector = Icons.Filled.MoreVert,
            // Names its list (cycle 12 a11y): a bare "List options"
            // is the generic-label class Test Lab's labeling check
            // flags — the Lists screen's twin of this menu was
            // fixed the same way in cycle 10.
            contentDescription = "Options for $listName",
            tint = WatchlyGold
        )
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
    ) {
        DropdownMenuItem(
            text = { Text("Rename") },
            onClick = {
                expanded = false
                onRename()
            }
        )
        DropdownMenuItem(
            text = { Text("Delete list") },
            onClick = {
                expanded = false
                onDelete()
            }
        )
    }
}

@Composable
private fun AddFromWatchlistDialog(
    candidates: List<Title>,
    onAdd: (Title) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Add from Watchlist",
                style = MaterialTheme.typography.titleLarge,
                color = WatchlyGold
            )
        },
        text = {
            if (candidates.isEmpty()) {
                Text(
                    text = "Nothing to add — your Watchlist is empty, or " +
                        "everything in it is already in this list.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(candidates, key = { it.stableKey }) { title ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onAdd(title) }
                                // 48dp minimum touch target (cycle 11
                                // audit): a candidate with no year
                                // line is ~24dp of content + 20dp of
                                // padding ≈ 44dp — under the bar.
                                .heightIn(min = 48.dp)
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                title.year?.let { year ->
                                    Text(
                                        text = year,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                text = "Add",
                                style = MaterialTheme.typography.labelLarge,
                                color = WatchlyGold
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = WatchlyGold)
            }
        }
    )
}
