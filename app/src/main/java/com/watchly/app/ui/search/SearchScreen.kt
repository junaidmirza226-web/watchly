/*
 * Watchly — unified search screen (spec §3.1).
 *
 * One search box, results grouped by Movies / Shows / Anime as the user
 * types (after a 300 ms pause). Empty box shows recent + trending
 * searches; no results suggests trending titles to try instead. The
 * microphone button uses Android's system voice input — no extra
 * permission, no new dependency.
 */
package com.watchly.app.ui.search

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchly.app.data.model.Title
import com.watchly.app.data.search.SearchHistoryRepository
import com.watchly.app.data.settings.TmdbKeyRepository
import com.watchly.app.ui.components.PosterCard
import com.watchly.app.ui.theme.WatchlyGold
import com.watchly.app.ui.theme.WatchlyGoldLight

/** Factory so the screen can hand the ViewModel its search-history store and TMDB key store. */
class SearchViewModelFactory(
    private val history: SearchHistoryRepository,
    private val context: Context? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SearchViewModel::class.java)) {
            val keys = context?.let { TmdbKeyRepository(it.applicationContext) }
            val uiPrefs = context?.let {
                com.watchly.app.data.settings.UiPreferencesRepository(
                    it.applicationContext
                )
            }
            return SearchViewModel(history, keys, uiPrefs) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = viewModel(
        factory = SearchViewModelFactory(
            SearchHistoryRepository(LocalContext.current.applicationContext),
            LocalContext.current.applicationContext
        )
    ),
    onTitleClick: (Title) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()

    // System back leaves search too, same as the on-screen arrow.
    BackHandler { onBack() }

    // System voice input: returns the recognised text, which we feed
    // straight into the same debounced search as typed text.
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { viewModel.searchNow(it) }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Search bar row: back, field, mic / clear
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = WatchlyGold
                )
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                // No M3 floating label here, deliberately: with a
                // label, Material hides the placeholder until the
                // field is focused — and the placeholder is this
                // field's visible name (the blocking Maestro
                // journeys tap it by that exact text; a cycle-10
                // label addition hid it and broke flows 01/02/04,
                // so it was reverted the same cycle).
                placeholder = { Text("Movies, shows, anime…") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = WatchlyGold
                    )
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearQuery) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = "Clear search",
                                tint = WatchlyGold
                            )
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = WatchlyGold,
                    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                    cursorColor = WatchlyGold
                )
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Search Watchly")
                }
                runCatching { voiceLauncher.launch(intent) }
            }) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = "Voice search",
                    tint = WatchlyGold
                )
            }
        }

        // Genre chips over the current results (cycle 5): built
        // from the genres the results actually carry, composing
        // with the playability filter above.
        if (state.hasSearched && state.availableGenres.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    SearchGenreChip(
                        label = "All genres",
                        selected = state.selectedGenre == null,
                        onClick = { viewModel.setGenre(null) }
                    )
                }
                items(state.availableGenres) { genre ->
                    SearchGenreChip(
                        label = genre,
                        selected = state.selectedGenre == genre,
                        onClick = { viewModel.setGenre(genre) }
                    )
                }
            }
        }

        // No-key note (cycle 14, first-run audit): without a TMDB
        // key the biggest catalogue is not searched at all, and
        // nothing used to say so — sparse results just looked like
        // the app had nothing. One honest line, shown whenever a
        // query is in play and no key is saved; it disappears for
        // good once the key is pasted in My Library.
        if (state.query.isNotBlank() && !state.hasTmdbKey) {
            Text(
                text = "TMDB isn't searched yet — add your free TMDB key " +
                    "in My Library to search the full movie catalogue too.",
                style = MaterialTheme.typography.bodySmall,
                color = WatchlyGoldLight,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        when {
            state.isSearching && !state.hasSearched -> SearchLoading()
            state.query.isBlank() -> SearchSuggestions(
                state = state,
                onTermClick = viewModel::searchNow,
                onClearRecent = viewModel::clearRecentSearches
            )
            state.isSearchFailed -> SearchError(
                onRetry = viewModel::retrySearch
            )
            state.isFilterEmpty -> SearchFilterEmpty(
                genre = state.selectedGenre,
                onShowAll = viewModel::clearResultFilters
            )
            state.isEmptyResult -> SearchNoResults(
                state = state,
                onTermClick = viewModel::searchNow
            )
            else -> SearchResults(
                state = state,
                onTitleClick = onTitleClick
            )
        }
    }
}

// ---------------------------------------------------------------------
// Results
// ---------------------------------------------------------------------

