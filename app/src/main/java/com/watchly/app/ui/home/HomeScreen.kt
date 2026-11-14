/*
 * Watchly — Home screen.
 *
 * ASSUMED DEPENDENCIES (Compose BOM unless noted):
 *  - androidx.compose.material3, androidx.compose.foundation,
 *    androidx.compose.material:material-icons-extended (Star icon)
 *  - io.coil-kt:coil-compose:2.7.0 (AsyncImage poster loading)
 *  - androidx.lifecycle:lifecycle-viewmodel-compose (default viewModel())
 *
 * Layout: gold "Watchly" top app bar → hero feature → horizontal trending
 * rows (Movies / TV Shows / Anime) of poster cards with title + rating.
 * Loading, full-error, and empty states are all handled here.
 */
package com.watchly.app.ui.home

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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.PlayabilityFilter
import com.watchly.app.data.model.Title
import com.watchly.app.data.model.playability
import com.watchly.app.ui.components.PlayabilityBadge
import com.watchly.app.ui.components.PlayabilityFilterBar
import com.watchly.app.ui.components.PosterCard
import com.watchly.app.ui.theme.WatchlyGold
import com.watchly.app.ui.theme.WatchlyGoldLight
import com.watchly.app.ui.theme.WatchlyTheme
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onTitleClick: (Title) -> Unit = {},
    onSearchClick: () -> Unit = {},
    onTmdbClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(context)
    )
    val state by viewModel.uiState.collectAsState()

    // Pick up a TMDB key added/removed on the TMDB screen.
    LaunchedEffect(Unit) { viewModel.refreshKeyState() }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "Watchly",
                    style = MaterialTheme.typography.displayMedium,
                    color = WatchlyGold
                )
            },
            actions = {
                IconButton(onClick = onSearchClick) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search",
                        tint = WatchlyGold
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = WatchlyGold
            )
        )

        when {
            state.isLoading -> HomeLoading()
            state.isEmpty && state.errorMessage != null -> HomeError(
                message = state.errorMessage!!,
                onRetry = viewModel::load
            )
            // Content exists but the playability filter hides all
            // of it (Phase D): say so, with the way out.
            state.isEmpty && state.playabilityFilter != PlayabilityFilter.ALL &&
                state.hasAnyContent -> HomeFilterEmpty(
                    onShowAll = {
                        viewModel.setPlayabilityFilter(PlayabilityFilter.ALL)
                    }
                )
            state.isEmpty -> HomeError(
                message = "Nothing to show right now.",
                onRetry = viewModel::load
            )
            else -> HomeContent(
                state = state,
                onTitleClick = onTitleClick,
                onRetry = viewModel::load,
                onFilterChange = viewModel::setPlayabilityFilter,
                onTmdbClick = onTmdbClick
            )
        }
    }
}

// ---------------------------------------------------------------------
// Content
// ---------------------------------------------------------------------

