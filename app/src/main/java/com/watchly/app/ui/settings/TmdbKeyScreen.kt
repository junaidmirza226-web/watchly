/*
 * Watchly — TMDB key screen.
 *
 * One job: let the user paste their own free TMDB API key so the
 * full, self-updating movie catalogue (every industry — Bollywood,
 * Tollywood, Hollywood, Korean…) unlocks. The key is tested before
 * saving and never leaves the phone except to TMDB itself.
 */
package com.watchly.app.ui.settings

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchly.app.ui.theme.WatchlyGold
import com.watchly.app.ui.theme.WatchlyGoldLight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TmdbKeyScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: TmdbKeyViewModel = viewModel(
        factory = TmdbKeyViewModelFactory(context)
    )
    val state by viewModel.uiState.collectAsState()

    // System back leaves this screen too, same as the top-bar arrow.
    BackHandler { onBack() }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "TMDB Key",
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

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = if (state.hasSavedKey) {
                    "TMDB is connected ✓ — the full movie catalogue and the " +
                        "industry rows (Bollywood, Tollywood, Hollywood, Korean…) " +
                        "are on, and they update themselves."
                } else {
                    "TMDB is the big movie database that keeps itself updated — " +
                        "every industry, from Bollywood and Tollywood to Hollywood " +
                        "and Korean cinema. Paste your free TMDB key to unlock it."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Your key stays on this phone and is only sent to TMDB. " +
                    "Get one free at themoviedb.org → Settings → API.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = state.input,
                onValueChange = viewModel::onInputChange,
                label = { Text(if (state.hasSavedKey) "Replace key" else "TMDB API key") },
                singleLine = true,
                enabled = !state.isTesting,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = viewModel::testAndSave,
                enabled = !state.isTesting && state.input.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = WatchlyGold,
                    contentColor = Color(0xFF1A1405)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isTesting) {
                    CircularProgressIndicator(
                        color = Color(0xFF1A1405),
                        modifier = Modifier.height(20.dp)
                    )
                } else {
                    Text("Test & Save Key")
                }
            }

            if (state.hasSavedKey) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = viewModel::removeKey,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Remove key", color = WatchlyGold)
                }
            }

            state.message?.let { message ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = WatchlyGoldLight
                )
            }
            state.errorMessage?.let { message ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
