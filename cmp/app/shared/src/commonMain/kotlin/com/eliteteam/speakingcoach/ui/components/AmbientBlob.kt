package com.eliteteam.speakingcoach.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Soft circular wash from the Releva mockups: a blue-tinted core that eases
 * through the container color and fades out. Do not clip — a hard disc edge
 * kills the gradient. Last stop uses alpha 0 of the same hue so the fade
 * does not interpolate toward black.
 *
 * Dark keeps the plateau translucent so [color] does not paint a solid neon disc.
 */
@Composable
fun AmbientBlob(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primaryContainer,
    bloom: Color = MaterialTheme.colorScheme.primary,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val core = if (dark) bloom.copy(alpha = 0.16f) else bloom.copy(alpha = 0.14f)
    val plateau = if (dark) color.copy(alpha = 0.55f) else color
    val rim = if (dark) color.copy(alpha = 0.22f) else color.copy(alpha = 0.38f)
    Box(
        modifier = modifier.background(
            Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to core,
                    0.18f to plateau,
                    0.52f to plateau,
                    0.78f to rim,
                    1.00f to color.copy(alpha = 0f),
                ),
            ),
        ),
    )
}
