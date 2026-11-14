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
import com.watchly.app.data.downloads.DownloadRules
import com.watchly.app.data.downloads.DownloadStatus
import com.watchly.app.data.downloads.DownloadsRepository
import com.watchly.app.data.library.ListsRepository
import com.watchly.app.data.library.UserList
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
    /** User lists (spec §9.2), creation order, for the My Lists section. */
    val lists: List<UserList> = emptyList(),
    val downloads: List<DownloadRecord> = emptyList(),
    val downloadsTotalBytes: Long = 0,
    /** Live transfer speed per download record id (Phase D). */
    val downloadSpeeds: Map<String, Long> = emptyMap(),
    /** Autoplay-next toggle (cycle 5); defaults ON. */
    val autoplayNext: Boolean = true,
    /** Data Saver toggle (cycle 10, §14.1); defaults OFF. */
    val dataSaver: Boolean = false,
    /**
     * Plain-language result of the last backup export/import
     * (cycle 10, §15.2), shown inside the Your Data card. Null
     * until the user tries one.
     */
    val backupMessage: String? = null,
    /** Download rules (spec §7.3), editable in the Downloads card (cycle 8). */
    val downloadSettings: com.watchly.app.data.downloads.DownloadSettings =
        com.watchly.app.data.downloads.DownloadSettings(),
    /** On-device video files the user granted via SAF (cycle 5). */
    val localTitles: List<Title> = emptyList(),
    /** Local profiles (spec §8, cycle 12), creation order. */
    val profiles: List<com.watchly.app.data.library.WatchlyProfile> = emptyList(),
    /** The profile whose data every section above shows. */
    val activeProfile: com.watchly.app.data.library.WatchlyProfile? = null,
    /**
     * Plain-language result of the last profile action (cycle 12),
     * shown inside the Profiles card. Null until one happens.
     */
    val profileMessage: String? = null,
    /**
     * Set when a backup restore changed which profile is active
     * (cycle 12): this ViewModel's stores belong to the old
     * profile, so the screen must reset navigation exactly like
     * a profile switch. Consumed by the reset itself (this
     * ViewModel is destroyed by it).
     */
    val activeProfileChangedByRestore: Boolean = false
) {
    /**
     * True while any download can still make progress on its own.
     * A user-paused download does NOT count (Phase D): it must not
     * keep the poll loop alive — nothing changes until Resume.
     */
    val hasActiveDownloads: Boolean
        get() = downloads.any { it.isActive && !it.userPaused }
}

