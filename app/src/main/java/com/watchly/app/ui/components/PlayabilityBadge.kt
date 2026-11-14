/*
 * Watchly — small "Playable" / "Info only" badge for title cards.
 *
 * Gold pill ("▶ Playable") when the title has a real video file;
 * muted pill ("Info only") when it is catalogue/metadata only.
 * Black + gold theme throughout; the muted badge deliberately uses
 * the surface/muted greys so "Info only" never shouts.
 */
package com.watchly.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.watchly.app.data.model.Playability
import com.watchly.app.ui.theme.WatchlyGold
import com.watchly.app.ui.theme.WatchlyMuted
import com.watchly.app.ui.theme.WatchlySurfaceVariant

@Composable
fun PlayabilityBadge(
    playability: Playability,
    modifier: Modifier = Modifier
) {
    when (playability) {
        Playability.PLAYABLE -> Text(
            text = "▶ Playable",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1A1405),
            modifier = modifier
                .clip(RoundedCornerShape(6.dp))
                .background(WatchlyGold)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
        Playability.INFO_ONLY -> Text(
            text = "Info only",
            style = MaterialTheme.typography.labelSmall,
            color = WatchlyMuted,
            modifier = modifier
                .clip(RoundedCornerShape(6.dp))
                .background(WatchlySurfaceVariant)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
