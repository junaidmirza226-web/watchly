/*
 * Watchly — Title Details page (spec §4.1).
 *
 * One rich page per title: backdrop/poster, title, year, runtime /
 * seasons, genres, overview, per-provider ratings badges (labelled by
 * source — never a blended fake rating), Play / + Watchlist / Watched /
 * Share / Download buttons, cast row, season selector + episode list
 * for shows, and the source list with a health badge. When no playable
 * source exists the page still works fully as a catalogue page and says
 * so plainly: "Not in your library yet."
 */
package com.watchly.app.ui.details

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.watchly.app.data.downloads.DownloadPolicy
import com.watchly.app.data.model.CastMember
import com.watchly.app.data.model.Episode
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Playability
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.Title
import com.watchly.app.data.model.WatchProvider
import com.watchly.app.ui.theme.WatchlyGold
import com.watchly.app.ui.theme.watchlyGoldButtonColors
import com.watchly.app.ui.theme.WatchlyGoldLight
import java.util.Locale

@Composable
fun TitleDetailsScreen(
    title: Title,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    /**
     * Scroll state of the details list. Hoisted (with the usual
     * default) so tests can jump to a scrolled position
     * deterministically — the fixed-back-button screenshot golden
     * depends on it. Production callers use the default.
     */
    listState: LazyListState = rememberLazyListState(),
    /**
     * Play a title's files in the Media3 player. Called only when the
     * title has a playable source; otherwise the ViewModel explains
     * plainly that there is nothing to play.
     */
    onPlay: (
        Title,
        List<PlayableFile>,
        String?,
        List<com.watchly.app.data.playback.UpNextItem>
    ) -> Unit = { _, _, _, _ -> },
    onPlayWithServer: ((
        Title,
        List<PlayableFile>,
        String?,
        List<com.watchly.app.data.playback.UpNextItem>,
        String?
    ) -> Unit)? = null,
    /**
     * Opens a where-to-watch page IN-APP (Phase D). The nav layer
     * routes this to the in-app browser; the default does nothing
     * (screenshot tests render rows without a nav host).
     */
    onOpenWatchPage: (String) -> Unit = {},
    /**
     * Cycle 10: a "More like this" card was tapped — the nav layer
     * opens that title's details (replacing this page). Default
     * does nothing (screenshot tests).
     */
    onTitleClick: (Title) -> Unit = {},
    /**
     * Cycle 11 (§3.3): a cast member WITH a TMDB person id was
     * tapped — the nav layer opens that person's page. Only ever
     * fires for TMDB cast; other sources' members carry no TMDB
     * id, their cards aren't clickable at all (see CastCard).
     */
    onPersonClick: (CastMember) -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: TitleDetailsViewModel = viewModel(
        factory = TitleDetailsViewModelFactory(title, context)
    )
    val state by viewModel.uiState.collectAsState()

    // System back button / gesture leaves details too, through the
    // same onBack path as the on-screen arrow (bug reported
    // 2026-11-06: back from details was unreliable).
    BackHandler { onBack() }

    val triggerPlay: (
        Title,
        List<PlayableFile>,
        String?,
        List<com.watchly.app.data.playback.UpNextItem>,
        String?
    ) -> Unit = { playTitle, files, epLabel, queue, serverUrl ->
        if (onPlayWithServer != null) {
            onPlayWithServer(playTitle, files, epLabel, queue, serverUrl)
        } else {
            onPlay(playTitle, files, epLabel, queue)
        }
    }

    // The LazyColumn scrolls INSIDE this Box; the back button below is
    // a fixed overlay sibling of it, so it can never scroll away.
    Box(modifier = modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            DetailsHeader(
                state = state,
                onPlay = {
                    when {
                        state.canStreamInApp -> triggerPlay(
                            state.title,
                            state.playableFiles,
                            null,
                            state.upNextQueue,
                            state.selectedServerUrl
                        )
                        state.filesCheckFailed -> viewModel.retryFilesCheck()
                        else -> viewModel.onPlayRequested()
                    }
                },
                onSelectServer = viewModel::selectServer,
                onRetryFiles = viewModel::retryFilesCheck,
                onToggleWatchlist = viewModel::toggleWatchlist,
                onToggleWatched = viewModel::toggleWatched,
                onShare = {
                    // Name + year, then a real link to the title
                    // (TMDB / IMDb / TVmaze / AniList / Archive) —
                    // shareTextFor is pure + unit-tested (Bug 3).
                    val shareText = shareTextFor(state.title)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    context.startActivity(
                        Intent.createChooser(intent, "Share ${state.title.name}")
                    )
                },
                onDownload = viewModel::onDownloadRequested,
                onAddToList = viewModel::onAddToListRequested,
                onWatchTrailer = {
                    // The official trailer plays through the SAME
                    // in-app YouTube path as a YouTube title: a copy
                    // of the title carrying the trailer's video id,
                    // no files, no autoplay queue.
                    state.trailerYoutubeId?.let { trailerKey ->
                        onPlay(
                            state.title.copy(youtubeId = trailerKey),
                            emptyList(), null, emptyList()
                        )
                    }
                },
                onRatingPreview = viewModel::onPersonalRatingPreview,
                onRatingCommit = viewModel::onPersonalRatingCommitted
            )
        }

        state.infoMessage?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = WatchlyGoldLight,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
        state.errorMessage?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        if (state.isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = WatchlyGold)
                }
            }
        }

        if (state.cast.isNotEmpty()) {
            item { SectionHeading("Cast") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.cast, key = { it.personName }) { member ->
                        CastCard(
                            member = member,
                            // §3.3: tappable ONLY with a real TMDB
                            // person id behind it — no id, no tap,
                            // no fake page.
                            onClick = if (member.tmdbPersonId != null) {
                                { onPersonClick(member) }
                            } else {
                                null
                            }
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }

        if (state.episodesBySeason.isNotEmpty()) {
            item { SectionHeading("Episodes") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.seasons) { season ->
                        FilterChip(
                            selected = state.selectedSeason == season,
                            onClick = { viewModel.selectSeason(season) },
                            label = {
                                Text(if (season == 0) "Specials" else "Season $season")
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = WatchlyGold,
                                selectedLabelColor = Color(0xFF1A1405)
                            ),
                            // 48dp minimum touch target (cycle-4
                            // accessibility pass).
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
            }
            if (state.isSeasonLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = WatchlyGold)
                    }
                }
            }
            items(
                state.selectedEpisodes,
                key = { "${it.season}-${it.number}" }
            ) { episode ->
                EpisodeRow(
                    episode = episode,
                    watched = state.watchedEpisodes.contains("S${episode.season}E${episode.number}"),
                    onToggleWatched = { viewModel.toggleEpisodeWatched(episode) },
                    onPlay = {
                        val epName = episode.name ?: "Episode ${episode.number}"
                        triggerPlay(
                            state.title,
                            emptyList(),
                            "S${episode.season}E${episode.number} - $epName",
                            viewModel.episodeQueueAfter(episode),
                            null
                        )
                    }
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }

        // Where-to-watch: shown only when TMDB was actually asked
        // (title has a TMDB id and the user saved a key). An empty
        // answer gets an honest line, not a blank space.
        state.watchProviders?.let { watch ->
            item { SectionHeading("Where to watch") }
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    if (watch.providers.isEmpty()) {
                        Text(
                            text = "No official streaming, rental or purchase " +
                                "options are listed for this title in " +
                                "${watch.region} yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        watch.providers.forEach { provider ->
                            WatchProviderRow(
                                provider = provider,
                                watchPageUrl = watch.watchPageUrl,
                                onOpenWatchPage = onOpenWatchPage
                            )
                        }
                        Text(
                            text = "In ${watch.region}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }

        // No-key guidance (cycle 14, first-run audit): with no TMDB
        // key saved, TMDB was never asked, so the section above is
        // absent for EVERY title — and a missing headline feature
        // with no explanation reads as broken. One honest line in
        // its place; with a key saved this never renders (a keyed
        // fetch that finds nothing keeps the section's own honest
        // empty answer, and a keyed title TMDB can't resolve keeps
        // today's silence — only the provable no-key case speaks).
        if (state.watchProviders == null && !state.hasTmdbKey && !state.isLoading) {
            item { SectionHeading("Where to watch") }
            item {
                Text(
                    text = "Where to watch isn't available right now.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }

        // More like this (cycle 10): TMDB's recommendations for
        // this title, as ordinary poster cards — tapping one opens
        // that title's page. Absent entirely when TMDB has none.
        if (state.recommendations.isNotEmpty()) {
            item { SectionHeading("More like this") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.recommendations, key = { it.stableKey }) { rec ->
                        com.watchly.app.ui.components.PosterCard(
                            title = rec,
                            onClick = { onTitleClick(rec) },
                            modifier = Modifier.width(120.dp)
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }

        item { SectionHeading("Sources") }
        item {
            when {
                state.playableFiles.isNotEmpty() -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        state.playableFiles.forEach { file ->
                            SourceRow(file = file)
                        }
                        Text(
                            text = "Internet Archive",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                state.cloudServers.isNotEmpty() -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        state.cloudServers.forEachIndexed { idx, server ->
                            val label = server.qualityLabel ?: "Server ${idx + 1}"
                            StreamServerRow(
                                serverName = "${idx + 1}. $label",
                                badge = if (server.url == state.selectedServerUrl) "Selected • Auto HD" else "Backup HD",
                                onClick = {
                                    if (state.title.isReleased(java.time.LocalDate.now().toString())) {
                                        viewModel.selectServer(server.url)
                                        triggerPlay(
                                            state.title,
                                            emptyList(),
                                            null,
                                            state.upNextQueue,
                                            server.url
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
                state.title.youtubeId != null -> {
                    Text(
                        text = "YouTube",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                state.filesCheckFailed -> {
                    Text(
                        text = "Couldn't reach the servers. Tap Retry.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                else -> {
                    Text(
                        text = "Catalogue entry — no stream attached yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }

        // FIXED back button: overlay above the scrolling content, so
        // it stays put at every scroll position (it used to live
        // inside the scrolling header and scrolled off the top).
        // Semi-opaque dark circle keeps the gold arrow readable over
        // any backdrop; the IconButton itself is a 48dp tap target.
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = WatchlyGold
            )
        }
    }

    // Download quality picker (spec §7.1, screens §14): open while
    // the ViewModel holds choices — the title offered more than one
    // file and the user tapped Download. Tapping a row starts that
    // exact quality through the §7.3-checked path.
    state.qualityChoices?.let { choices ->
        AlertDialog(
            onDismissRequest = viewModel::onQualityPickerDismissed,
            title = {
                Text(
                    text = "Choose quality",
                    style = MaterialTheme.typography.titleLarge,
                    color = WatchlyGold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { viewModel.onQualityChosen(file) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = DownloadPolicy.qualityLabel(file),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                file.sizeLabel?.let { size ->
                                    Text(
                                        text = size,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                text = "Download",
                                style = MaterialTheme.typography.labelLarge,
                                color = WatchlyGold
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = viewModel::onQualityPickerDismissed) {
                    Text("Cancel", color = WatchlyGold)
                }
            }
        )
    }

    // "Add to List…" picker (spec §9.2): every list with a check
    // where this title already lives; tapping toggles membership
    // in place, and a new list can be created without leaving.
    state.listChoices?.let { choices ->
        AddToListDialog(
            choices = choices,
            onToggle = viewModel::onListMembershipToggled,
            onCreateAndAdd = viewModel::onCreateListAndAdd,
            onDismiss = viewModel::onAddToListDismissed
        )
    }
}

@Composable
private fun AddToListDialog(
    choices: List<ListMembership>,
    onToggle: (String) -> Unit,
    onCreateAndAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newListName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Add to List",
                style = MaterialTheme.typography.titleLarge,
                color = WatchlyGold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (choices.isEmpty()) {
                    Text(
                        text = "No lists yet — create your first one below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                choices.forEach { choice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onToggle(choice.listId) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = choice.listName,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = when (choice.itemCount) {
                                    0 -> "No titles yet"
                                    1 -> "1 title"
                                    else -> "${choice.itemCount} titles"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (choice.containsTitle) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "In this list",
                                tint = WatchlyGold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = newListName,
                    onValueChange = { newListName = it },
                    label = { Text("New list name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = {
                        onCreateAndAdd(newListName)
                        newListName = ""
                    },
                    enabled = newListName.isNotBlank(),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Create & add", color = WatchlyGold)
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

// ---------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------

/**
 * Cycle-4 ratings strip: OMDb's IMDb / Rotten Tomatoes / Metacritic
 * scores (plus the age rating) and Trakt's community score — one
 * labelled chip per source, rendered only for values that actually
 * came back. Renders nothing at all without the user's keys.
 */
@Composable
private fun ProviderRatingsRow(state: TitleDetailsUiState) {
    val omdb = state.omdbRatings
    val hasOmdb = omdb?.hasAnyRating == true
    if (!hasOmdb && state.traktRating == null) return

    Spacer(Modifier.height(10.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (omdb?.imdbRating != null) {
            item {
                RatingChip(
                    label = "IMDb ★ ${String.format(Locale.US, "%.1f", omdb.imdbRating)}" +
                        (omdb.imdbVotes?.let { " ($it votes)" } ?: "")
                )
            }
        }
        if (omdb?.rottenTomatoesPercent != null) {
            item { RatingChip(label = "Rotten Tomatoes ${omdb.rottenTomatoesPercent}%") }
        }
        if (omdb?.metacriticScore != null) {
            item { RatingChip(label = "Metacritic ${omdb.metacriticScore}") }
        }
        if (state.traktRating != null) {
            item {
                RatingChip(
                    label = "Trakt ★ ${String.format(Locale.US, "%.1f", state.traktRating)}"
                )
            }
        }
        if (omdb?.rated != null) {
            item { RatingChip(label = "Rated ${omdb.rated}") }
        }
    }
}

/** One gold-on-dark ratings chip. */
@Composable
private fun RatingChip(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = WatchlyGoldLight,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun DetailsHeader(
    state: TitleDetailsUiState,
    onPlay: () -> Unit,
    onToggleWatchlist: () -> Unit,
    onToggleWatched: () -> Unit,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    onAddToList: () -> Unit,
    onSelectServer: (String) -> Unit = {},
    onRetryFiles: () -> Unit = {},
    onWatchTrailer: () -> Unit = {},
    onRatingPreview: (Int) -> Unit = {},
    onRatingCommit: () -> Unit = {}
) {
    val title = state.title
    val today = remember { java.time.LocalDate.now().toString() }
    // Not-out-yet titles (future release/first-air date) must never show a
    // live Play button — there is nothing to play.
    val released = title.isReleased(today)
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            var failedHeroUrls by remember {
                mutableStateOf<Set<String>>(emptySet())
            }
            val heroCandidates = listOfNotNull(
                title.backdropUrl,
                title.posterUrl,
                state.fanartBackdropUrl
            )
            val shownHeroUrl =
                heroCandidates.firstOrNull { it !in failedHeroUrls }
            if (shownHeroUrl != null) {
                AsyncImage(
                    model = shownHeroUrl,
                    // Decorative backdrop: the title's name is the
                    // header text right below it — a matching image
                    // description would be spoken twice (Test Lab
                    // run 4: content labeling tips).
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { failedHeroUrls = failedHeroUrls + shownHeroUrl },
                    modifier = Modifier.fillMaxSize()
                )
            } else if (heroCandidates.isNotEmpty()) {
                // Every candidate image failed: monogram hero.
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title.name.take(1).uppercase(),
                        style = MaterialTheme.typography.displayLarge,
                        color = WatchlyGold
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
            )
            // Fanart HD clear logo (cycle 4): the film's own
            // transparent wordmark over the hero's lower-left.
            state.fanartLogoUrl?.let { logoUrl ->
                AsyncImage(
                    model = logoUrl,
                    // Decorative wordmark of the same title name the
                    // header already speaks — no second description.
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 12.dp)
                        .height(44.dp)
                        .widthIn(max = 200.dp)
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = title.name,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                title.year?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                title.runtimeMinutes?.let { runtime ->
                    Text(
                        text = "  •  $runtime min",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.episodesBySeason.isNotEmpty()) {
                    val seasonCount = state.seasons.size
                    val episodeCount = state.episodesBySeason.values.sumOf { it.size }
                    Text(
                        text = "  •  $seasonCount season${if (seasonCount == 1) "" else "s"} • $episodeCount episodes",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                title.status?.let {
                    Text(
                        text = "  •  $it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Phase D: the files check failed — say so, with a
            // working Retry, instead of claiming "no video file".
            if (state.filesCheckFailed) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Couldn't reach the servers.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRetryFiles) {
                        Text("Retry", color = WatchlyGold)
                    }
                }
            }

            // Ratings: one badge per provider that supplied a rating,
            // labelled by source — never a blended fake rating.
            if (title.rating != null && state.ratingSourceLabel != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = WatchlyGold,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${state.ratingSourceLabel} ${String.format(Locale.US, "%.1f", title.rating)} / 10",
                        style = MaterialTheme.typography.titleMedium,
                        color = WatchlyGoldLight
                    )
                }
            }

            // Cycle-4 ratings (OMDb + Trakt), shown only when the
            // user's keys unlocked them and the providers answered.
            ProviderRatingsRow(state = state)

            // Your rating (cycle 10, §4.2): HIS score, 1–10, kept
            // on this phone and in his backup — labelled as his and
            // never mixed into the provider scores above. The
            // slider rests at 0 = not rated; setting it back to 0
            // clears the rating.
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Your rating",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = state.personalRating?.let { "$it / 10" }
                        ?: "Not rated",
                    style = MaterialTheme.typography.titleMedium,
                    color = WatchlyGoldLight
                )
            }
            androidx.compose.material3.Slider(
                value = (state.personalRating ?: 0).toFloat(),
                onValueChange = { value -> onRatingPreview(value.toInt()) },
                onValueChangeFinished = onRatingCommit,
                valueRange = 0f..10f,
                steps = 9,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = WatchlyGold,
                    activeTrackColor = WatchlyGold,
                    // White at 30%: a surfaceVariant track on black
                    // is nearly invisible (Test Lab contrast class).
                    inactiveTrackColor = Color.White.copy(alpha = 0.30f)
                ),
                modifier = Modifier.semantics {
                    contentDescription = "Your rating, 0 to 10"
                }
            )

            if (title.genres.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(title.genres) { genre ->
                        Text(
                            text = genre,
                            style = MaterialTheme.typography.labelLarge,
                            color = WatchlyGold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            title.overview?.let { overview ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Phase D truth rule: the gold Play button is only
                // ACTIVE when something can actually play (files, a
                // YouTube video) AND the title is actually out. A
                // catalogue-only or unreleased title gets the grey,
                // disabled form — never a live-looking button that
                // only prints a note.
                Button(
                    onClick = onPlay,
                    enabled = released,
                    colors = watchlyGoldButtonColors(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (released) "Play" else "Coming soon")
                }
                OutlinedButton(onClick = onToggleWatchlist) {
                    Text(
                        if (state.isInWatchlist) "✓ Watchlist" else "+ Watchlist",
                        color = WatchlyGold
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // Two half-width buttons per row: the old three-in-a-row
            // squeezed the Download button until its label wrapped
            // to two lines at 411dp (cycle-2 golden). Equal weights
            // give every label a full half row — one line, always.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onToggleWatched,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (state.isWatched) "✓ Watched" else "Mark watched",
                        color = WatchlyGold,
                        maxLines = 1
                    )
                }
                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = null,
                        tint = WatchlyGold,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Share", color = WatchlyGold, maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onAddToList,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlaylistAdd,
                        contentDescription = null,
                        tint = WatchlyGold,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Add to List…", color = WatchlyGold, maxLines = 1)
                }
                // Always visible: playable titles start a real
                // download; catalogue-only titles explain why not.
                OutlinedButton(
                    onClick = onDownload,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = null,
                        tint = WatchlyGold,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        state.downloadButtonLabel,
                        color = WatchlyGold,
                        maxLines = 1
                    )
                }
            }
            // Trailer (cycle 10, §4.1): only when TMDB actually
            // lists an official YouTube trailer for this title —
            // the row doesn't exist otherwise. Plays in the same
            // in-app YouTube player as a YouTube-channel title.
            if (state.trailerYoutubeId != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onWatchTrailer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = WatchlyGold,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Watch trailer", color = WatchlyGold, maxLines = 1)
                }
            }

            // Quick Multi-Server Selector strip for cloud-streamable titles
            if (state.cloudServers.size > 1) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Streaming Server (${state.cloudServers.size} HD Servers Ready)",
                    style = MaterialTheme.typography.labelLarge,
                    color = WatchlyGoldLight
                )
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.cloudServers, key = { it.url }) { server ->
                        val isSelected = server.url == state.selectedServerUrl
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectServer(server.url) },
                            label = {
                                Text(server.qualityLabel ?: "Server")
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = WatchlyGold,
                                selectedLabelColor = Color(0xFF1A1405),
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.heightIn(min = 44.dp)
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        color = WatchlyGold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun CastCard(member: CastMember, onClick: (() -> Unit)? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(80.dp)
            .then(
                // Cycle 11 (§3.3): the card is a tap target only
                // when a person page really exists behind it
                // (TMDB cast). Inert otherwise — no ripple, no
                // semantics click, nothing pretending.
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            // Initial monogram when the photo is missing OR fails
            // to load (Phase D) — never an empty grey circle.
            var castImageFailed by remember(member.imageUrl) {
                mutableStateOf(false)
            }
            if (member.imageUrl != null && !castImageFailed) {
                AsyncImage(
                    model = member.imageUrl,
                    // Decorative: the person's name is the text
                    // directly under the photo.
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { castImageFailed = true },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = member.personName.take(1).uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = WatchlyGold
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = member.personName,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        member.characterName?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    watched: Boolean,
    onToggleWatched: () -> Unit,
    onPlay: () -> Unit
) {
    // Episodes that have not aired yet must not pretend to be playable.
    val aired = episode.isAired(java.time.LocalDate.now().toString())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = aired, onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (episode.imageUrl != null) {
            Box(
                modifier = Modifier
                    .width(116.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = episode.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (aired) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = WatchlyGold,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${episode.number}. ${episode.name ?: "Episode ${episode.number}"}",
                style = MaterialTheme.typography.titleMedium,
                color = if (watched) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Row {
                episode.airDate?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                episode.runtimeMinutes?.let {
                    Text(
                        text = "  •  $it min",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            episode.overview?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onToggleWatched) {
            Icon(
                imageVector = if (watched) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                // Names the episode (cycle 10 a11y): a bare "Mark
                // watched" repeats on every episode row — the
                // duplicate-label class Test Lab flags.
                contentDescription = if (watched) {
                    "Mark S${episode.season}E${episode.number} unwatched"
                } else {
                    "Mark S${episode.season}E${episode.number} watched"
                },
                tint = if (watched) WatchlyGold else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WatchProviderRow(
    provider: WatchProvider,
    watchPageUrl: String?,
    onOpenWatchPage: (String) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (watchPageUrl != null) {
                    Modifier.clickable {
                        // TMDB supplies one watch page per title +
                        // region (JustWatch-powered); it opens IN-APP
                        // (Phase D) via the nav layer's browser.
                        onOpenWatchPage(watchPageUrl)
                    }
                } else {
                    Modifier
                }
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            if (provider.logoUrl != null) {
                AsyncImage(
                    model = provider.logoUrl,
                    // Decorative: the provider's name is rendered as
                    // text right beside the logo — a matching image
                    // description would be spoken twice (Test Lab
                    // content labeling, cycle 6).
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = provider.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = WatchlyGold
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = provider.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = provider.category.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (watchPageUrl != null) {
            Text(
                text = "Open",
                style = MaterialTheme.typography.labelLarge,
                color = WatchlyGold
            )
        }
    }
}

@Composable
internal fun SourceRow(file: PlayableFile) {
    // Cycle 14 (run 13 labeling tip): a title's rows all spoke just
    // "Internet Archive" (14 examples in one crawl) though they
    // differ by format and size. The row now merges into ONE spoken
    // description carrying all three ("Internet Archive, MPEG4,
    // 507 MB"); the visible layout is unchanged.
    val spokenSource = listOfNotNull(
        "Internet Archive",
        file.format,
        file.sizeLabel
    ).joinToString(", ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = spokenSource
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = WatchlyGold
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Internet Archive",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = listOfNotNull(file.format, file.sizeLabel).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "Working",
            style = MaterialTheme.typography.labelLarge,
            color = WatchlyGold
        )
    }
}

@Composable
private fun StreamServerRow(
    serverName: String,
    badge: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = WatchlyGold,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = serverName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Tap to stream in HD",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = badge,
            style = MaterialTheme.typography.labelMedium,
            color = WatchlyGold
        )
    }
}
