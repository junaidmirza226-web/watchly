/*
 * Watchly — Flagship Home Screen.
 *
 * Features:
 *  - Widescreen HD Hero Showcase (uses 16:9 backdropUrl when available) + Watch Now CTA + indicators
 *  - Live Continue Watching rail with gold progress bar & episode resume
 *  - Regional & Global Cinema Quick-Jump Hubs (Bollywood, South Indian, Tollywood, Mollywood, Hollywood, etc.)
 *  - Rich curated rows with live "See All" navigation into Multi-Filter Browse
 */
package com.watchly.app.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.watchly.app.data.browse.BrowseSort
import com.watchly.app.data.browse.INDUSTRIES
import com.watchly.app.data.browse.Industry
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.data.playback.PlaybackProgress
import com.watchly.app.ui.browse.BrowseKind
import com.watchly.app.ui.browse.BrowsePreset
import com.watchly.app.ui.browse.BrowsePresetStore
import com.watchly.app.ui.components.PosterCard
import com.watchly.app.ui.theme.WatchlyGold
import com.watchly.app.ui.theme.WatchlyGoldLight
import com.watchly.app.ui.theme.WatchlySurface
import com.watchly.app.ui.theme.WatchlySurfaceVariant
import com.watchly.app.ui.theme.WatchlyTheme
import com.watchly.app.ui.theme.watchlyGoldButtonColors
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onTitleClick: (Title) -> Unit = {},
    onSearchClick: () -> Unit = {},
    onTmdbClick: () -> Unit = {},
    onOpenBrowseKind: (BrowseKind) -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(context)
    )
    val state by viewModel.uiState.collectAsState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshKeyState()
    }

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
            state.isLoading -> HomeShimmerLoading()
            state.isEmpty && state.errorMessage != null -> HomeError(
                message = state.errorMessage!!,
                onRetry = viewModel::load
            )
            state.isEmpty -> HomeError(
                message = "Nothing to show right now.",
                onRetry = viewModel::load
            )
            else -> HomeContent(
                state = state,
                onTitleClick = onTitleClick,
                onRetry = viewModel::load,
                onTmdbClick = onTmdbClick,
                onOpenBrowsePreset = { preset ->
                    BrowsePresetStore.offer(preset)
                    onOpenBrowseKind(preset.kind)
                },
                onDismissContinueWatching = viewModel::removeFromContinueWatching
            )
        }
    }
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    onTitleClick: (Title) -> Unit,
    onRetry: () -> Unit = {},
    onTmdbClick: () -> Unit = {},
    onOpenBrowsePreset: (BrowsePreset) -> Unit = {},
    onDismissContinueWatching: (String) -> Unit = {}
) {
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
    val updateInfo by com.watchly.app.data.remote.AppUpdateChecker.updateState.collectAsState()
    val localContext = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        updateInfo?.let { update ->
            item {
                Surface(
                    color = WatchlyGold.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "New Version Available (v${update.latestVersion})",
                                style = MaterialTheme.typography.titleSmall,
                                color = WatchlyGold
                            )
                            Text(
                                text = "Tap to update Watchly automatically",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = {
                                com.watchly.app.data.remote.AppUpdateChecker.triggerInstall(
                                    localContext,
                                    update.downloadUrl
                                )
                            },
                            colors = watchlyGoldButtonColors()
                        ) {
                            Text("Update")
                        }
                    }
                }
            }
        }

        state.errorMessage?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }

        // 1. Widescreen Cinema Hero Showcase
        item {
            HeroSection(
                hero = candidates.getOrNull(heroIndex % maxOf(heroCount, 1)),
                activeIndex = heroIndex % maxOf(heroCount, 1),
                totalCount = heroCount,
                onClick = onHeroTitleClick
            )
        }

        // 2. Cinema Quick-Jump Hubs (Bollywood, South Indian, Tollywood, Mollywood, Hollywood, etc.)
        if (state.hasTmdbKey) {
            item { Spacer(Modifier.height(16.dp)) }
            item(key = "cinema-hubs-ribbon") {
                CinemaHubsQuickBar(
                    industries = INDUSTRIES,
                    onSelectIndustry = { industry ->
                        onOpenBrowsePreset(
                            BrowsePreset(kind = BrowseKind.MOVIES, industry = industry)
                        )
                    }
                )
            }
        }

        // 3. Continue Watching (renders automatically when user has watched anything)
        if (state.continueWatching.isNotEmpty()) {
            item { Spacer(Modifier.height(22.dp)) }
            item(key = "continue-watching-row") {
                ContinueWatchingRow(
                    items = state.continueWatching,
                    onResumeClick = { progress ->
                        val reconstructed = progress.toTitle()
                        onHeroTitleClick(reconstructed)
                    },
                    onDismiss = onDismissContinueWatching
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }

        // 4. Trending Movies
        item {
            TitleRow(
                heading = "Trending Movies",
                titles = state.visibleMovies,
                emptyMessage = "No movies available right now.",
                onTitleClick = onHeroTitleClick,
                failed = "Movies" in state.failedSections,
                onRetry = onRetry,
                onSeeAll = if (state.visibleMovies.isNotEmpty()) {
                    { onOpenBrowsePreset(BrowsePreset(kind = BrowseKind.MOVIES)) }
                } else null
            )
        }

        // 5. Spotlight Regional Cinema Rows right near the top!
        val spotlightCodes = listOf("hi", "te|ta|ml|kn", "te", "ml", "ta", "en")
        val spotlightHeadings = mapOf(
            "hi" to "Bollywood Hits",
            "te|ta|ml|kn" to "South Indian Blockbusters",
            "te" to "Tollywood Cinema (Telugu)",
            "ml" to "Mollywood Gems (Malayalam)",
            "ta" to "Kollywood Action & Drama",
            "en" to "Hollywood Blockbusters"
        )
        spotlightCodes.forEach { code ->
            state.visibleIndustryRows.firstOrNull { it.industry.languageCode == code }?.let { row ->
                item { Spacer(Modifier.height(24.dp)) }
                item(key = "industry-$code") {
                    TitleRow(
                        heading = spotlightHeadings[code] ?: row.industry.label,
                        titles = row.titles,
                        emptyMessage = "",
                        onTitleClick = onHeroTitleClick,
                        onSeeAll = {
                            onOpenBrowsePreset(
                                BrowsePreset(kind = BrowseKind.MOVIES, industry = row.industry)
                            )
                        }
                    )
                }
            }
        }

        if (state.topRatedMovies.isNotEmpty()) {
            item { Spacer(Modifier.height(24.dp)) }
            item(key = "top-rated-movies") {
                TitleRow(
                    heading = "Top Rated Movies",
                    titles = state.topRatedMovies,
                    emptyMessage = "",
                    onTitleClick = onHeroTitleClick,
                    onSeeAll = {
                        onOpenBrowsePreset(
                            BrowsePreset(kind = BrowseKind.MOVIES, sort = BrowseSort.TOP_RATED)
                        )
                    }
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }

        // 6. Trending TV Shows
        item {
            TitleRow(
                heading = "Trending TV Shows",
                titles = state.visibleShows,
                emptyMessage = "No shows available right now.",
                onTitleClick = onHeroTitleClick,
                failed = "TV shows" in state.failedSections,
                onRetry = onRetry,
                onSeeAll = if (state.visibleShows.isNotEmpty()) {
                    { onOpenBrowsePreset(BrowsePreset(kind = BrowseKind.SHOWS)) }
                } else null
            )
        }

        if (state.topRatedShows.isNotEmpty()) {
            item { Spacer(Modifier.height(24.dp)) }
            item(key = "top-rated-shows") {
                TitleRow(
                    heading = "Top Rated TV Series",
                    titles = state.topRatedShows,
                    emptyMessage = "",
                    onTitleClick = onHeroTitleClick,
                    onSeeAll = {
                        onOpenBrowsePreset(
                            BrowsePreset(kind = BrowseKind.SHOWS, sort = BrowseSort.TOP_RATED)
                        )
                    }
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }

        // 7. Trending Anime
        item {
            TitleRow(
                heading = "Trending Anime",
                titles = state.visibleAnime,
                emptyMessage = "No anime available right now.",
                onTitleClick = onHeroTitleClick,
                failed = "Anime" in state.failedSections,
                onRetry = onRetry,
                onSeeAll = if (state.visibleAnime.isNotEmpty()) {
                    { onOpenBrowsePreset(BrowsePreset(kind = BrowseKind.ANIME)) }
                } else null
            )
        }

        // 8. Remaining Global & Regional Cinema Rows (Sandalwood, Punjabi, Pakistani & Urdu, Korean, Turkish, etc.)
        state.visibleIndustryRows
            .filter { it.industry.languageCode !in spotlightCodes }
            .forEach { row ->
                item { Spacer(Modifier.height(24.dp)) }
                item(key = "industry-${row.industry.languageCode}") {
                    TitleRow(
                        heading = row.industry.label,
                        titles = row.titles,
                        emptyMessage = "",
                        onTitleClick = onHeroTitleClick,
                        onSeeAll = {
                            onOpenBrowsePreset(
                                BrowsePreset(kind = BrowseKind.MOVIES, industry = row.industry)
                            )
                        }
                    )
                }
            }

        state.visibleYoutubeRows.forEach { row ->
            item { Spacer(Modifier.height(24.dp)) }
            item(key = "youtube-${row.heading}") {
                TitleRow(
                    heading = row.heading,
                    titles = row.titles,
                    emptyMessage = "",
                    onTitleClick = onHeroTitleClick
                )
            }
        }

        state.visibleArchiveRows.forEach { row ->
            item { Spacer(Modifier.height(24.dp)) }
            item(key = "archive-${row.heading}") {
                TitleRow(
                    heading = row.heading,
                    titles = row.titles,
                    emptyMessage = "",
                    onTitleClick = onHeroTitleClick
                )
            }
        }

        state.visibleTraktRow?.let { row ->
            item { Spacer(Modifier.height(24.dp)) }
            item(key = "trakt-trending") {
                TitleRow(
                    heading = row.heading,
                    titles = row.titles,
                    emptyMessage = "",
                    onTitleClick = onHeroTitleClick
                )
            }
        }

        if (!state.hasTmdbKey) {
            item { Spacer(Modifier.height(24.dp)) }
            item(key = "tmdb-unlock") {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    TmdbUnlockCard(onClick = onTmdbClick)
                }
            }
        }
    }
}

@Composable
private fun CinemaHubsQuickBar(
    industries: List<Industry>,
    onSelectIndustry: (Industry) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(industries, key = { it.languageCode }) { industry ->
            Surface(
                onClick = { onSelectIndustry(industry) },
                shape = RoundedCornerShape(999.dp),
                color = WatchlySurface,
                modifier = Modifier
                    .height(40.dp)
                    .border(
                        width = 1.dp,
                        color = WatchlyGold.copy(alpha = 0.42f),
                        shape = RoundedCornerShape(999.dp)
                    )
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = industry.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = WatchlyGoldLight
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinueWatchingRow(
    items: List<PlaybackProgress>,
    onResumeClick: (PlaybackProgress) -> Unit,
    onDismiss: (String) -> Unit
) {
    Column {
        Text(
            text = "Continue Watching",
            style = MaterialTheme.typography.headlineSmall,
            color = WatchlyGold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { it.key }) { item ->
                val fraction = if (item.durationMs > 0L) {
                    (item.positionMs.toFloat() / item.durationMs.toFloat()).coerceIn(0.05f, 0.98f)
                } else 0.15f
                Surface(
                    onClick = { onResumeClick(item) },
                    shape = RoundedCornerShape(14.dp),
                    color = WatchlySurface,
                    modifier = Modifier
                        .width(220.dp)
                        .border(1.dp, WatchlyGold.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(115.dp)
                                .background(WatchlySurfaceVariant)
                        ) {
                            if (item.posterUrl != null) {
                                AsyncImage(
                                    model = item.posterUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.35f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(WatchlyGold),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        tint = Color(0xFF1A1405)
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onDismiss(item.key) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Remove ${item.titleName}",
                                    tint = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        LinearProgressIndicator(
                            progress = { fraction },
                            color = WatchlyGold,
                            trackColor = WatchlySurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                        )
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = item.titleName,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = item.episodeLabel ?: "Resume Movie",
                                style = MaterialTheme.typography.labelSmall,
                                color = WatchlyGoldLight,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun PlaybackProgress.toTitle(): Title {
    val parts = key.split(":", limit = 2)
    val prefix = parts.getOrNull(0)
    val rawId = parts.getOrNull(1)
    return when (prefix) {
        "tmdb-movie", "tmdb-tv" -> Title(
            tmdbId = rawId?.toIntOrNull(),
            name = titleName,
            posterUrl = posterUrl,
            mediaType = mediaType
        )
        "imdb" -> Title(
            imdbId = rawId,
            name = titleName,
            posterUrl = posterUrl,
            mediaType = mediaType
        )
        "archive" -> Title(
            archiveId = rawId,
            name = titleName,
            posterUrl = posterUrl,
            mediaType = mediaType
        )
        "tvmaze" -> Title(
            tvmazeId = rawId?.toIntOrNull(),
            name = titleName,
            posterUrl = posterUrl,
            mediaType = mediaType
        )
        "anilist" -> Title(
            anilistId = rawId?.toIntOrNull(),
            name = titleName,
            posterUrl = posterUrl,
            mediaType = mediaType
        )
        "yt" -> Title(
            youtubeId = rawId,
            name = titleName,
            posterUrl = posterUrl,
            mediaType = mediaType
        )
        else -> Title(
            name = titleName,
            posterUrl = posterUrl,
            mediaType = mediaType
        )
    }
}

@Composable
private fun HeroSection(
    hero: Title?,
    activeIndex: Int = 0,
    totalCount: Int = 0,
    onClick: (Title) -> Unit
) {
    val heroArtUrl = hero?.heroImageUrl
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(350.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (hero != null) {
                    Modifier.clickable { onClick(hero) }
                } else Modifier
            )
    ) {
        var heroImageFailed by remember(heroArtUrl) {
            mutableStateOf(false)
        }
        if (heroArtUrl != null && !heroImageFailed) {
            AsyncImage(
                model = heroArtUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { heroImageFailed = true },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Rich cinema gradient scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black.copy(alpha = 0.18f),
                            0.42f to Color.Black.copy(alpha = 0.25f),
                            0.78f to MaterialTheme.colorScheme.background.copy(alpha = 0.88f),
                            1.0f to MaterialTheme.colorScheme.background
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 16.dp, bottom = 18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "FEATURED",
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = WatchlyGold
                )
                hero?.languageBadge?.let { badge ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(WatchlyGold.copy(alpha = 0.20f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = WatchlyGoldLight
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = hero?.name ?: "Your next obsession awaits",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (hero?.overview != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = hero.overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hero != null) {
                        Surface(
                            onClick = { onClick(hero) },
                            shape = RoundedCornerShape(999.dp),
                            color = WatchlyGold
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = Color(0xFF1A1405),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Watch Now",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1A1405)
                                )
                            }
                        }
                    }
                    hero?.year?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = WatchlyGoldLight
                        )
                    }
                    hero?.rating?.takeIf { it > 0.0 }?.let {
                        Text(
                            text = "★ ${"%.1f".format(it)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = WatchlyGold
                        )
                    }
                }

                if (totalCount > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        repeat(totalCount) { idx ->
                            Box(
                                modifier = Modifier
                                    .height(5.dp)
                                    .width(if (idx == activeIndex) 16.dp else 5.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (idx == activeIndex) WatchlyGold
                                        else Color.White.copy(alpha = 0.35f)
                                    )
                            )
                        }
                    }
                }
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
    failed: Boolean = false,
    onRetry: () -> Unit = {},
    onSeeAll: (() -> Unit)? = null
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = heading,
                style = MaterialTheme.typography.headlineSmall,
                color = WatchlyGold,
                modifier = Modifier.weight(1f)
            )
            if (onSeeAll != null && titles.isNotEmpty()) {
                TextButton(
                    onClick = onSeeAll,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = "Explore →",
                        style = MaterialTheme.typography.labelLarge,
                        color = WatchlyGoldLight
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (titles.isEmpty() && failed) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Text(
                    text = "Failed to load.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onRetry) {
                    Text("Retry", color = WatchlyGold)
                }
            }
        } else if (titles.isEmpty() && emptyMessage.isNotEmpty()) {
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

@Composable
private fun HomeShimmerLoading() {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shimmerX by transition.animateFloat(
        initialValue = -600f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerX"
    )
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            WatchlySurfaceVariant,
            Color(0xFF2A2A2A),
            WatchlySurfaceVariant
        ),
        start = Offset(shimmerX, 0f),
        end = Offset(shimmerX + 600f, 300f)
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(350.dp)
                    .background(shimmerBrush)
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
        repeat(2) {
            item {
                Column {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .width(140.dp)
                            .height(22.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(shimmerBrush)
                    )
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(5) {
                            Column {
                                Box(
                                    modifier = Modifier
                                        .width(120.dp)
                                        .height(180.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(shimmerBrush)
                                )
                                Spacer(Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .width(90.dp)
                                        .height(14.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(shimmerBrush)
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
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
            text = "Add a TMDB key to unlock industries.",
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