@Composable
private fun HomeContent(
    state: HomeUiState,
    onTitleClick: (Title) -> Unit,
    onRetry: () -> Unit = {},
    onFilterChange: (PlayabilityFilter) -> Unit = {},
    onTmdbClick: () -> Unit = {}
) {
    // Hero spotlight rotates through up to 5 trending titles every 8s
    // (spec §2.3). Rotation pauses once the user taps anything.
    val candidates = state.heroCandidates
    var heroIndex by remember { mutableIntStateOf(0) }
    var heroPaused by remember { mutableStateOf(false) }
    val heroCount = candidates.size
    LaunchedEffect(heroIndex, heroPaused, heroCount) {
        if (!heroPaused && heroCount > 1) {
            delay(8_000)
            heroIndex = (heroIndex + 1) % heroCount
        }
    }
    val onHeroTitleClick: (Title) -> Unit = {
        heroPaused = true
        onTitleClick(it)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Partial-failure banner (one row failed, the rest loaded)
        state.errorMessage?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

        // Hero
        item {
            HeroSection(
                hero = candidates.getOrNull(heroIndex % maxOf(heroCount, 1)),
                onClick = onHeroTitleClick
            )
        }

        // Trending rows (visible = playability-filtered views). The
        // three backbone rows know whether their load FAILED and
        // offer Retry instead of a dead-end empty message.
        item {
            TitleRow(
                heading = "Trending Movies",
                titles = state.visibleMovies,
                emptyMessage = "No movies available right now.",
                onTitleClick = onHeroTitleClick,
                failed = "Movies" in state.failedSections,
                onRetry = onRetry
            )
        }
        if (state.topRatedMovies.isNotEmpty()) {
            item {
                TitleRow(
                    heading = "Top Rated Movies",
                    titles = state.topRatedMovies,
                    emptyMessage = "",
                    onTitleClick = onHeroTitleClick
                )
            }
        }
        item {
            TitleRow(
                heading = "Trending TV Shows",
                titles = state.visibleShows,
                emptyMessage = "No shows available right now.",
                onTitleClick = onHeroTitleClick,
                failed = "TV shows" in state.failedSections,
                onRetry = onRetry
            )
        }
        if (state.topRatedShows.isNotEmpty()) {
            item {
                TitleRow(
                    heading = "Top Rated TV Series",
                    titles = state.topRatedShows,
                    emptyMessage = "",
                    onTitleClick = onHeroTitleClick
                )
            }
        }
        item {
            TitleRow(
                heading = "Trending Anime",
                titles = state.visibleAnime,
                emptyMessage = "No anime available right now.",
                onTitleClick = onHeroTitleClick,
                failed = "Anime" in state.failedSections,
                onRetry = onRetry
            )
        }

        // One row per film industry (TMDB, only when a key is saved —
        // the ViewModel leaves this empty otherwise). TMDB updates
        // these catalogues daily, so the rows are always current.
        state.visibleIndustryRows.forEach { row ->
            item(key = "industry-${row.industry.languageCode}") {
                TitleRow(
                    heading = row.industry.label,
                    titles = row.titles,
                    emptyMessage = "",
                    onTitleClick = onHeroTitleClick
                )
            }
        }

        // Official YouTube channels (cycle 4): full films/episodes
        // from the channels' own uploads, playable in YouTube's
        // official embedded player.
        state.visibleYoutubeRows.forEach { row ->
            item(key = "youtube-${row.heading}") {
                TitleRow(
                    heading = row.heading,
                    titles = row.titles,
                    emptyMessage = "",
                    onTitleClick = onHeroTitleClick
                )
            }
        }

        // Wider Internet Archive shelves (cycle 4): Classic TV,
        // Animation & Cartoons, Prelinger — all public domain.
        state.visibleArchiveRows.forEach { row ->
            item(key = "archive-${row.heading}") {
                TitleRow(
                    heading = row.heading,
                    titles = row.titles,
                    emptyMessage = "",
                    onTitleClick = onHeroTitleClick
                )
            }
        }

        // Trending on Trakt (cycle 4, only with the user's Client ID).
        state.visibleTraktRow?.let { row ->
            item(key = "trakt") {
                TitleRow(
                    heading = row.heading,
                    titles = row.titles,
                    emptyMessage = "",
                    onTitleClick = onHeroTitleClick
                )
            }
        }
    }
}

/**
 * The Home no-key card (cycle 14): what the free TMDB key turns
 * on, in the same words My Library's TMDB card uses, with the
 * button going straight to the key screen. Renders ONLY when no
 * key is saved (HomeContent gates it on `!state.hasTmdbKey`).
 */
@Composable
internal fun TmdbUnlockCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Text(
            text = "Unlock every industry",
            style = MaterialTheme.typography.titleLarge,
            color = WatchlyGold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "You're seeing free public-domain films, shows and " +
                "anime. Add your free TMDB key to unlock every industry " +
                "— Bollywood, Tollywood, Hollywood, Korean and more — " +
                "always up to date.",
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
            Text("Add TMDB Key")
        }
    }
}

