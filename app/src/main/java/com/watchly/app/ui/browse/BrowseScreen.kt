/*
 * Watchly — Movies, Shows and Anime browse screen with Luxury Multi-Filter Studio.
 */
package com.watchly.app.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchly.app.data.browse.BrowseRatingFilter
import com.watchly.app.data.browse.BrowseSort
import com.watchly.app.data.browse.BrowseYearFilter
import com.watchly.app.data.browse.GenreMatchMode
import com.watchly.app.data.browse.Industry
import com.watchly.app.data.model.PlayabilityFilter
import com.watchly.app.data.model.Title
import com.watchly.app.ui.components.PosterCard
import com.watchly.app.ui.components.ShimmerPosterGrid
import com.watchly.app.ui.theme.WatchlyBlack
import com.watchly.app.ui.theme.WatchlyGold
import com.watchly.app.ui.theme.WatchlyGoldLight
import com.watchly.app.ui.theme.WatchlySurface
import com.watchly.app.ui.theme.WatchlySurfaceVariant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    kind: BrowseKind,
    modifier: Modifier = Modifier,
    onTitleClick: (Title) -> Unit = {},
    onOpenSearch: (() -> Unit)? = null
) {
    val heading = when (kind) {
        BrowseKind.MOVIES -> "Movies"
        BrowseKind.SHOWS -> "Shows"
        BrowseKind.ANIME -> "Anime"
    }
    val context = LocalContext.current
    val viewModel: BrowseViewModel = viewModel(
        key = "browse_${kind.name}",
        factory = BrowseViewModelFactory(kind, context)
    )
    val state by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()
    var filterSheetOpen by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshKeyState()
    }

    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val total = info.totalItemsCount
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && lastVisible >= total - 6
        }
    }
    LaunchedEffect(nearEnd, state.hasMore, state.isLoadingMore) {
        if (nearEnd && state.hasMore && !state.isLoadingMore) {
            viewModel.loadMore()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = heading,
                    style = MaterialTheme.typography.displayMedium,
                    color = WatchlyGold
                )
            },
            actions = {
                // Luxury Multi-Filter Studio trigger
                Surface(
                    onClick = { filterSheetOpen = true },
                    shape = RoundedCornerShape(999.dp),
                    color = if (state.activeFilterCount > 0) WatchlyGold else WatchlySurfaceVariant,
                    modifier = Modifier
                        .height(40.dp)
                        .border(
                            width = 1.dp,
                            color = WatchlyGold.copy(alpha = if (state.activeFilterCount > 0) 1f else 0.45f),
                            shape = RoundedCornerShape(999.dp)
                        )
                        .semantics { contentDescription = "Open multi-filters" }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = null,
                            tint = if (state.activeFilterCount > 0) Color(0xFF1A1405) else WatchlyGold,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (state.activeFilterCount > 0) {
                                "Filters (${state.activeFilterCount})"
                            } else {
                                "Filters"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (state.activeFilterCount > 0) Color(0xFF1A1405) else WatchlyGoldLight
                        )
                    }
                }
                if (onOpenSearch != null) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onOpenSearch) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search",
                            tint = WatchlyGold
                        )
                    }
                } else {
                    Spacer(Modifier.width(8.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = WatchlyGold
            )
        )

        // Active multi-filter summary banner with 1-tap Reset
        val summary = state.activeFilterSummary
        if (summary != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .background(WatchlySurface, RoundedCornerShape(12.dp))
                    .border(1.dp, WatchlyGold.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = WatchlyGoldLight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = viewModel::clearAllFilters) {
                    Text(
                        text = "Reset",
                        style = MaterialTheme.typography.labelLarge,
                        color = WatchlyGold
                    )
                }
            }
        }

        BrowseChipRows(
            state = state,
            onClearGenres = { viewModel.selectGenre(null) },
            onToggleGenre = viewModel::toggleGenre,
            onSortSelected = viewModel::selectSort,
            onClearIndustries = { viewModel.selectIndustry(null) },
            onToggleIndustry = viewModel::toggleIndustry
        )

        when {
            state.isLoading -> ShimmerPosterGrid(
                modifier = Modifier.fillMaxSize()
            )
            state.errorMessage != null && state.titles.isEmpty() -> BrowseError(
                message = state.errorMessage!!,
                onRetry = viewModel::load
            )
            state.isEmpty && !state.isLoadingMore &&
                (
                    (state.playabilityFilter != PlayabilityFilter.ALL &&
                        state.titles.isNotEmpty()) ||
                        state.hasAnyActiveFilter
                    ) ->
                BrowseFilterEmpty(
                    genre = state.selectedGenre,
                    filter = state.playabilityFilter,
                    loadError = state.errorMessage,
                    onShowAll = viewModel::clearAllFilters
                )
            state.isEmpty -> BrowseEmpty(kind = state.kind, tmdbActive = state.tmdbActive)
            else -> BrowseGrid(
                state = state,
                gridState = gridState,
                onTitleClick = onTitleClick
            )
        }
    }

    if (filterSheetOpen) {
        LuxuryMultiFilterSheet(
            state = state,
            onDismiss = { filterSheetOpen = false },
            onToggleIndustry = viewModel::toggleIndustry,
            onClearIndustries = { viewModel.selectIndustry(null) },
            onToggleGenre = viewModel::toggleGenre,
            onClearGenres = { viewModel.selectGenre(null) },
            onGenreMatchModeSelected = viewModel::setGenreMatchMode,
            onYearSelected = viewModel::selectYearFilter,
            onRatingSelected = viewModel::selectRatingFilter,
            onSortSelected = viewModel::selectSort,
            onResetAll = viewModel::clearAllFilters
        )
    }
}

