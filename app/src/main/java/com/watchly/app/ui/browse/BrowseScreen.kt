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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchly.app.data.browse.BrowseSort
import com.watchly.app.data.model.Title
import com.watchly.app.data.model.PlayabilityFilter
import com.watchly.app.ui.components.PlayabilityFilterBar
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
                    state.visibleTitles.isNotEmpty() &&
                    lastIndex >= state.visibleTitles.size - 6
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

        // The playability filter bar (Phase D) stays visible in
        // every state — including empty — so a filter that hides
        // everything is always one tap from being lifted.
        PlayabilityFilterBar(
            selected = state.playabilityFilter,
            onSelect = viewModel::setPlayabilityFilter,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // Cycle 5: the genre / sort / industry chips live OUTSIDE
        // the state switch, so they stay reachable in every state —
        // above all in the filter-empty state, where the genre that
        // caused the empty grid must be changeable.
        BrowseChipRows(
            state = state,
            onGenreSelected = viewModel::selectGenre,
            onSortSelected = viewModel::selectSort,
            onIndustrySelected = viewModel::selectIndustry
        )

        when {
            state.isLoading -> BrowseLoading()
            // Load failed with NOTHING loaded: the full error
            // state with Retry. Keyed on the raw list (cycle 7): a
            // load-more failure with titles already loaded must
            // NOT nuke the screen — it surfaces as a note on the
            // states below / the grid banner instead.
            state.errorMessage != null && state.titles.isEmpty() -> BrowseError(
                message = state.errorMessage!!,
                onRetry = viewModel::load
            )
            // An empty grid ANY active filter can explain (genre,
            // playability, or the two combined) gets the filter
            // empty state — never a bare "nothing found" dead end.
            state.isEmpty && !state.isLoadingMore &&
                (
                    (state.playabilityFilter != PlayabilityFilter.ALL &&
                        state.titles.isNotEmpty()) ||
                        state.selectedGenre != null
                    ) -> BrowseFilterEmpty(
                    genre = state.selectedGenre,
                    filter = state.playabilityFilter,
                    loadError = state.errorMessage,
                    onShowAll = viewModel::clearAllFilters
                )
            state.isEmpty -> BrowseEmpty(kind = kind, tmdbActive = state.tmdbActive)
            else -> BrowseGrid(
                state = state,
                gridState = gridState,
                onTitleClick = onTitleClick
            )
        }
    }
}

/**
 * Industry + genre + sort chip rows (cycle 5: hoisted out of the
 * grid so they render in every state, including empty).
 */
@Composable
private fun BrowseChipRows(
    state: BrowseUiState,
    onGenreSelected: (String?) -> Unit,
    onSortSelected: (BrowseSort) -> Unit,
    onIndustrySelected: (com.watchly.app.data.browse.Industry?) -> Unit
) {
    Column {
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
                    // Spoken apart from the availability row's own
                    // "All" chip (cycle 14, run 13 labeling): the
                    // visible text stays "All" on both.
                    spokenLabel = "All, genre filter",
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
    }
}

// ---------------------------------------------------------------------
// Grid
// ---------------------------------------------------------------------

@Composable
private fun BrowseGrid(
    state: BrowseUiState,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
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
            items(state.visibleTitles, key = { it.stableKey }) { title ->
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
internal fun GenreChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    spokenLabel: String? = null
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
        ),
        // Fixed 48dp height (cycle-4 set the floor as a minimum;
        // cycle 14 makes it exact — Test Lab run 13 measured the
        // sort chips at 28dp on a real device even though the
        // minimum-height version measures 48dp in the local
        // semantics harness, so the floor no longer depends on
        // how a minimum constraint is resolved downstream).
        modifier = Modifier
            .height(48.dp)
            .then(
                if (spokenLabel != null) {
                    Modifier.semantics { contentDescription = spokenLabel }
                } else {
                    Modifier
                }
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

/**
 * Empty because a filter hides everything (Phase D: playability;
 * cycle 5: genre, or the two combined) — says which filter did it
 * and offers the way out, instead of a "nothing found" dead end.
 */
@Composable
private fun BrowseFilterEmpty(
    genre: String?,
    filter: PlayabilityFilter,
    loadError: String? = null,
    onShowAll: () -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = when {
                    genre != null && filter == PlayabilityFilter.PLAYABLE_ONLY ->
                        "No playable $genre titles here yet. Playable titles " +
                            "come from Public Domain films and official " +
                            "YouTube channels."
                    genre != null && filter == PlayabilityFilter.INFO_ONLY ->
                        "No catalogue-only $genre titles here — everything " +
                            "in $genre plays in Watchly."
                    genre != null ->
                        "No titles found for $genre yet."
                    filter == PlayabilityFilter.PLAYABLE_ONLY ->
                        "Nothing playable here yet. Playable titles come " +
                            "from Public Domain films and official YouTube channels."
                    filter == PlayabilityFilter.INFO_ONLY ->
                        "Everything loaded here is playable — no " +
                            "catalogue-only titles to show."
                    else -> "Nothing found."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // A load-more failure behind a filtered-empty grid
            // (cycle 7): the filter explanation stays primary, but
            // the failure is no longer invisible.
            if (loadError != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = loadError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onShowAll,
                colors = ButtonDefaults.buttonColors(
                    containerColor = WatchlyGold,
                    contentColor = Color(0xFF1A1405)
                )
            ) {
                Text("Show everything")
            }
        }
    }
}

@Composable
private fun BrowseEmpty(kind: BrowseKind, tmdbActive: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            // Genre wording lives ONLY in BrowseFilterEmpty,
            // which renders when a genre is actually selected.
            // This state means "loaded, and genuinely nothing
            // here" — blaming a genre here lied whenever the
            // selection was All (cycle 7).
            text = when (kind) {
                BrowseKind.MOVIES ->
                    if (tmdbActive) {
                        "No films found yet."
                    } else {
                        "No public-domain films found yet."
                    }
                BrowseKind.SHOWS -> "No shows found yet."
                BrowseKind.ANIME -> "No anime found yet."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp)
        )
    }
}
