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
            return SearchViewModel(history, keys) as T
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

        when {
            state.isSearching && !state.hasSearched -> SearchLoading()
            state.query.isBlank() -> SearchSuggestions(
                state = state,
                onTermClick = viewModel::searchNow,
                onClearRecent = viewModel::clearRecentSearches
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
        if (state.groups.movies.isNotEmpty()) {
            item { ResultGroup("Movies", state.groups.movies, onTitleClick) }
        }
        if (state.groups.shows.isNotEmpty()) {
            item { ResultGroup("Shows", state.groups.shows, onTitleClick) }
        }
        if (state.groups.anime.isNotEmpty()) {
            item { ResultGroup("Anime", state.groups.anime, onTitleClick) }
        }
    }
}

@Composable
private fun ResultGroup(
    heading: String,
    titles: List<Title>,
    onTitleClick: (Title) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = heading,
            style = MaterialTheme.typography.headlineSmall,
            color = WatchlyGold
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
                        TextButton(onClick = onClearRecent) {
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
                    text = "Search movies, shows and anime — everything in one place.",
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