@Composable
private fun BrowseChipRows(
    state: BrowseUiState,
    onClearGenres: () -> Unit,
    onToggleGenre: (String) -> Unit,
    onSortSelected: (BrowseSort) -> Unit,
    onClearIndustries: () -> Unit,
    onToggleIndustry: (Industry) -> Unit
) {
    Column {
        // Regional & Global Cinema Hub rail (supports multi-select!)
        if (state.industries.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    GenreChip(
                        label = "All Industries",
                        selected = state.selectedIndustries.isEmpty() && state.selectedIndustry == null,
                        onClick = onClearIndustries
                    )
                }
                items(state.industries) { industry ->
                    val isSelected = industry in state.selectedIndustries ||
                        state.selectedIndustry == industry
                    GenreChip(
                        label = industry.label,
                        selected = isSelected,
                        onClick = { onToggleIndustry(industry) }
                    )
                }
            }
        }
        // Multi-Select Genre chips
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                GenreChip(
                    label = "All",
                    spokenLabel = "All, genre filter",
                    selected = state.selectedGenres.isEmpty() && state.selectedGenre == null,
                    onClick = onClearGenres
                )
            }
            items(state.genres) { genre ->
                val isSelected = genre in state.selectedGenres || state.selectedGenre == genre
                GenreChip(
                    label = genre,
                    selected = isSelected,
                    onClick = { onToggleGenre(genre) }
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LuxuryMultiFilterSheet(
    state: BrowseUiState,
    onDismiss: () -> Unit,
    onToggleIndustry: (Industry) -> Unit,
    onClearIndustries: () -> Unit,
    onToggleGenre: (String) -> Unit,
    onClearGenres: () -> Unit,
    onGenreMatchModeSelected: (GenreMatchMode) -> Unit,
    onYearSelected: (BrowseYearFilter) -> Unit,
    onRatingSelected: (BrowseRatingFilter) -> Unit,
    onSortSelected: (BrowseSort) -> Unit,
    onResetAll: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = WatchlyBlack,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Multi-Filter Studio",
                        style = MaterialTheme.typography.headlineSmall,
                        color = WatchlyGold
                    )
                    Text(
                        text = "Combine multiple cinemas, genres, eras & ratings simultaneously",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onResetAll) {
                    Text("Reset", color = WatchlyGold)
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close filters",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 1. Regional & Global Cinema (Multi-select)
            if (state.industries.isNotEmpty()) {
                FilterSectionHeader(
                    title = "Cinema & Language (Multi-Select)",
                    subtitle = if (state.selectedIndustries.isEmpty()) {
                        "Global — All Languages"
                    } else {
                        "${state.selectedIndustries.size} selected"
                    },
                    onClear = if (state.selectedIndustries.isNotEmpty()) onClearIndustries else null
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    GenreChip(
                        label = "All Global",
                        selected = state.selectedIndustries.isEmpty(),
                        onClick = onClearIndustries
                    )
                    state.industries.forEach { industry ->
                        GenreChip(
                            label = industry.label,
                            selected = industry in state.selectedIndustries,
                            onClick = { onToggleIndustry(industry) }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = WatchlySurfaceVariant)
                Spacer(Modifier.height(16.dp))
            }

            // 2. Genres (Multi-select + Match Any / Match All)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Genres (Multi-Select)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                if (state.selectedGenres.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = state.genreMatchMode == GenreMatchMode.ANY,
                            onClick = { onGenreMatchModeSelected(GenreMatchMode.ANY) },
                            label = { Text("Match Any") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = WatchlyGold,
                                selectedLabelColor = Color(0xFF1A1405)
                            )
                        )
                        FilterChip(
                            selected = state.genreMatchMode == GenreMatchMode.ALL,
                            onClick = { onGenreMatchModeSelected(GenreMatchMode.ALL) },
                            label = { Text("Match All") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = WatchlyGold,
                                selectedLabelColor = Color(0xFF1A1405)
                            )
                        )
                    }
                } else if (state.selectedGenres.isNotEmpty()) {
                    TextButton(onClick = onClearGenres) {
                        Text("Clear", color = WatchlyGold)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                GenreChip(
                    label = "All Genres",
                    selected = state.selectedGenres.isEmpty(),
                    onClick = onClearGenres
                )
                state.genres.forEach { genre ->
                    GenreChip(
                        label = genre,
                        selected = genre in state.selectedGenres,
                        onClick = { onToggleGenre(genre) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = WatchlySurfaceVariant)
            Spacer(Modifier.height(16.dp))

            // 3. Release Year / Decade
            FilterSectionHeader(
                title = "Release Year / Era",
                subtitle = state.yearFilter.label
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                BrowseYearFilter.entries.forEach { yf ->
                    GenreChip(
                        label = yf.label,
                        selected = state.yearFilter == yf,
                        onClick = { onYearSelected(yf) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = WatchlySurfaceVariant)
            Spacer(Modifier.height(16.dp))

            // 4. Minimum Audience / Critic Rating
            FilterSectionHeader(
                title = "Minimum Rating",
                subtitle = state.ratingFilter.label
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                BrowseRatingFilter.entries.forEach { rf ->
                    GenreChip(
                        label = rf.label,
                        selected = state.ratingFilter == rf,
                        onClick = { onRatingSelected(rf) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = WatchlySurfaceVariant)
            Spacer(Modifier.height(16.dp))

            // 5. Sort Order
            FilterSectionHeader(
                title = "Sort Results By",
                subtitle = sortLabel(state.sort)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                state.availableSorts.forEach { sort ->
                    GenreChip(
                        label = sortLabel(sort),
                        selected = state.sort == sort,
                        onClick = { onSortSelected(sort) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Bottom action buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onResetAll,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text("Reset All", color = WatchlyGold)
                }
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WatchlyGold,
                        contentColor = Color(0xFF1A1405)
                    ),
                    modifier = Modifier
                        .weight(1.4f)
                        .height(48.dp)
                ) {
                    Text(
                        text = if (state.visibleTitles.isNotEmpty()) {
                            "Show ${state.visibleTitles.size}+ Titles"
                        } else {
                            "Apply Filters"
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterSectionHeader(
    title: String,
    subtitle: String? = null,
    onClear: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = WatchlyGoldLight
                )
            }
        }
        if (onClear != null) {
            TextButton(onClick = onClear) {
                Text("Clear", color = WatchlyGold)
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
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
                        "No playable $genre titles yet."
                    genre != null && filter == PlayabilityFilter.INFO_ONLY ->
                        "Everything in $genre is playable."
                    genre != null ->
                        "No $genre titles found."
                    filter == PlayabilityFilter.PLAYABLE_ONLY ->
                        "Nothing playable here yet."
                    filter == PlayabilityFilter.INFO_ONLY ->
                        "Everything here is playable."
                    else -> "No titles match all selected filters."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
