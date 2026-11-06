package com.watchly.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.watchly.app.data.model.Playability

/**
 * Reference component exercised by WatchlyHeroCardScreenshotTest.
 * Copy to: app/src/main/java/com/watchly/app/ui/components/
 * Skip this file if the app already has an equivalent card — adapt the
 * screenshot test to render the real one instead.
 *
 * Watchly house style: true-black surfaces, one gold accent, no other hues
 * except the artwork itself.
 */
object WatchlyColors {
    val Black = Color(0xFF0B0B0B)
    val Surface = Color(0xFF161616)
    val Gold = Color(0xFFD4AF37)
    val GoldBright = Color(0xFFE8C15A)
    val TextPrimary = Color(0xFFF5F0E1)
    val TextSecondary = Color(0xFFB8B2A7)
}

/**
 * Large "hero" card for the home screen's featured title.
 * Artwork is represented by [artworkBrush] so screenshot tests need no
 * image loading / network — pass a Coil painter in the real app instead.
 *
 * [playability] shows the "▶ Playable" / "Info only" badge when given;
 * it takes the top-start slot over the generic [badge] label, so the
 * two can never overlap.
 */
@Composable
fun WatchlyHeroCard(
    title: String,
    metaLine: String,
    overview: String,
    badge: String? = null,
    playability: Playability? = null,
    artworkBrush: Brush = Brush.verticalGradient(
        listOf(WatchlyColors.Surface, WatchlyColors.Black),
    ),
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(artworkBrush),
    ) {
        if (playability != null) {
            PlayabilityBadge(
                playability = playability,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
            )
        } else if (badge != null) {
            Text(
                text = badge.uppercase(),
                color = WatchlyColors.Black,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(WatchlyColors.Gold)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, WatchlyColors.Black.copy(alpha = 0.92f)),
                    ),
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                color = WatchlyColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "★ Featured",
                    color = WatchlyColors.GoldBright,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = metaLine,
                    color = WatchlyColors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                text = overview,
                color = WatchlyColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
