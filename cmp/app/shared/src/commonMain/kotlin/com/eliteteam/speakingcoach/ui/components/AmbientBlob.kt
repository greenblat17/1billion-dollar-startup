package com.eliteteam.speakingcoach.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Soft circular wash from the Releva mockups: a blue-tinted core that eases
 * through the container color and fades out. Do not clip — a hard disc edge
 * kills the gradient. Last stop uses alpha 0 of the same hue so the fade
 * does not interpolate toward black.
 */
@Composable
fun AmbientBlob(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primaryContainer,
    bloom: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier.background(
            Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to bloom.copy(alpha = 0.14f),
                    0.18f to color,
                    0.52f to color,
                    0.78f to color.copy(alpha = 0.38f),
                    1.00f to color.copy(alpha = 0f),
                ),
            ),
        ),
    )
}
