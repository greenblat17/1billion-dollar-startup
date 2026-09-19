package com.eliteteam.speakingcoach.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Median / cluster samples from ai_docs/design/screens/*.png (2026-09-17 Releva mockups).
private val RelevaBlue = Color(0xFF1874FC)
private val RelevaOnBlue = Color(0xFFFFFFFF)
private val RelevaBlueSoft = Color(0xFFE9EFFD)
private val RelevaBlueMist = Color(0xFFECF0FC)
private val RelevaBlueWash = Color(0xFFF0F4FC)
private val RelevaCream = Color(0xFFFBFAF6)
private val RelevaInk = Color(0xFF0E1020)
private val RelevaMuted = Color(0xFF9B9EA4)
private val RelevaCard = Color(0xFFFCFCFC)
private val RelevaOutline = Color(0xFFE4E4E4)
private val RelevaHangup = Color(0xFFFC3434)
private val RelevaFlame = Color(0xFFE85D04)
private val RelevaIce = Color(0xFFFFFFFF)
private val RelevaIceLine = Color(0xFFDFE2E8)

internal val LightColorScheme = lightColorScheme(
    primary = RelevaBlue,
    onPrimary = RelevaOnBlue,
    primaryContainer = RelevaBlueMist,
    onPrimaryContainer = RelevaInk,
    secondary = RelevaBlue,
    onSecondary = RelevaOnBlue,
    secondaryContainer = RelevaBlueSoft,
    onSecondaryContainer = RelevaInk,
    tertiary = RelevaFlame,
    onTertiary = RelevaOnBlue,
    background = RelevaCream,
    onBackground = RelevaInk,
    surface = RelevaCream,
    onSurface = RelevaInk,
    surfaceVariant = RelevaCard,
    onSurfaceVariant = RelevaMuted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = RelevaCard,
    surfaceContainer = RelevaCard,
    surfaceContainerHigh = RelevaBlueWash,
    surfaceContainerHighest = RelevaIce,
    outline = RelevaOutline,
    outlineVariant = RelevaIceLine,
    error = RelevaHangup,
    onError = RelevaOnBlue,
)

// Mockups have no dark frames. Same roles as light: brand blue CTA, cards
// lighter than the ink background, quiet navy washes — not M3 pastel invert.
private val RelevaNight = Color(0xFF0E1020)
private val RelevaNightCard = Color(0xFF1C2030)
private val RelevaNightMist = Color(0xFF1B2740)
private val RelevaNightSoft = Color(0xFF243352)
private val RelevaNightWash = Color(0xFF2A3148)
private val RelevaNightLine = Color(0xFF3D4458)
private val RelevaNightText = Color(0xFFF2F3F7)
private val RelevaNightMuted = Color(0xFF9AA0B0)
private val RelevaNightFlame = Color(0xFFFF8A4C)

internal val DarkColorScheme = darkColorScheme(
    primary = RelevaBlue,
    onPrimary = RelevaOnBlue,
    primaryContainer = RelevaNightMist,
    onPrimaryContainer = RelevaNightText,
    secondary = RelevaBlue,
    onSecondary = RelevaOnBlue,
    secondaryContainer = RelevaNightSoft,
    onSecondaryContainer = RelevaNightText,
    tertiary = RelevaNightFlame,
    onTertiary = RelevaNight,
    background = RelevaNight,
    onBackground = RelevaNightText,
    surface = RelevaNight,
    onSurface = RelevaNightText,
    surfaceVariant = RelevaNightCard,
    onSurfaceVariant = RelevaNightMuted,
    surfaceContainerLowest = RelevaNightCard,
    surfaceContainerLow = RelevaNightCard,
    surfaceContainer = RelevaNightCard,
    surfaceContainerHigh = RelevaNightWash,
    surfaceContainerHighest = RelevaNightMist,
    outline = RelevaNightLine,
    outlineVariant = RelevaNightLine,
    error = RelevaHangup,
    onError = RelevaOnBlue,
)
