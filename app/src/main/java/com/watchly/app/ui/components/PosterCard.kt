/*
 * Watchly — shared poster card used by Home rows and the browse grids.
 *
 * 2:3 poster, clean presentation without cluttering badges, gold star + rating, year.
 * Monogram placeholder on black if poster fails or missing.
 */
package com.watchly.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.watchly.app.data.model.Playability
import com.watchly.app.data.model.Title
import com.watchly.app.data.model.playability
import com.watchly.app.ui.theme.WatchlyGold
import com.watchly.app.ui.theme.WatchlyGoldLight
import java.util.Locale

@Composable
fun PosterCard(
    title: Title,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playability = title.playability()
    val spokenPlayability = when (playability) {
        Playability.PLAYABLE -> "Playable"
        Playability.INFO_ONLY -> "Info only"
    }

    Card(
        onClick = onClick,
        modifier = modifier.semantics {
            contentDescription = "${title.name}, $spokenPlayability"
        },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                var posterFailed by remember(title.posterUrl) {
                    mutableStateOf(false)
                }
                if (title.posterUrl != null && !posterFailed) {
                    AsyncImage(
                        model = title.posterUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onError = { posterFailed = true },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = title.name.take(1).uppercase(),
                        style = MaterialTheme.typography.displayMedium,
                        color = WatchlyGold
                    )
                }
                val langBadge = title.languageBadge
                if (langBadge != null && title.originalLanguage?.lowercase() != "en") {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.78f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = langBadge,
                            style = MaterialTheme.typography.labelSmall,
                            color = WatchlyGoldLight
                        )
                    }
                }
            }
            Column(Modifier.padding(8.dp)) {
                Text(
                    text = title.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (title.rating != null && title.rating > 0.0) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            tint = WatchlyGold,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = String.format(Locale.US, "%.1f", title.rating),
                            style = MaterialTheme.typography.labelSmall,
                            color = WatchlyGoldLight
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    title.year?.let { year ->
                        Text(
                            text = year,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
