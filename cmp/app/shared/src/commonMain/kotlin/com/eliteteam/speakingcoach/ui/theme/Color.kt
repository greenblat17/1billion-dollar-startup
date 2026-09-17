package com.eliteteam.speakingcoach.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Sampled from ai_docs/design/screens/ (2026-09-17 Releva mockups).
private val RelevaBlue = Color(0xFF1870F8)
private val RelevaOnBlue = Color(0xFFFFFFFF)
private val RelevaBlueSoft = Color(0xFFE8E8F8)
private val RelevaBlueMist = Color(0xFFF2F6FF)
private val RelevaCream = Color(0xFFF8F8F0)
private val RelevaInk = Color(0xFF151918)
private val RelevaMuted = Color(0xFFA8A8B0)
private val RelevaCard = Color(0xFFFDFDFD)
private val RelevaOutline = Color(0xFFE4E6EE)
private val RelevaHangup = Color(0xFFF83030)

internal val LightColorScheme = lightColorScheme(
    primary = RelevaBlue,
    onPrimary = RelevaOnBlue,
    primaryContainer = RelevaBlueMist,
    onPrimaryContainer = RelevaInk,
    secondary = RelevaBlue,
    onSecondary = RelevaOnBlue,
    secondaryContainer = RelevaBlueSoft,
    onSecondaryContainer = RelevaInk,
    background = RelevaCream,
    onBackground = RelevaInk,
    surface = RelevaCream,
    onSurface = RelevaInk,
    surfaceVariant = RelevaCard,
    onSurfaceVariant = RelevaMuted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = RelevaCard,
    surfaceContainer = RelevaCard,
    surfaceContainerHigh = RelevaBlueMist,
    surfaceContainerHighest = RelevaBlueSoft,
    outline = RelevaOutline,
    outlineVariant = RelevaOutline,
    error = RelevaHangup,
    onError = RelevaOnBlue,
)

// Mockups have no dark frames. Tones below are the Material 3 dark mapping of the
// same sampled blue seed — not a second brand palette.
internal val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFADC6FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF00439A),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFADC6FF),
    onSecondary = Color(0xFF002E69),
    secondaryContainer = Color(0xFF1A2740),
    onSecondaryContainer = Color(0xFFD6E3FF),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF2C2F36),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainerLowest = Color(0xFF0D0E13),
    surfaceContainerLow = Color(0xFF1A1B21),
    surfaceContainer = Color(0xFF1E2026),
    surfaceContainerHigh = Color(0xFF1A2740),
    surfaceContainerHighest = Color(0xFF243047),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF43474E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)
