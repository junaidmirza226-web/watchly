/*
 * Watchly — My Library screen (spec screens §6, v1 slice).
 *
 * Sections, top to bottom:
 *  - Profiles card (cycle 12, §8): who's watching, and the switcher.
 *  - TMDB card: connected state, or the entry point to add the key.
 *  - Continue Watching: in-progress titles with a gold progress bar.
 *  - Watchlist: saved titles as poster cards.
 *  - Downloads: every offline download with status, progress,
 *    offline Play (completed) and Delete. Space used at the top.
 * Everything here is local data — the screen works fully offline.
 */
package com.watchly.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.watchly.app.data.downloads.DownloadRecord
import com.watchly.app.data.downloads.DownloadRules
import com.watchly.app.data.downloads.DownloadStatus
import com.watchly.app.data.library.ProfilesRepository
import com.watchly.app.data.library.UserList
import com.watchly.app.data.library.WatchlyProfile
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.Title
import com.watchly.app.data.playback.PlaybackProgress
import com.watchly.app.ui.components.PosterCard
import com.watchly.app.ui.lists.ListCard
import com.watchly.app.ui.theme.WatchlyGold
import com.watchly.app.ui.theme.WatchlyGoldLight
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    onTitleClick: (Title) -> Unit = {},
    onProfileSwitched: () -> Unit = {},
    onTmdbClick: () -> Unit = {},
    onApiKeysClick: () -> Unit = {},
    onListsClick: () -> Unit = {},
    onListClick: (UserList) -> Unit = {},
    onPlayDownload: (Title, PlayableFile) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val viewModel: LibraryViewModel = viewModel(
        factory = LibraryViewModelFactory(context)
    )
    val state by viewModel.uiState.collectAsState()

    // On-device files (cycle 5): SAF pickers — individual videos,
    // or a whole folder. No storage permission is ever requested.
    val pickFilesLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) viewModel.onLocalFilesPicked(uris) }
    val pickFolderLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.onLocalFolderPicked(it) } }

    // Backup & export (cycle 10, §15.2): the same SAF pattern —
    // the user picks where the backup file goes and which file to
    // restore; Watchly never asks for storage permission.
    val exportBackupLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.exportBackup(it) } }
    val importBackupLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importBackup(it) } }

    // Fresh data (and live download progress) while the tab is open.
    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.startPolling()
    }

    // A backup restore that changed the active profile (cycle 12)
    // leaves this screen's stores pointing at the old profile —
    // reset navigation exactly like a manual switch.
    LaunchedEffect(state.activeProfileChangedByRestore) {
        if (state.activeProfileChangedByRestore) onProfileSwitched()
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
            item {
                ProfilesCard(
                    profiles = state.profiles,
                    activeProfile = state.activeProfile,
                    profileMessage = state.profileMessage,
                    onSwitchProfile = { profileId ->
                        if (viewModel.switchProfile(profileId)) {
                            onProfileSwitched()
                        }
                    },
                    onCreateProfile = viewModel::createProfile,
                    onRenameProfile = viewModel::renameProfile,
                    onDeleteProfile = { profileId ->
                        if (viewModel.deleteProfile(profileId)) {
                            onProfileSwitched()
                        }
                    }
                )
            }

            item { TmdbCard(hasKey = state.hasTmdbKey, onClick = onTmdbClick) }

            item { ApiKeysCard(onClick = onApiKeysClick) }

            item {
                PlaybackSettingsCard(
                    autoplayNext = state.autoplayNext,
                    onAutoplayChange = viewModel::setAutoplayNext
                )
            }

            item {
                DataSaverCard(
                    dataSaver = state.dataSaver,
                    onDataSaverChange = viewModel::setDataSaver
                )
            }

            item {
                YourDataCard(
                    backupMessage = state.backupMessage,
                    onExport = {
                        exportBackupLauncher.launch(
                            com.watchly.app.data.backup.BackupRepository
                                .DEFAULT_FILE_NAME
                        )
                    },
                    onImport = {
                        importBackupLauncher.launch(arrayOf("application/json"))
                    }
                )
            }

            item {
                DownloadSettingsCard(
                    settings = state.downloadSettings,
                    onWifiOnlyChange = viewModel::setDownloadWifiOnly,
                    onPauseOnLowBatteryChange =
                        viewModel::setDownloadPauseOnLowBattery,
                    onStorageCapChange = viewModel::setDownloadStorageCap
                )
            }

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

            // Stable key (cycle 8): item indices above this section
            // shifted when the Downloads settings card was added, and
            // LazyColumn reuses slots positionally while settling —
            // on slow software rendering the section transiently
            // composed as the wrong section during tab entry
            // (DetailsNavigationTest failures, CI runs 42347944271+).
            // A key pins this slot to the Watchlist section itself.
            item(key = "watchlist") {
                WatchlistSection(
                    titles = state.watchlist,
                    onTitleClick = onTitleClick
                )
            }

            item {
                ListsSection(
                    lists = state.lists,
                    onListClick = onListClick,
                    onSeeAllClick = onListsClick
                )
            }

            item {
                DownloadsSection(
                    downloads = state.downloads,
                    totalBytes = state.downloadsTotalBytes,
                    speeds = state.downloadSpeeds,
                    onPause = viewModel::pauseDownload,
                    onResume = viewModel::resumeDownload,
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

            item {
                LocalFilesSection(
                    localTitles = state.localTitles,
                    onPlay = { title ->
                        val uri = title.localUri
                        if (uri != null) {
                            onPlayDownload(
                                title,
                                PlayableFile(url = uri, fileName = title.name)
                            )
                        }
                    },
                    onRemove = viewModel::removeLocalTitle,
                    onAddFiles = {
                        pickFilesLauncher.launch(arrayOf("video/*"))
                    },
                    onAddFolder = { pickFolderLauncher.launch(null) }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------

/** "#D4AF37" → opaque Compose colour (gold when unparseable). */
private fun profileColor(hex: String): Color {
    val rgb = hex.removePrefix("#").toLongOrNull(16) ?: 0xD4AF37L
    return Color((0xFF000000L or rgb).toInt())
}

/**
 * A profile's avatar: its colour with the name's first letter —
 * the same monogram idea as the list collages. Pure decoration:
 * the profile's name is always rendered as text beside it.
 */
@Composable
private fun ProfileAvatar(profile: WatchlyProfile, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(profileColor(profile.colorHex)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = profile.monogram,
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF1A1405)
        )
    }
}

/**
 * Profiles (spec §8, cycle 12): who's watching, and the way to
 * switch. The copy is explicit about the boundary — a profile
 * owns its watchlist, lists, progress and ratings; downloads and
 * API keys belong to the phone and stay shared.
 */
@Composable
internal fun ProfilesCard(
    profiles: List<WatchlyProfile>,
    activeProfile: WatchlyProfile?,
    profileMessage: String?,
    onSwitchProfile: (String) -> Unit,
    onCreateProfile: (String, Int) -> Unit,
    onRenameProfile: (String, String) -> Unit,
    onDeleteProfile: (String) -> Unit
) {
    var showSwitcher by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<WatchlyProfile?>(null) }
    var deleteTarget by remember { mutableStateOf<WatchlyProfile?>(null) }

    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Text(
            text = "Profiles",
            style = MaterialTheme.typography.titleLarge,
            color = WatchlyGold
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            activeProfile?.let { ProfileAvatar(it, 44.dp) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = activeProfile?.name ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Watching as this profile",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Each profile keeps its own watchlist, lists, " +
                "progress and ratings. Downloads and API keys are " +
                "shared on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { showSwitcher = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = WatchlyGold,
                contentColor = Color(0xFF1A1405)
            )
        ) {
            Text("Switch profile")
        }
        profileMessage?.let { message ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = WatchlyGoldLight
            )
        }
    }

    if (showSwitcher) {
        AlertDialog(
            onDismissRequest = { showSwitcher = false },
            title = {
                Text(
                    text = "Switch profile",
                    style = MaterialTheme.typography.titleLarge,
                    color = WatchlyGold
                )
            },
            text = {
                Column {
                    profiles.forEach { profile ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable {
                                    showSwitcher = false
                                    onSwitchProfile(profile.id)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProfileAvatar(profile, 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = profile.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (profile.id == activeProfile?.id) {
                                Text(
                                    text = "Current",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = WatchlyGold
                                )
                            }
                        }
                        Row {
                            Spacer(Modifier.width(52.dp))
                            // 48dp floor + a spoken label that names
                            // its profile (cycle 13): bare "Rename" /
                            // "Delete" repeat identically under every
                            // profile — the duplicate-label class —
                            // and a bare TextButton lays out 40dp
                            // tall (measured; the cycle-4 "See all"
                            // floor, applied here from the start the
                            // dialogs should have had).
                            TextButton(
                                onClick = {
                                    showSwitcher = false
                                    renameTarget = profile
                                },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .semantics {
                                        contentDescription =
                                            "Rename ${profile.name}"
                                    }
                            ) {
                                Text("Rename", color = WatchlyGold)
                            }
                            if (profiles.size > 1) {
                                TextButton(
                                    onClick = {
                                        showSwitcher = false
                                        deleteTarget = profile
                                    },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .semantics {
                                            contentDescription =
                                                "Delete ${profile.name}"
                                        }
                                ) {
                                    Text(
                                        "Delete",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                    if (profiles.size < ProfilesRepository.MAX_PROFILES) {
                        TextButton(
                            onClick = {
                                showSwitcher = false
                                showCreate = true
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Add profile", color = WatchlyGold)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { showSwitcher = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Close", color = WatchlyGold)
                }
            }
        )
    }

    if (showCreate) {
        ProfileNameDialog(
            heading = "New profile",
            confirmLabel = "Add",
            showColors = true,
            onConfirm = { name, colorIndex ->
                onCreateProfile(name, colorIndex)
                showCreate = false
            },
            onDismiss = { showCreate = false }
        )
    }
    renameTarget?.let { profile ->
        ProfileNameDialog(
            heading = "Rename profile",
            confirmLabel = "Rename",
            initialName = profile.name,
            showColors = false,
            onConfirm = { name, _ ->
                onRenameProfile(profile.id, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }
    deleteTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = {
                Text(
                    text = "Delete “${profile.name}”?",
                    style = MaterialTheme.typography.titleLarge,
                    color = WatchlyGold
                )
            },
            text = {
                Text(
                    text = "Their watchlist, lists, progress and " +
                        "ratings on this phone go away. Downloads " +
                        "and API keys stay — they're shared.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteProfile(profile.id)
                        deleteTarget = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteTarget = null },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel", color = WatchlyGold)
                }
            }
        )
    }
}

/**
 * Name (+ avatar colour, for a new profile) dialog. The field's
 * label is "Profile name" — deliberately NOT the lists' "List
 * name", which the blocking Maestro journeys tap by.
 */
@Composable
internal fun ProfileNameDialog(
    heading: String,
    confirmLabel: String,
    initialName: String = "",
    showColors: Boolean,
    onConfirm: (String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var colorIndex by remember { mutableStateOf(0) }
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
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (showColors) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Avatar colour",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    // 48dp touch target around the 40dp colour
                    // circle (cycle 13): the chips were a raw
                    // clickable Box at 40x40 — the one sub-48dp
                    // target with no Material minimum-size behind
                    // it in the Profiles UI (measured in
                    // ProfilesAccessibilityTest). Two rows of
                    // three so the wider targets fit any dialog.
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ProfilesRepository.AVATAR_COLOR_HEX.indices
                            .chunked(3)
                            .forEach { rowIndices ->
                                Row(
                                    horizontalArrangement =
                                        Arrangement.spacedBy(8.dp)
                                ) {
                                    rowIndices.forEach { index ->
                                        val selected = index == colorIndex
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clickable {
                                                    colorIndex = index
                                                }
                                                .semantics {
                                                    contentDescription =
                                                        "Avatar colour " +
                                                        ProfilesRepository
                                                            .AVATAR_COLOR_NAMES[index]
                                                    this.selected = selected
                                                },
                                            contentAlignment =
                                                Alignment.Center
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        profileColor(
                                                            ProfilesRepository
                                                                .AVATAR_COLOR_HEX[index]
                                                        )
                                                    )
                                                    .then(
                                                        if (selected) {
                                                            Modifier.border(
                                                                2.dp,
                                                                Color.White,
                                                                CircleShape
                                                            )
                                                        } else {
                                                            Modifier
                                                        }
                                                    ),
                                                contentAlignment =
                                                    Alignment.Center
                                            ) {
                                                if (selected) {
                                                    Icon(
                                                        imageVector =
                                                            Icons.Filled.Check,
                                                        contentDescription =
                                                            null,
                                                        tint =
                                                            Color(0xFF1A1405),
                                                        modifier = Modifier
                                                            .size(20.dp)
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
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), colorIndex) },
                enabled = name.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(confirmLabel, color = WatchlyGold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Cancel", color = WatchlyGold)
            }
        }
    )
}

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
            text = if (hasKey) "TMDB Catalog — Connected ✓" else "TMDB Catalog",
            style = MaterialTheme.typography.titleLarge,
            color = WatchlyGold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (hasKey) {
                "Bollywood, Tollywood, Hollywood, Korean and regional industries active."
            } else {
                "Configure your TMDB API key to customize catalog feeds."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = WatchlyGold,
                contentColor = Color(0xFF1A1405)
            )
        ) {
            Text(if (hasKey) "Configure TMDB Key" else "Add TMDB Key")
        }
    }
}

/**
 * The one-go entry to the API Keys hub: configure OMDb, Trakt, OpenSubtitles.
 */
@Composable
private fun ApiKeysCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Text(
            text = "API Integrations",
            style = MaterialTheme.typography.titleLarge,
            color = WatchlyGold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "OMDb (Ratings) • Trakt (Trending) • OpenSubtitles (Subtitles)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = WatchlyGold,
                contentColor = Color(0xFF1A1405)
            )
        ) {
            Text("Manage API Keys")
        }
    }
}

/**
 * Playback settings (cycle 5): the autoplay-next toggle. When an
 * episode or list item finishes, Watchly offers the next playable
 * one with a short countdown — this switch turns that off.
 */
@Composable
private fun PlaybackSettingsCard(
    autoplayNext: Boolean,
    onAutoplayChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Text(
            text = "Playback",
            style = MaterialTheme.typography.titleLarge,
            color = WatchlyGold
        )
        Spacer(Modifier.height(6.dp))
        SettingSwitchRow(
            title = "Autoplay next episode",
            description = "When an episode or a list item finishes, " +
                "play the next playable one after a short countdown.",
            checked = autoplayNext,
            onCheckedChange = onAutoplayChange,
            switchDescription = "Autoplay next episode"
        )
    }
}

/**
 * Data Saver (cycle 10, spec §14.1): one switch, two real effects —
 * posters and artwork load in a smaller size, and downloads pick
 * the smaller file on their own instead of asking. The copy names
 * both, so the switch is never a label without behaviour.
 */
@Composable
private fun DataSaverCard(
    dataSaver: Boolean,
    onDataSaverChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Text(
            text = "Data Saver",
            style = MaterialTheme.typography.titleLarge,
            color = WatchlyGold
        )
        Spacer(Modifier.height(6.dp))
        SettingSwitchRow(
            title = "Save mobile data",
            description = "Posters and artwork load in a smaller " +
                "size, and downloads pick the smaller file " +
                "automatically instead of asking.",
            checked = dataSaver,
            onCheckedChange = onDataSaverChange,
            switchDescription = "Data Saver"
        )
    }
}

