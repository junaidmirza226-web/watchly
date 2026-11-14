/*
 * Watchly — the shared playability filter bar (Phase D).
 *
 * One control, three choices — All / ▶ Playable / Info only — used
 * identically on Browse, Search and Home so the filter the user
 * picks in one place means the same thing everywhere. Chips are a
 * fixed 48dp tall (cycle 14: Test Lab run 13 measured Browse's
 * sort chips at 28dp on device even though the same FilterChip +
 * heightIn(min = 48.dp) measures 48dp in the local semantics
 * harness — a fixed height removes any dependence on how the
 * minimum is resolved). The bar's "All" chip also carries a
 * distinct SPOKEN label ("All, availability filter"): on Browse
 * it sits a row away from the genre row's own "All" chip, and the
 * two shared one spoken name (run 13 labeling finding).
 */
package com.watchly.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.watchly.app.data.model.PlayabilityFilter
import com.watchly.app.ui.theme.WatchlyGold

@Composable
fun PlayabilityFilterBar(
    selected: PlayabilityFilter,
    onSelect: (PlayabilityFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        PlayabilityChip(
            label = "All",
            spokenLabel = "All, availability filter",
            isSelected = selected == PlayabilityFilter.ALL,
            onClick = { onSelect(PlayabilityFilter.ALL) }
        )
        PlayabilityChip(
            label = "▶ Playable",
            isSelected = selected == PlayabilityFilter.PLAYABLE_ONLY,
            onClick = { onSelect(PlayabilityFilter.PLAYABLE_ONLY) }
        )
        PlayabilityChip(
            label = "Info only",
            isSelected = selected == PlayabilityFilter.INFO_ONLY,
            onClick = { onSelect(PlayabilityFilter.INFO_ONLY) }
        )
    }
}

@Composable
private fun PlayabilityChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    spokenLabel: String? = null
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = WatchlyGold,
            selectedLabelColor = androidx.compose.ui.graphics.Color(0xFF1A1405)
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
