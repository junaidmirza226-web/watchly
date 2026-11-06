/*
 * Watchly — app theme.
 *
 * Dependencies: androidx.compose.material3 + androidx.compose.foundation
 * (Compose BOM). Dark theme ONLY for v1: Watchly is a black + gold luxury
 * brand, so there is deliberately no light colour scheme yet.
 */
package com.watchly.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WatchlyDarkColorScheme = darkColorScheme(
    primary = WatchlyGold,
    onPrimary = Color(0xFF1A1405), // near-black text on gold buttons
    primaryContainer = WatchlyGoldContainer,
    onPrimaryContainer = WatchlyGoldLight,
    secondary = WatchlyGoldLight,
    onSecondary = Color(0xFF1A1405),
    secondaryContainer = WatchlySurfaceVariant,
    onSecondaryContainer = WatchlyOnSurface,
    tertiary = WatchlyGoldDark,
    onTertiary = WatchlyOnBackground,
    background = WatchlyBlack,
    onBackground = WatchlyOnBackground,
    surface = WatchlySurface,
    onSurface = WatchlyOnSurface,
    surfaceVariant = WatchlySurfaceVariant,
    onSurfaceVariant = WatchlyMuted,
    outline = WatchlyOutline,
    outlineVariant = WatchlyOutline,
    error = WatchlyError,
    onError = WatchlyOnError
)

/**
 * Root theme for the whole app. Wrap MainActivity content (and previews)
 * in this. The [darkTheme] parameter is accepted for future use but v1
 * always renders the dark scheme regardless of system setting.
 */
@Composable
fun WatchlyTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = WatchlyDarkColorScheme,
        typography = WatchlyTypography,
        content = content
    )
}