@Composable
private fun SearchResults(
    state: SearchUiState,
    onTitleClick: (Title) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        state.partialFailureNote?.let { note ->
            item {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (state.isSearching) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = WatchlyGold)
                }
            }
        }
        if (state.visibleGroups.movies.isNotEmpty()) {
            item { ResultGroup("Movies", state.visibleGroups.movies, onTitleClick) }
        }
        if (state.visibleGroups.shows.isNotEmpty()) {
            item { ResultGroup("Shows", state.visibleGroups.shows, onTitleClick) }
        }
        if (state.visibleGroups.anime.isNotEmpty()) {
            item { ResultGroup("Anime", state.visibleGroups.anime, onTitleClick) }
        }
    }
}

@Composable
internal fun ResultGroup(
    heading: String,
    titles: List<Title>,
    onTitleClick: (Title) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = heading,
            style = MaterialTheme.typography.headlineSmall,
            color = WatchlyGold,
            // Cycle 14 (run 13 labeling): the "Shows" heading spoke
            // identically to the bottom-nav "Shows" tab. The visible
            // text is unchanged; the SPOKEN name now says it is a
            // results section, so the two never collide.
            modifier = Modifier.semantics {
                contentDescription = "$heading in search results"
            }
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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

// ---------------------------------------------------------------------
// Empty box: recent + trending
// ---------------------------------------------------------------------

@Composable
private fun SearchSuggestions(
    state: SearchUiState,
    onTermClick: (String) -> Unit,
    onClearRecent: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        if (state.recentSearches.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recent searches",
                            style = MaterialTheme.typography.headlineSmall,
                            color = WatchlyGold
                        )
                        TextButton(
                            onClick = onClearRecent,
                            // 48dp minimum touch target (cycle-4
                            // accessibility pass).
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Clear", color = WatchlyGoldLight)
                        }
                    }
                    state.recentSearches.forEach { term ->
                        SuggestionRow(
                            icon = Icons.Filled.History,
                            label = term,
                            onClick = { onTermClick(term) }
                        )
                    }
                }
            }
        }
        if (state.trendingSearches.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Trending now",
                        style = MaterialTheme.typography.headlineSmall,
                        color = WatchlyGold
                    )
                    state.trendingSearches.forEach { term ->
                        SuggestionRow(
                            icon = Icons.AutoMirrored.Filled.TrendingUp,
                            label = term,
                            onClick = { onTermClick(term) }
                        )
                    }
                }
            }
        }
        if (state.recentSearches.isEmpty() && state.trendingSearches.isEmpty()) {
            item {
                Text(
                    text = "Search movies, shows and anime.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // 48dp minimum touch target (cycle 11 audit): the
            // row's own content is ~24dp, so its 8dp vertical
            // padding landed it near 40dp — a real sub-48dp
            // target on a main screen.
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = WatchlyGold
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ---------------------------------------------------------------------
// No results + loading
// ---------------------------------------------------------------------

/** One genre chip, styled like the Browse chips (fixed 48dp height, cycle 14). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchGenreChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            selectedContainerColor = WatchlyGold,
            selectedLabelColor = Color(0xFF1A1405),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        modifier = Modifier.height(48.dp)
    )
}

/**
 * Results exist but the active filters hide every one (Phase D:
 * playability; cycle 5: genre too): say so, and offer the way
 * out — not a fake "no results" dead end.
 */
@Composable
private fun SearchFilterEmpty(genre: String?, onShowAll: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (genre != null) {
                "Results found — but no $genre titles pass the filters."
            } else {
                "Results found — but the playability filter hides them all."
            },
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

@Composable
private fun SearchNoResults(
    state: SearchUiState,
    onTermClick: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "No results for “${state.query}”",
                style = MaterialTheme.typography.headlineSmall,
                color = WatchlyGold
            )
        }
        state.partialFailureNote?.let { note ->
            item {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        item {
            Text(
                text = "Check the spelling, or try a title, actor, or genre — " +
                    "or one of these trending titles:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.trendingSearches.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.trendingSearches.take(5).forEach { term ->
                        SuggestionRow(
                            icon = Icons.AutoMirrored.Filled.TrendingUp,
                            label = term,
                            onClick = { onTermClick(term) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Every source failed, so the search never happened (cycle 13):
 * a couldn't-search state with Retry — never the "No results"
 * page, which would claim the title doesn't exist. Mirrors
 * Browse's error state (cycle 7's three-state separation).
 */
@Composable
private fun SearchError(onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "Couldn't search",
                style = MaterialTheme.typography.headlineSmall,
                color = WatchlyGold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "None of the sources answered — check your " +
                    "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
private fun SearchLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = WatchlyGold)
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Searching…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