/**
 * Your data (cycle 10, spec §15.2): the backup card. Export writes
 * one file with his lists, progress, ratings and settings; import
 * restores it on any install. The copy is explicit that API keys
 * are never in the file — after a fresh install they're re-entered
 * once, like any password.
 */
@Composable
private fun YourDataCard(
    backupMessage: String?,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Text(
            text = "Your data",
            style = MaterialTheme.typography.titleLarge,
            color = WatchlyGold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Backup or restore your watchlist, watch history, and app preferences.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onExport,
                colors = ButtonDefaults.buttonColors(
                    containerColor = WatchlyGold,
                    contentColor = Color(0xFF1A1405)
                )
            ) {
                Text("Export backup")
            }
            OutlinedButton(onClick = onImport) {
                Text("Import backup", color = WatchlyGold)
            }
        }
        backupMessage?.let { message ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = WatchlyGoldLight
            )
        }
    }
}

/** One labelled settings switch row: title + plain-language effect. */
@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchDescription: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics {
                contentDescription = switchDescription
            }
        )
    }
}

/** Storage-cap choices offered in the Downloads card (GB = 10^9 bytes,
 * matching DownloadSettings' 10 GB default). */
private val STORAGE_CAP_OPTIONS_GB = listOf(2L, 5L, 10L, 20L)
private const val BYTES_PER_GB = 1_000_000_000L