class LibraryViewModel(
    private val watchlist: WatchlistRepository,
    private val playback: PlaybackRepository,
    private val downloads: DownloadsRepository,
    private val tmdbKeys: TmdbKeyRepository,
    private val userLists: ListsRepository,
    /** Cycle 5: UI preferences (the autoplay-next toggle). */
    private val uiPrefs: com.watchly.app.data.settings.UiPreferencesRepository? = null,
    /** Cycle 5: on-device local files (SAF), null in tests/previews. */
    private val localFiles: com.watchly.app.data.local.LocalFilesRepository? = null,
    /** Cycle 8: §7.3 download settings store, null in tests/previews. */
    private val downloadSettingsRepo:
        com.watchly.app.data.downloads.DownloadSettingsRepository? = null,
    /** Cycle 10: backup & export store (§15.2), null in tests/previews. */
    private val backupRepo:
        com.watchly.app.data.backup.BackupRepository? = null,
    /** Cycle 12: local profiles store (§8), null in tests/previews. */
    private val profilesRepo:
        com.watchly.app.data.library.ProfilesRepository? = null,
    /** Cycle 5: app context for SAF grants/scans, null in tests. */
    private val appContext: android.content.Context? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    /** Last progress sample per record id, for speed maths. */
    private val lastSamples = HashMap<String, Pair<Long, Long>>()

    /** Smoothed speeds per record id, carried between polls. */
    private val smoothedSpeeds = HashMap<String, Long>()

    init {
        refresh()
    }

    /** Reloads every section from the local repositories. */
    fun refresh() {
        val downloadList = downloads.getDownloads()
        updateSpeeds(downloadList)
        _uiState.update {
            it.copy(
                isLoading = false,
                hasTmdbKey = tmdbKeys.hasKey(),
                continueWatching = playback.getRecentProgress(),
                watchlist = watchlist.getWatchlist(),
                lists = userLists.getLists(),
                downloads = downloadList,
                downloadsTotalBytes = downloads.totalDownloadedBytes(),
                downloadSpeeds = HashMap(smoothedSpeeds),
                autoplayNext = uiPrefs?.autoplayNext() ?: true,
                dataSaver = uiPrefs?.dataSaverEnabled() ?: false,
                downloadSettings = downloadSettingsRepo?.settings()
                    ?: com.watchly.app.data.downloads.DownloadSettings(),
                localTitles = localFiles?.titles() ?: emptyList(),
                profiles = profilesRepo?.profiles() ?: emptyList(),
                activeProfile = profilesRepo?.activeProfile()
            )
        }
    }

    // ---------------------------------------------------------------
    // Profiles (spec §8, cycle 12). The repositories this
    // ViewModel holds were built for the profile that was active
    // when the screen opened, so a switch (or deleting the active
    // profile) is reported back to the screen, which resets the
    // navigation stack — every screen then rebuilds against the
    // new profile's own stores. Downloads and API keys are NOT
    // profile data: they stay shared, and the card copy says so.
    // ---------------------------------------------------------------

    /**
     * Switches the active profile. True when the switch happened
     * and the caller must reset navigation; switching to the
     * already-active profile is a no-op (false).
     */
    fun switchProfile(profileId: String): Boolean {
        val repo = profilesRepo ?: return false
        if (repo.activeProfileId() == profileId) return false
        return repo.setActiveProfile(profileId)
    }

    /** Adds a profile (does not switch to it). */
    fun createProfile(name: String, colorIndex: Int) {
        val repo = profilesRepo ?: return
        val created = repo.createProfile(name, colorIndex)
        if (created == null) {
            _uiState.update {
                it.copy(
                    profileMessage = "Watchly holds up to " +
                        "${com.watchly.app.data.library.ProfilesRepository.MAX_PROFILES} " +
                        "profiles — delete one before adding another."
                )
            }
        }
        refresh()
    }

    /** Renames a profile. */
    fun renameProfile(profileId: String, newName: String) {
        profilesRepo?.renameProfile(profileId, newName)
        refresh()
    }

    /**
     * Deletes a profile and its data. True when the deleted
     * profile was the active one — the caller must reset
     * navigation, exactly like a switch.
     */
    fun deleteProfile(profileId: String): Boolean {
        val repo = profilesRepo ?: return false
        val wasActive = repo.activeProfileId() == profileId
        val deleted = repo.deleteProfile(profileId)
        if (deleted && !wasActive) refresh()
        return deleted && wasActive
    }

    // ---------------------------------------------------------------
    // On-device local files (cycle 5, SAF — no storage permission)
    // ---------------------------------------------------------------

    /**
     * The user picked individual video files. Each grant is made
     * persistable (so it survives reboot), named via the provider,
     * and stored; the section refreshes from the repository.
     */
    fun onLocalFilesPicked(uris: List<android.net.Uri>) {
        val context = appContext ?: return
        val repo = localFiles ?: return
        val files = uris.map { uri ->
            takeReadGrant(context, uri)
            uri.toString() to
                com.watchly.app.data.local.LocalFilesScanner
                    .displayName(context, uri)
        }
        repo.addFiles(files, System.currentTimeMillis())
        refresh()
    }

    /**
     * The user granted a whole folder. The grant is made
     * persistable, the folder is scanned for videos off the main
     * thread, and the finds are stored under the folder's URI.
     */
    fun onLocalFolderPicked(treeUri: android.net.Uri) {
        val context = appContext ?: return
        val repo = localFiles ?: return
        takeReadGrant(context, treeUri)
        viewModelScope.launch {
            val files = kotlinx.coroutines.withContext(
                kotlinx.coroutines.Dispatchers.IO
            ) {
                com.watchly.app.data.local.LocalFilesScanner
                    .scanTree(context, treeUri)
            }
            repo.addFolderFiles(
                treeUri.toString(), files, System.currentTimeMillis()
            )
            refresh()
        }
    }

    /**
     * Removes one local entry. Only Watchly's record is dropped —
     * the user's file on their phone is never deleted.
     */
    fun removeLocalTitle(title: Title) {
        val repo = localFiles ?: return
        val uri = title.localUri ?: return
        repo.remove(uri)
        refresh()
    }

    /** Best-effort persistable read grant; failure just means the
     * entry may stop resolving after reboot, never a crash. */
    private fun takeReadGrant(context: android.content.Context, uri: android.net.Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    /** Flips the autoplay-next setting (persisted at once). */
    fun setAutoplayNext(enabled: Boolean) {
        uiPrefs?.setAutoplayNext(enabled)
        _uiState.update { it.copy(autoplayNext = enabled) }
    }

    /** Flips Data Saver (§14.1, persisted at once). Image loads
     * read the same preference live, so posters shrink from the
     * next load — no restart. */
    fun setDataSaver(enabled: Boolean) {
        uiPrefs?.setDataSaverEnabled(enabled)
        _uiState.update { it.copy(dataSaver = enabled) }
    }

    // ---------------------------------------------------------------
    // Backup & export (cycle 10, spec §15.2). The screen owns the
    // SAF pickers; the URIs land here and the streams run off the
    // main thread. API keys are never in a backup (see
    // BackupRepository) — the card's copy says so.
    // ---------------------------------------------------------------

    /** Writes the backup to the user-picked [uri]. */
    fun exportBackup(uri: android.net.Uri) {
        val context = appContext ?: return
        val repo = backupRepo ?: return
        viewModelScope.launch {
            val ok = kotlinx.coroutines.withContext(
                kotlinx.coroutines.Dispatchers.IO
            ) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        repo.writeBackupTo(out, System.currentTimeMillis())
                    } ?: error("no output stream")
                }.isSuccess
            }
            _uiState.update {
                it.copy(
                    backupMessage = if (ok) {
                        "Backup saved. Keep that file somewhere safe — " +
                            "it's your lists, progress and settings in one file."
                    } else {
                        "Couldn't save the backup file. Try again."
                    }
                )
            }
        }
    }

    /** Restores the backup from the user-picked [uri], then
     * reloads every section so the restored data shows at once. */
    fun importBackup(uri: android.net.Uri) {
        val context = appContext ?: return
        val repo = backupRepo ?: return
        // Cycle 12: a backup carries its own active profile; if
        // the restore changes it, this ViewModel's stores point
        // at the wrong profile's files and the screen must reset
        // (flagged on success below).
        val activeBefore = profilesRepo?.activeProfileId()
        viewModelScope.launch {
            val outcome = kotlinx.coroutines.withContext(
                kotlinx.coroutines.Dispatchers.IO
            ) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        repo.readBackupFrom(input)
                    } ?: error("no input stream")
                }
            }
            outcome.fold(
                onSuccess = {
                    refresh()
                    val activeChanged = profilesRepo != null &&
                        activeBefore != null &&
                        profilesRepo.activeProfileId() != activeBefore
                    _uiState.update {
                        it.copy(
                            backupMessage = "Backup restored — your lists, " +
                                "progress and settings are back.",
                            activeProfileChangedByRestore = activeChanged
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            backupMessage =
                                (error as? com.watchly.app.data.backup
                                    .BackupFormatException)?.message
                                    ?: "That file couldn't be read as a Watchly backup."
                        )
                    }
                }
            )
        }
    }

    // ---------------------------------------------------------------
    // Download settings (spec §7.3; UI added cycle 8 — the values
    // persisted and were engine-enforced since cycle 2 but had no
    // screen to change them). Each setter persists at once, exactly
    // like the autoplay toggle; the scheduler reads the store fresh
    // for every download, so a change applies to the next start.
    // ---------------------------------------------------------------

    fun setDownloadWifiOnly(value: Boolean) {
        downloadSettingsRepo?.setWifiOnly(value)
        _uiState.update {
            it.copy(downloadSettings = it.downloadSettings.copy(wifiOnly = value))
        }
    }

    fun setDownloadPauseOnLowBattery(value: Boolean) {
        downloadSettingsRepo?.setPauseOnLowBattery(value)
        _uiState.update {
            it.copy(
                downloadSettings =
                    it.downloadSettings.copy(pauseOnLowBattery = value)
            )
        }
    }

    fun setDownloadStorageCap(capBytes: Long) {
        downloadSettingsRepo?.setStorageCapBytes(capBytes)
        _uiState.update {
            it.copy(
                downloadSettings =
                    it.downloadSettings.copy(storageCapBytes = capBytes)
            )
        }
    }

    /**
     * Derives per-download speeds from consecutive progress samples
     * (Phase D): raw rate from DownloadRules, lightly smoothed
     * (⅔ old + ⅓ new) so the row doesn't flicker. Non-running
     * records drop out — a paused/finished row shows no speed.
     */
    private fun updateSpeeds(records: List<DownloadRecord>) {
        val now = System.currentTimeMillis()
        val liveIds = HashSet<String>()
        for (record in records) {
            if (record.status != DownloadStatus.RUNNING) continue
            liveIds.add(record.id)
            val prev = lastSamples[record.id]
            lastSamples[record.id] = record.bytesDownloaded to now
            if (prev == null) continue
            val raw = DownloadRules.speedBytesPerSec(
                prev.first, prev.second, record.bytesDownloaded, now
            ) ?: continue
            val old = smoothedSpeeds[record.id]
            smoothedSpeeds[record.id] =
                if (old == null) raw else (old * 2 + raw) / 3
        }
        lastSamples.keys.retainAll(liveIds)
        smoothedSpeeds.keys.retainAll(liveIds)
    }

    /** Deletes one download (file + record), then refreshes. */
    fun deleteDownload(record: DownloadRecord) {
        downloads.delete(record)
        refresh()
    }

    /** Pauses one download (row action, Phase D), then refreshes. */
    fun pauseDownload(record: DownloadRecord) {
        downloads.pauseDownload(record)
        refresh()
    }

    /** Resumes one user-paused download (row action), then refreshes. */
    fun resumeDownload(record: DownloadRecord) {
        downloads.resumeDownload(record)
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
                tmdbKeys = TmdbKeyRepository(app),
                userLists = ListsRepository(app),
                uiPrefs = com.watchly.app.data.settings.UiPreferencesRepository(app),
                localFiles = com.watchly.app.data.local.LocalFilesRepository(app),
                downloadSettingsRepo =
                    com.watchly.app.data.downloads.DownloadSettingsRepository(app),
                backupRepo = com.watchly.app.data.backup.BackupRepository(app),
                profilesRepo =
                    com.watchly.app.data.library.ProfilesRepository(app),
                appContext = app
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
        "youtube" -> base.copy(youtubeId = parts[1])
        "imdb" -> base.copy(imdbId = parts[1])
        else -> base
    }
}
