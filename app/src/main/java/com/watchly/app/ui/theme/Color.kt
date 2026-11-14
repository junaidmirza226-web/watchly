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
val WatchlyMuted = Color(0xFFB5B5B5)        // Secondary / muted text
// Brightened from #9E9E9E in cycle 4 (Test Lab low-contrast items):
// #B5B5B5 on the #1E1E1E raised surface is ~8:1, comfortably past
// WCAG AA for small text, and still reads as muted grey next to the
// warm off-white primary text.

// Disabled-state content (cycle 7, Test Lab low-contrast pool):
// Material3's DEFAULT disabled colours composite onSurface at
// 38% over a 12% container, which lands near 3:1 on Watchly's
// near-black surfaces — below WCAG AA for the button text. The
// gold buttons therefore set this explicit content colour on
// WatchlySurfaceVariant instead: #9E9E9E on #1E1E1E is ~6.2:1,
// comfortably past AA, and still reads as the greyed form next
// to the active gold. Pinned by DisabledButtonContrastTest.
val WatchlyDisabledContent = Color(0xFF9E9E9E)

// Semantic
val WatchlyError = Color(0xFFCF6679)
val WatchlyOnError = Color(0xFF1A0A0D)
