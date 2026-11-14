/*
 * Watchly — typography.
 *
 * Dependencies: androidx.compose.material3 + androidx.compose.ui (Compose BOM).
 * Uses platform font families only (Serif for display, SansSerif for UI) so
 * v1 needs no downloadable-font setup. Swap in a bundled luxury serif
 * (e.g. Playfair Display) later by replacing WatchlyDisplayFamily.
 */
package com.watchly.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Serif display face — gives the wordmark and hero titles the luxury feel. */
val WatchlyDisplayFamily = FontFamily.Serif

/** Clean sans for all functional UI text. */
val WatchlyBodyFamily = FontFamily.SansSerif

val WatchlyTypography = Typography(
    // Hero / wordmark
    displayLarge = TextStyle(
        fontFamily = WatchlyDisplayFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.5.sp
    ),
    displayMedium = TextStyle(
        fontFamily = WatchlyDisplayFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.5.sp
    ),
    // Section headers (e.g. "Trending Movies")
    headlineSmall = TextStyle(
        fontFamily = WatchlyBodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleLarge = TextStyle(
        fontFamily = WatchlyBodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = WatchlyBodyFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = WatchlyBodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = WatchlyBodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = WatchlyBodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = WatchlyBodyFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelSmall = TextStyle(
        fontFamily = WatchlyBodyFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp
    )
)
