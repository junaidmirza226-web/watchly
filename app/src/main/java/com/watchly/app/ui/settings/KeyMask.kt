/*
 * Watchly — API key masking (cycle 15: keys stay hidden).
 *
 * Saved keys render masked ("••••••••0e0") and reveal only behind an
 * explicit tap, so a screenshot or screen-share can never leak them.
 */
package com.watchly.app.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Masks an API key for display: only the last 4 characters ever show. */
internal fun maskApiKey(key: String?): String {
    if (key.isNullOrBlank()) return "—"
    if (key.length <= 4) return "••••"
    return "••••••••" + key.takeLast(4)
}

/**
 * Saved-key preview: masked by default, full value only behind an
 * explicit reveal tap.
 */
@Composable
internal fun SavedKeyMaskRow(key: String?) {
    if (key.isNullOrBlank()) return
    var revealed by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Saved key: " + if (revealed) key else maskApiKey(key),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { revealed = !revealed }) {
            Icon(
                imageVector = if (revealed) {
                    Icons.Filled.VisibilityOff
                } else {
                    Icons.Filled.Visibility
                },
                contentDescription = if (revealed) {
                    "Hide saved key"
                } else {
                    "Show saved key"
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
