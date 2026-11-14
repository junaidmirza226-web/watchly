/*
 * Watchly — API Keys hub (cycle 4).
 *
 * ONE Settings area for every provider key, so the user pastes all
 * of them in a single sitting: TMDB (the catalogue) plus OMDb,
 * Trakt, OpenSubtitles and Fanart. Each row shows whether that key
 * is saved and opens its own test-and-save screen.
 */
package com.watchly.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.watchly.app.data.settings.ApiProvider
import com.watchly.app.data.settings.ProviderKeysRepository
import com.watchly.app.data.settings.TmdbKeyRepository
import com.watchly.app.ui.theme.WatchlyGold
import com.watchly.app.ui.theme.WatchlyGoldLight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onTmdbClick: () -> Unit = {},
    onProviderClick: (ApiProvider) -> Unit = {}
) {
    val context = LocalContext.current
    // Read fresh on every composition of this screen: returning from
    // a key screen re-reads the store, so statuses are never stale.
    val tmdbHasKey = remember(context) {
        TmdbKeyRepository(context.applicationContext).hasKey()
    }
    val providerKeys = remember(context) {
        ProviderKeysRepository(context.applicationContext)
    }

    BackHandler { onBack() }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "API Keys",
                    style = MaterialTheme.typography.displayMedium,
                    color = WatchlyGold
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = WatchlyGold
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = WatchlyGold
            )
        )

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "All keys are free, stay on this phone, and are only " +
                    "sent to their own service. Each one is tested before " +
                    "it is saved.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            ApiKeyRow(
                name = "TMDB",
                benefit = "The full, self-updating movie & show catalogue",
                hasKey = tmdbHasKey,
                onClick = onTmdbClick
            )
            ApiProvider.entries.forEach { provider ->
                Spacer(Modifier.height(12.dp))
                ApiKeyRow(
                    name = provider.displayName,
                    benefit = benefitLine(provider),
                    hasKey = providerKeys.hasKey(provider),
                    onClick = { onProviderClick(provider) }
                )
            }
        }
    }
}

/** One-line "what this key turns on" per provider. */
private fun benefitLine(provider: ApiProvider): String = when (provider) {
    ApiProvider.OMDB -> "IMDb, Rotten Tomatoes & Metacritic ratings"
    ApiProvider.TRAKT -> "Trending on Trakt row + community ratings"
    ApiProvider.OPENSUBTITLES -> "Subtitles inside the player"
    ApiProvider.FANART -> "HD logos & extra artwork on title pages"
}

/** One tappable provider row: name, benefit, saved state, chevron. */
@Composable
private fun ApiKeyRow(
    name: String,
    benefit: String,
    hasKey: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = WatchlyGold
            )
            Text(
                text = benefit,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (hasKey) "Connected ✓" else "Not added yet",
                style = MaterialTheme.typography.bodySmall,
                color = if (hasKey) WatchlyGoldLight else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            // Decorative chevron (cycle 8): the row is one clickable
            // merged node that already speaks the provider name from
            // its text — a description repeating the name ("Open
            // $name key settings") made TalkBack say it twice in one
            // node (same speakable-duplicate class as the collage).
            contentDescription = null,
            tint = WatchlyGold
        )
    }
}
