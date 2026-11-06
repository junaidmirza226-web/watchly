/*
 * Watchly — black + gold luxury palette.
 *
 * Dependencies: androidx.compose.ui (part of the Compose BOM).
 * Dark theme only for v1 — see WatchlyTheme.kt.
 */
package com.watchly.app.ui.theme

import androidx.compose.ui.graphics.Color

// Core luxury palette
val WatchlyBlack = Color(0xFF070707)        // App background — deep black
val WatchlySurface = Color(0xFF141414)      // Cards / surfaces
val WatchlySurfaceVariant = Color(0xFF1E1E1E) // Raised surfaces, rows, chips
val WatchlyOutline = Color(0xFF2A2A2A)      // Hairline dividers on black

// Gold family
val WatchlyGold = Color(0xFFD4AF37)         // Primary gold
val WatchlyGoldLight = Color(0xFFF1D27A)    // Light gold accent / highlights
val WatchlyGoldDark = Color(0xFF9A7B24)     // Pressed / secondary gold
val WatchlyGoldContainer = Color(0xFF3A2F10) // Gold-tinted container on black

// Text / content colours
val WatchlyOnBackground = Color(0xFFF5F0E1) // Warm off-white for primary text
val WatchlyOnSurface = Color(0xFFEDEDED)    // Body text on surfaces
val WatchlyMuted = Color(0xFF9E9E9E)        // Secondary / muted text

// Semantic
val WatchlyError = Color(0xFFCF6679)
val WatchlyOnError = Color(0xFF1A0A0D)
