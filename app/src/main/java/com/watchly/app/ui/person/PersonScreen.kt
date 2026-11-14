/*
 * Watchly — Person page (spec §3.3, cycle 11).
 *
 * Reached by tapping a cast member whose row carries a TMDB
 * person id (cast from other sources stays inert — no id, no
 * page). Shows the person's photo, name, department and TMDB
 * biography — honestly absent when TMDB has none — and a
 * "Known for" grid of their other titles, each opening its own
 * details page through the same hand-over as every other card.
 *
 * Layout mirrors Title Details: scrolling content inside a Box
 * with a FIXED overlay back button (48dp, dark circle) that can
 * never scroll away — the cycle-1 back-button lesson.
 */
package com.watchly.app.ui.person

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.watchly.app.data.model.Person
import com.watchly.app.data.model.Title
import com.watchly.app.ui.components.PosterCard
import com.watchly.app.ui.theme.WatchlyGold
import com.watchly.app.ui.theme.watchlyGoldButtonColors

@Composable
fun PersonScreen(
    selected: SelectedPerson,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    /** A "Known for" card was tapped — open that title's details. */
    onTitleClick: (Title) -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: PersonViewModel = viewModel(
        factory = PersonViewModelFactory(selected, context)
    )
    val state by viewModel.uiState.collectAsState()

    // System back leaves through the same path as the arrow.
    BackHandler { onBack() }

    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 72.dp, bottom = 32.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PersonHeader(state = state)
            }

            state.errorMessage?.let { message ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = viewModel::load,
                            colors = watchlyGoldButtonColors()
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }

            if (state.isLoading && state.person == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = WatchlyGold)
                    }
                }
            }

            state.person?.let { person ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        SectionHeading("About")
                        Text(
                            text = person.biography
                                ?: "TMDB doesn't have a biography " +
                                    "for ${person.name} yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (person.biography != null) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            if (state.knownFor.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeading("Known for")
                }
                items(state.knownFor, key = { it.stableKey }) { title ->
                    PosterCard(
                        title = title,
                        onClick = { onTitleClick(title) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // FIXED back button — same pattern as Title Details: an
        // overlay sibling of the scrolling content, so it stays
        // put at every scroll position.
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = WatchlyGold
            )
        }
    }
}

/** Photo, name and department, centred — the profile header. */
@Composable
private fun PersonHeader(state: PersonUiState) {
    val person: Person? = state.person
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(128.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            // Gold monogram when the photo is missing OR fails to
            // load — never an empty grey circle (Phase D rule).
            var photoFailed by remember(person?.photoUrl) {
                mutableStateOf(false)
            }
            if (person?.photoUrl != null && !photoFailed) {
                AsyncImage(
                    model = person.photoUrl,
                    // Decorative: the name is the text directly
                    // under the photo.
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { photoFailed = true },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = state.name.take(1).uppercase(),
                    style = MaterialTheme.typography.displayMedium,
                    color = WatchlyGold
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = state.name,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        person?.knownForDepartment?.let { department ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = department,
                style = MaterialTheme.typography.titleMedium,
                color = WatchlyGold,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Gold section heading, matching Title Details (the grid's own
 * content padding supplies the horizontal inset here). */
@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        color = WatchlyGold,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}