/**
 * Download settings (spec §7.3; UI added cycle 8): the rules the
 * engine has enforced since cycle 2 — Wi-Fi only, low-battery pause,
 * storage cap — finally with a screen to change them. Changes persist
 * at once and apply to the next download start.
 */
@Composable
private fun DownloadSettingsCard(
    settings: com.watchly.app.data.downloads.DownloadSettings,
    onWifiOnlyChange: (Boolean) -> Unit,
    onPauseOnLowBatteryChange: (Boolean) -> Unit,
    onStorageCapChange: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Text(
            text = "Downloads",
            style = MaterialTheme.typography.titleLarge,
            color = WatchlyGold
        )
        Spacer(Modifier.height(6.dp))
        SettingSwitchRow(
            title = "Wi-Fi only",
            description = "Downloads wait for Wi-Fi instead of " +
                "starting on mobile data.",
            checked = settings.wifiOnly,
            onCheckedChange = onWifiOnlyChange,
            switchDescription = "Wi-Fi only"
        )
        Spacer(Modifier.height(12.dp))
        SettingSwitchRow(
            title = "Pause on low battery",
            description = "A download won't start while the battery " +
                "is at 15% or lower and not charging.",
            checked = settings.pauseOnLowBattery,
            onCheckedChange = onPauseOnLowBatteryChange,
            switchDescription = "Pause on low battery"
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Storage cap",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Watchly's downloads never grow past this much " +
                "space on the phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            STORAGE_CAP_OPTIONS_GB.forEach { gb ->
                FilterChip(
                    selected = settings.storageCapBytes == gb * BYTES_PER_GB,
                    onClick = { onStorageCapChange(gb * BYTES_PER_GB) },
                    label = { Text("$gb GB") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = WatchlyGold,
                        selectedLabelColor = Color(0xFF1A1405),
                        containerColor = MaterialTheme.colorScheme.surface,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    // 48dp minimum touch target, same as the
                    // Browse/Playability chips (cycle-4 pass).
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
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
                        // White at 30%: the old track (surfaceVariant
                        // #1E1E1E on the black background, ≈1.35:1)
                        // was nearly invisible (Test Lab low
                        // contrast, cycle 6).
                        trackColor = Color.White.copy(alpha = 0.30f),
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
private fun ListsSection(
    lists: List<UserList>,
    onListClick: (UserList) -> Unit,
    onSeeAllClick: () -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeading("My Lists")
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onSeeAllClick,
                // 48dp minimum touch target (cycle-4 accessibility pass).
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("See all", color = WatchlyGold)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (lists.isEmpty()) {
            Text(
                text = "No lists yet — make one in Lists, or use “Add to " +
                    "List…” on any title's page.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(lists, key = { it.id }) { list ->
                    ListCard(
                        list = list,
                        onClick = { onListClick(list) },
                        modifier = Modifier.width(250.dp)
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
    speeds: Map<String, Long> = emptyMap(),
    onPlay: (DownloadRecord) -> Unit,
    onDelete: (DownloadRecord) -> Unit,
    onPause: (DownloadRecord) -> Unit = {},
    onResume: (DownloadRecord) -> Unit = {}
) {
    Column {
        SectionHeading("Downloads")
        Spacer(Modifier.height(12.dp))
        if (downloads.isEmpty()) {
            Text(
                text = "No downloads yet. Downloaded videos will appear here for offline playback.",
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
                        speedBytesPerSec = speeds[record.id],
                        onPlay = { onPlay(record) },
                        onDelete = { onDelete(record) },
                        onPause = { onPause(record) },
                        onResume = { onResume(record) }
                    )
                }
            }
        }
    }
}

/**
 * "On this device" (cycle 5): videos the user granted Watchly
 * access to via SAF — individual picks or a whole folder. They
 * play in the Watchly player like any other playable title.
 * Removing an entry only drops Watchly's record; the file stays
 * on the phone untouched.
 */
@Composable
private fun LocalFilesSection(
    localTitles: List<Title>,
    onPlay: (Title) -> Unit,
    onRemove: (Title) -> Unit,
    onAddFiles: () -> Unit,
    onAddFolder: () -> Unit
) {
    Column {
        SectionHeading("On this device")
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (localTitles.isEmpty()) {
                "Play local videos or folders from your device in Watchly."
            } else {
                "${localTitles.size} local video${if (localTitles.size == 1) "" else "s"} ready to play."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            OutlinedButton(onClick = onAddFiles) {
                Text("Add videos", color = WatchlyGold)
            }
            OutlinedButton(onClick = onAddFolder) {
                Text("Add a folder", color = WatchlyGold)
            }
        }
        if (localTitles.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                localTitles.forEach { title ->
                    LocalFileRow(
                        title = title,
                        onPlay = { onPlay(title) },
                        onRemove = { onRemove(title) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalFileRow(
    title: Title,
    onPlay: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPlay) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play ${title.name}",
                tint = WatchlyGold
            )
        }
        Text(
            text = title.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Remove ${title.name} from Watchly",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DownloadRow(
    record: DownloadRecord,
    speedBytesPerSec: Long? = null,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {}
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
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            // Poster with an on-brand letter fallback when the image
            // is missing or fails to load (Phase D) — never a black
            // empty box.
            var posterFailed by remember(record.posterUrl) {
                mutableStateOf(false)
            }
            if (record.posterUrl != null && !posterFailed) {
                AsyncImage(
                    model = record.posterUrl,
                    // Decorative: the title name is rendered as text
                    // right beside the poster — a matching image
                    // description would be spoken twice (Test Lab
                    // content labeling, cycle 6).
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { posterFailed = true },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = record.titleName.trim().take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = WatchlyGold
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
                text = downloadStatusText(record, speedBytesPerSec),
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
                // Track: white at 30% — the same treatment as the
                // player's seek bar. The old track (surface #141414
                // on this row's surfaceVariant #1E1E1E, ≈1.1:1) was
                // effectively invisible (Test Lab low contrast,
                // cycle 6).
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        color = WatchlyGold,
                        trackColor = Color.White.copy(alpha = 0.30f),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(
                        color = WatchlyGold,
                        trackColor = Color.White.copy(alpha = 0.30f),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        // Every row action names its title (cycle 10 a11y): bare
        // "Pause download" / "Delete download" repeats identically
        // on every row — the duplicate-label class Test Lab flags.
        if (record.status == DownloadStatus.COMPLETED) {
            IconButton(onClick = onPlay) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Play ${record.titleName} offline",
                    tint = WatchlyGold
                )
            }
        }
        // Pause / Resume (Phase D). A system pause (waiting for
        // network) shows neither — it resumes on its own.
        if ((record.status == DownloadStatus.RUNNING ||
                record.status == DownloadStatus.QUEUED) && !record.userPaused
        ) {
            IconButton(onClick = onPause) {
                Icon(
                    imageVector = Icons.Filled.Pause,
                    contentDescription = "Pause download of ${record.titleName}",
                    tint = WatchlyGold
                )
            }
        }
        if (record.userPaused) {
            IconButton(onClick = onResume) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Resume download of ${record.titleName}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Delete download of ${record.titleName}",
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
private fun downloadStatusText(
    record: DownloadRecord,
    speedBytesPerSec: Long? = null
): String = when (record.status) {
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
        // Live speed (Phase D), when a rate is known.
        val speed = DownloadRules
            .formatSpeed(speedBytesPerSec)
            .takeIf { it.isNotEmpty() }
            ?.let { " • $it" } ?: ""
        "Downloading$pct$sizes$speed"
    }
    // Honest wording (Phase D): a USER pause never resumes on its
    // own; a system pause (network/Wi-Fi) does.
    DownloadStatus.PAUSED ->
        if (record.userPaused) "Paused" else "Paused — will resume automatically"
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