@Composable
private fun HeroSection(
    hero: Title?,
    onClick: (Title) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        // A hero poster that fails to load simply isn't drawn
        // (Phase D): the tinted backdrop + title below carry the
        // slot — never a broken-image hero.
        var heroImageFailed by remember(hero?.posterUrl) {
            mutableStateOf(false)
        }
        if (hero?.posterUrl != null && !heroImageFailed) {
            AsyncImage(
                model = hero.posterUrl,
                // Decorative: the hero's name is rendered as text
                // on the same card — describing the image with it
                // too makes TalkBack say it twice (Test Lab run 4:
                // content labeling tips).
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { heroImageFailed = true },
                modifier = Modifier.fillMaxSize()
            )
        }
        // Gold-tinted gradient scrim so hero text stays readable
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
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "FEATURED",
                    style = MaterialTheme.typography.labelSmall,
                    color = WatchlyGoldLight
                )
                // "Playable" (gold) vs "Info only" (muted), honest at a
                // glance — the hero rotates through shows, anime and
                // public-domain films, and most of them can't play yet.
                hero?.let { PlayabilityBadge(playability = it.playability()) }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = hero?.name ?: "Your next obsession awaits",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (hero?.overview != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = hero.overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TitleRow(
    heading: String,
    titles: List<Title>,
    emptyMessage: String,
    onTitleClick: (Title) -> Unit,
    /** Phase D: this row's load failed — empty means "couldn't load". */
    failed: Boolean = false,
    onRetry: () -> Unit = {}
) {
    Column {
        Text(
            text = heading,
            style = MaterialTheme.typography.headlineSmall,
            color = WatchlyGold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(12.dp))
        if (titles.isEmpty() && failed) {
            // A failed row is a retry-able state, never a dead end
            // that claims nothing exists (Phase D).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Text(
                    text = "Couldn't load this row.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                androidx.compose.material3.TextButton(onClick = onRetry) {
                    Text("Retry", color = WatchlyGold)
                }
            }
        } else if (titles.isEmpty()) {
            Text(
                text = emptyMessage,
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

// ---------------------------------------------------------------------
// Loading / error states
// ---------------------------------------------------------------------

@Composable
private fun HomeLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = WatchlyGold)
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Loading Watchly…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Home has content, but the playability filter hides all of it
 * (Phase D) — plain message + the way out.
 */
@Composable
private fun HomeFilterEmpty(onShowAll: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "The playability filter hides everything on Home right now.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onShowAll,
                colors = ButtonDefaults.buttonColors(
                    containerColor = WatchlyGold,
                    contentColor = Color(0xFF1A1405)
                )
            ) {
                Text("Show all titles")
            }
        }
    }
}

@Composable
private fun HomeError(
    message: String,
    onRetry: () -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "Something went wrong",
                style = MaterialTheme.typography.headlineSmall,
                color = WatchlyGold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = WatchlyGold,
                    contentColor = Color(0xFF1A1405)
                )
            ) {
                Text("Retry")
            }
        }
    }
}

// ---------------------------------------------------------------------
// Preview (design-time only; uses fake data, no network)
// ---------------------------------------------------------------------

@Preview(showBackground = true, backgroundColor = 0xFF070707)
@Composable
private fun HomeContentPreview() {
    WatchlyTheme {
        HomeContent(
            state = HomeUiState(
                isLoading = false,
                shows = listOf(
                    Title(
                        tvmazeId = 1,
                        name = "Sample Show",
                        rating = 8.7,
                        mediaType = MediaType.TV,
                        year = "2024"
                    )
                ),
                anime = listOf(
                    Title(
                        anilistId = 2,
                        name = "Sample Anime",
                        rating = 9.1,
                        mediaType = MediaType.ANIME,
                        year = "2025"
                    )
                )
            ),
            onTitleClick = {}
        )
    }
}
