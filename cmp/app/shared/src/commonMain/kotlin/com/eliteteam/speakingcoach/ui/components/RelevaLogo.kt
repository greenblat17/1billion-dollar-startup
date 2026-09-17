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
            val radius = size.minDimension * 0.28f
            drawCircle(
                color = primary.copy(alpha = 0.45f),
                radius = radius,
                center = Offset(size.width * 0.40f, size.height * 0.58f),
            )
            drawCircle(
                color = primary,
                radius = radius,
                center = Offset(size.width * 0.58f, size.height * 0.42f),
            )
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
