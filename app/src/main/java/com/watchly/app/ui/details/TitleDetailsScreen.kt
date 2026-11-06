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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.watchly.app.data.model.CastMember
import com.watchly.app.data.model.Episode
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Playability
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.Title
import com.watchly.app.data.model.WatchProvider
import com.watchly.app.ui.components.PlayabilityBadge
import com.watchly.app.ui.theme.WatchlyGold
import com.watchly.app.ui.theme.WatchlyGoldLight
import java.util.Locale

@Composable
fun TitleDetailsScreen(
    title: Title,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    /**
     * Play a title's files in the Media3 player. Called only when the
     * title has a playable source; otherwise the ViewModel explains
     * plainly that there is nothing to play.
     */
    onPlay: (Title, List<PlayableFile>, String?) -> Unit = { _, _, _ -> }
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

    // The LazyColumn scrolls INSIDE this Box; the back button below is
    // a fixed overlay sibling of it, so it can never scroll away.
    Box(modifier = modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            DetailsHeader(
                state = state,
                onPlay = {
                    if (state.hasPlayableSource) {
                        onPlay(state.title, state.playableFiles, null)
                    } else {
                        viewModel.onPlayRequested()
                    }
                },
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
                onDownload = viewModel::onDownloadRequested
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
                        CastCard(member = member)
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
                            )
                        )
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
                    onPlay = viewModel::onPlayRequested
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
                                watchPageUrl = watch.watchPageUrl
                            )
                        }
                        Text(
                            text = "Options for ${watch.region}, via TMDB. " +
                                "Tapping a service opens its official watch page.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }

        item { SectionHeading("Sources") }
        item {
            if (state.playableFiles.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    state.playableFiles.forEach { file ->
                        SourceRow(file = file)
                    }
                    Text(
                        text = "Public domain — free to play. Source: Internet Archive.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = "Not in your library yet. Connect Jellyfin or Plex, or " +
                        "pick a public-domain title, to play it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
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
}

// ---------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------

@Composable
private fun DetailsHeader(
    state: TitleDetailsUiState,
    onPlay: () -> Unit,
    onToggleWatchlist: () -> Unit,
    onToggleWatched: () -> Unit,
    onShare: () -> Unit,
    onDownload: () -> Unit
) {
    val title = state.title
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (title.posterUrl != null) {
                AsyncImage(
                    model = title.posterUrl,
                    contentDescription = title.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
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

            // "Playable" (gold) vs "Info only" (muted), from the real
            // file list once loaded. Info-only titles say in plain
            // words why Play has nothing to play.
            Spacer(Modifier.height(10.dp))
            PlayabilityBadge(playability = state.playability)
            if (!state.isLoading && state.playability == Playability.INFO_ONLY) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "This title is in the catalogue for now — no video " +
                        "file is available to play yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                Button(
                    onClick = onPlay,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WatchlyGold,
                        contentColor = Color(0xFF1A1405)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Play")
                }
                OutlinedButton(onClick = onToggleWatchlist) {
                    Text(
                        if (state.isInWatchlist) "✓ Watchlist" else "+ Watchlist",
                        color = WatchlyGold
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onToggleWatched) {
                    Text(
                        if (state.isWatched) "✓ Watched" else "Mark watched",
                        color = WatchlyGold
                    )
                }
                OutlinedButton(onClick = onShare) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = null,
                        tint = WatchlyGold,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Share", color = WatchlyGold)
                }
                // Always visible: playable titles start a real
                // download; catalogue-only titles explain why not.
                OutlinedButton(onClick = onDownload) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = null,
                        tint = WatchlyGold,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(state.downloadButtonLabel, color = WatchlyGold)
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
private fun CastCard(member: CastMember) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(80.dp)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (member.imageUrl != null) {
                AsyncImage(
                    model = member.imageUrl,
                    contentDescription = member.personName,
                    contentScale = ContentScale.Crop,
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
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
                contentDescription = if (watched) "Mark unwatched" else "Mark watched",
                tint = if (watched) WatchlyGold else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WatchProviderRow(provider: WatchProvider, watchPageUrl: String?) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (watchPageUrl != null) {
                    Modifier.clickable {
                        // TMDB supplies one watch page per title +
                        // region (JustWatch-powered); it opens in the
                        // browser / official apps from there.
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(watchPageUrl))
                        )
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
                    contentDescription = provider.name,
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
private fun SourceRow(file: PlayableFile) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
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
