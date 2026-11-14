/*
 * Watchly — Lists screen (spec §9.2, screens §6.2).
 *
 * All of the user's lists as cards (name, count, 4-poster
 * collage), a gold "New list" button, and per-card Rename /
 * Delete in the overflow menu. Everything is local data. Reached
 * from My Library; tapping a card opens the List Detail screen.
 */
package com.watchly.app.ui.lists

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchly.app.data.library.UserList
import com.watchly.app.ui.theme.WatchlyGold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onListClick: (UserList) -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: ListsViewModel = viewModel(
        factory = ListsViewModelFactory(context)
    )
    val state by viewModel.uiState.collectAsState()

    // Contents can change while this screen is away (details page
    // "Add to List…"), so re-read every time it is shown.
    LaunchedEffect(Unit) { viewModel.refresh() }

    BackHandler { onBack() }

    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<UserList?>(null) }
    var deleteTarget by remember { mutableStateOf<UserList?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "Lists",
                    style = MaterialTheme.typography.displayMedium,
                    color = WatchlyGold
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
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = WatchlyGold
            )
        )

        Button(
            onClick = { showCreate = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = WatchlyGold,
                contentColor = Color(0xFF1A1405)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = null)
            Text("New list")
        }

        if (state.lists.isEmpty()) {
            Text(
                text = "No lists yet. Make one here, or use “Add to List…” " +
                    "on any title's page.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.lists, key = { it.id }) { list ->
                    ListCard(
                        list = list,
                        onClick = { onListClick(list) },
                        modifier = Modifier.fillMaxWidth(),
                        trailing = {
                            ListOverflowMenu(
                                listName = list.name,
                                onRename = { renameTarget = list },
                                onDelete = { deleteTarget = list }
                            )
                        }
                    )
                }
            }
        }
    }

    if (showCreate) {
        ListNameDialog(
            heading = "New list",
            confirmLabel = "Create",
            onConfirm = { name ->
                viewModel.createList(name)
                showCreate = false
            },
            onDismiss = { showCreate = false }
        )
    }
    renameTarget?.let { list ->
        ListNameDialog(
            heading = "Rename list",
            confirmLabel = "Rename",
            initialName = list.name,
            onConfirm = { name ->
                viewModel.renameList(list.id, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }
    deleteTarget?.let { list ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = {
                Text(
                    text = "Delete “${list.name}”?",
                    style = MaterialTheme.typography.titleLarge,
                    color = WatchlyGold
                )
            },
            text = {
                Text(
                    text = "The list and its ${list.size} " +
                        "title${if (list.size == 1) "" else "s"} go away. " +
                        "The titles themselves stay in your library.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteList(list.id)
                        deleteTarget = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel", color = WatchlyGold)
                }
            }
        )
    }
}

/** Per-card "⋮" menu: Rename / Delete. The button's label names
 * its list — a bare "List options" repeats identically on every
 * card, which is the duplicate-label class Test Lab flags. */
@Composable
private fun ListOverflowMenu(
    listName: String,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(
            imageVector = Icons.Filled.MoreVert,
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
