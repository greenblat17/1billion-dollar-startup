package com.eliteteam.speakingcoach.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cmp.app.shared.generated.resources.Res
import cmp.app.shared.generated.resources.brand_name
import org.jetbrains.compose.resources.stringResource

@Composable
fun RelevaLogo(
    modifier: Modifier = Modifier,
    showWordmark: Boolean = true,
    markSize: Dp = 36.dp,
    wordmarkColor: Color = MaterialTheme.colorScheme.onBackground,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val primary = MaterialTheme.colorScheme.primary
        Canvas(Modifier.size(markSize)) {
            val s = size.minDimension
            val petal = teardropPath(s * 0.72f)
            translate(s * 0.04f, s * 0.10f) {
                rotate(-38f, pivot = Offset(s * 0.36f, s * 0.52f)) {
                    drawPath(petal, color = primary.copy(alpha = 0.42f))
                }
            }
            translate(s * 0.18f, s * -0.02f) {
                rotate(28f, pivot = Offset(s * 0.42f, s * 0.40f)) {
                    drawPath(petal, color = primary)
                }
            }
        }
        if (showWordmark) {
            Text(
                text = stringResource(Res.string.brand_name),
                style = MaterialTheme.typography.titleLarge,
                color = wordmarkColor,
            )
        }
    }
}

private fun teardropPath(size: Float): Path {
    val path = Path()
    path.moveTo(size * 0.50f, size * 0.96f)
    path.cubicTo(
        size * 0.06f, size * 0.72f,
        size * 0.10f, size * 0.22f,
        size * 0.50f, size * 0.04f,
    )
    path.cubicTo(
        size * 0.90f, size * 0.22f,
        size * 0.94f, size * 0.72f,
        size * 0.50f, size * 0.96f,
    )
    path.close()
    return path
}
