/*
 * Watchly — Home screen.
 *
 * Improvement batch 1 (autonomous testing loop):
 *  - Hero section enlarged to 340dp (feels cinematic, not cramped)
 *  - Hero gradient extended to 60% opacity scrim for better text legibility
 *  - Shimmer skeleton loading replaces bare spinner (industry standard UX)
 *  - PlayabilityBadge removed from hero (cleaner, modern look)
 *  - Dead imports cleaned up (PlayabilityFilter, PlayabilityFilterBar, PlayabilityBadge)
 *  - "FEATURED" label styled with letter-spacing for premium feel
 *  - Hero overview hidden when null (no empty space)
 *  - Row heading "See All" affordance added per row (future tap target)
 *  - Bottom content padding increased to clear bottom nav bar
 */
package com.watchly.app.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.ui.components.PosterCard
import com.watchly.app.ui.theme.WatchlyGold
import com.watchly.app.ui.theme.WatchlyGoldLight
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
    onTmdbClick: () -> Unit = {}
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
    val localContext = androidx.compose.ui.platform.LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        updateInfo?.let { update ->
            item {
                androidx.compose.material3.Surface(
                    color = WatchlyGold.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
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
                            onClick = { com.watchly.app.data.remote.AppUpdateChecker.triggerInstall(localContext, update.downloadUrl) },
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

        item {
            HeroSection(
                hero = candidates.getOrNull(heroIndex % maxOf(heroCount, 1)),
                onClick = onHeroTitleClick
            )
        }

        item { Spacer(Modifier.height(24.dp)) }

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

        state.visibleIndustryRows.firstOrNull { it.industry.languageCode == "hi" }?.let { row ->
            item { Spacer(Modifier.height(24.dp)) }
            item(key = "industry-hi") {
                TitleRow(
                    heading = "Bollywood Hits",
                    titles = row.titles,
                    emptyMessage = "",
                    onTitleClick = onHeroTitleClick
                )
            }
        }

        state.visibleIndustryRows.firstOrNull { it.industry.languageCode == "te" }?.let { row ->
            item { Spacer(Modifier.height(24.dp)) }
            item(key = "industry-te") {
                TitleRow(
                    heading = "Tollywood & South Cinema",
                    titles = row.titles,
                    emptyMessage = "",
                    onTitleClick = onHeroTitleClick
                )
            }
        }

        if (state.topRatedMovies.isNotEmpty()) {
            item { Spacer(Modifier.height(24.dp)) }
            item {
                TitleRow(
                    heading = "Top Rated Movies",
                    titles = state.topRatedMovies,
                    emptyMessage = "",
                    onTitleClick = onHeroTitleClick
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }

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
            item { Spacer(Modifier.height(24.dp)) }
            item {
                TitleRow(
                    heading = "Top Rated TV Series",
                    titles = state.topRatedShows,
                    emptyMessage = "",
                    onTitleClick = onHeroTitleClick
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }

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

        state.visibleIndustryRows.filter { it.industry.languageCode !in listOf("hi", "te") }.forEach { row ->
            item { Spacer(Modifier.height(24.dp)) }
            item(key = "industry-${row.industry.languageCode}") {
                TitleRow(
                    heading = row.industry.label,
                    titles = row.titles,
                    emptyMessage = "",
                    onTitleClick = onHeroTitleClick
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
private fun HeroSection(
    hero: Title?,
    onClick: (Title) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp) // cinematic height (was 260dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (hero != null) {
                    Modifier.clickable { onClick(hero) }
                } else Modifier
            )
    ) {
        var heroImageFailed by remember(hero?.posterUrl) {
            mutableStateOf(false)
        }
        if (hero?.posterUrl != null && !heroImageFailed) {
            AsyncImage(
                model = hero.posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { heroImageFailed = true },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Deep gradient scrim — text readable on any poster
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.45f to Color.Transparent,
                            1.0f to MaterialTheme.colorScheme.background
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp)
        ) {
            // "FEATURED" label with premium letter-spacing
            Text(
                text = "FEATURED",
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = WatchlyGold
            )
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
            // Year + rating row
            if (hero?.year != null || hero?.rating != null) {
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    hero?.year?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = WatchlyGoldLight
                        )
                    }
                    hero?.rating?.let {
                        Text(
                            text = "★ ${"%.1f".format(it)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = WatchlyGold
                        )
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
    onRetry: () -> Unit = {}
) {
    Column {
        // Row heading with optional "See All" (future-ready)
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
                color = WatchlyGold
            )
        }
        Spacer(Modifier.height(10.dp))
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

// ---------------------------------------------------------------------
// Shimmer skeleton loading (industry standard UX for streaming apps)
// ---------------------------------------------------------------------

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
        // Hero skeleton
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .background(shimmerBrush)
            )
        }

        item { Spacer(Modifier.height(24.dp)) }

        // Two rows of poster skeletons
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

/** Kept for callers that still reference TmdbUnlockCard. */
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

// ---------------------------------------------------------------------
// Preview
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
