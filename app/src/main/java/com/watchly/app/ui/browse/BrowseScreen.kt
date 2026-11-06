/*
 * Watchly — browse tab screen (Movies / Shows / Anime).
 *
 * Spec §2.2: genre chips filter the grid in place, sort options
 * (Popular / Top Rated / Newest / A–Z), grid loads more as the user
 * scrolls — no "next page" button. One screen, three data sources,
 * driven by BrowseViewModel(kind).
 */
package com.watchly.app.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchly.app.data.browse.BrowseSort
import com.watchly.app.data.model.Title
import com.watchly.app.ui.components.PosterCard
import com.watchly.app.ui.theme.WatchlyGold
import com.watchly.app.ui.theme.WatchlyGoldLight
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    kind: BrowseKind,
    modifier: Modifier = Modifier,
    onTitleClick: (Title) -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: BrowseViewModel = viewModel(
        factory = BrowseViewModelFactory(kind, context)
    )
    val state by viewModel.uiState.collectAsState()

    // Pick up a TMDB key added/removed since this tab last loaded.
    LaunchedEffect(Unit) { viewModel.refreshKeyState() }

    val heading = when (kind) {
        BrowseKind.MOVIES -> "Movies"
        BrowseKind.SHOWS -> "Shows"
        BrowseKind.ANIME -> "Anime"
    }
    val gridState = rememberLazyGridState()

    // Infinite scroll: when the last visible item is within 6 of the end,
    // ask for the next chunk.
    LaunchedEffect(gridState, state.hasMore) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .filter { lastIndex ->
                lastIndex != null &&
                    state.titles.isNotEmpty() &&
                    lastIndex >= state.titles.size - 6
            }
            .collect { viewModel.loadMore() }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = heading,
                    style = MaterialTheme.typography.displayMedium,
                    color = WatchlyGold
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = WatchlyGold
            )
        )

        when {
            state.isLoading -> BrowseLoading()
            state.isEmpty && state.errorMessage != null -> BrowseError(
                message = state.errorMessage!!,
                onRetry = viewModel::load
            )
            state.isEmpty -> BrowseEmpty(kind = kind, tmdbActive = state.tmdbActive)
            else -> BrowseGrid(
                state = state,
                gridState = gridState,
                onGenreSelected = viewModel::selectGenre,
                onSortSelected = viewModel::selectSort,
                onIndustrySelected = viewModel::selectIndustry,
                onTitleClick = onTitleClick
            )
        }
    }
}

// ---------------------------------------------------------------------
// Grid
// ---------------------------------------------------------------------

@Composable
private fun BrowseGrid(
    state: BrowseUiState,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    onGenreSelected: (String?) -> Unit,
    onSortSelected: (BrowseSort) -> Unit,
    onIndustrySelected: (com.watchly.app.data.browse.Industry?) -> Unit,
    onTitleClick: (Title) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        // No-key note (Movies tab): the public-domain catalogue is
        // showing; a free TMDB key unlocks the full, self-updating
        // catalogue. Honest and non-blocking.
        if (state.showTmdbPrompt) {
            Text(
                text = "Showing free public-domain films. Add your free TMDB key " +
                    "in My Library to unlock the full movie catalogue — every " +
                    "industry, always up to date.",
                style = MaterialTheme.typography.bodySmall,
                color = WatchlyGoldLight,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        // Industry chips (Movies + TMDB): filter by original language.
        if (state.industries.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    GenreChip(
                        label = "All Industries",
                        selected = state.selectedIndustry == null,
                        onClick = { onIndustrySelected(null) }
                    )
                }
                items(state.industries) { industry ->
                    GenreChip(
                        label = industry.label,
                        selected = state.selectedIndustry == industry,
                        onClick = { onIndustrySelected(industry) }
                    )
                }
            }
        }
        // Genre chips
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                GenreChip(
                    label = "All",
                    selected = state.selectedGenre == null,
                    onClick = { onGenreSelected(null) }
                )
            }
            items(state.genres) { genre ->
                GenreChip(
                    label = genre,
                    selected = state.selectedGenre == genre,
                    onClick = { onGenreSelected(genre) }
                )
            }
        }
        // Sort chips
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.availableSorts) { sort ->
                GenreChip(
                    label = sortLabel(sort),
                    selected = state.sort == sort,
                    onClick = { onSortSelected(sort) }
                )
            }
        }

        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(state.titles, key = { it.stableKey }) { title ->
                PosterCard(title = title, onClick = { onTitleClick(title) })
            }
            if (state.isLoadingMore) {
                item(span = { GridItemSpan(3) }) {
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
        }
    }
}

@Composable
private fun GenreChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = WatchlyGold,
            selectedLabelColor = Color(0xFF1A1405),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

private fun sortLabel(sort: BrowseSort): String = when (sort) {
    BrowseSort.POPULAR -> "Popular"
    BrowseSort.TOP_RATED -> "Top Rated"
    BrowseSort.NEWEST -> "Newest"
    BrowseSort.A_Z -> "A–Z"
}

// ---------------------------------------------------------------------
// Loading / error / empty states
// ---------------------------------------------------------------------

@Composable
private fun BrowseLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = WatchlyGold)
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Loading…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BrowseError(
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
private fun BrowseEmpty(kind: BrowseKind, tmdbActive: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = when (kind) {
                BrowseKind.MOVIES ->
                    if (tmdbActive) {
                        "No films found for this selection yet."
                    } else {
                        "No public-domain films found for this genre yet."
                    }
                BrowseKind.SHOWS -> "No shows found for this genre yet."
                BrowseKind.ANIME -> "No anime found for this genre yet."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp)
        )
    }
}
