package com.eliteteam.speakingcoach.ui.call

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cmp.app.shared.generated.resources.Res
import cmp.app.shared.generated.resources.call_captions
import cmp.app.shared.generated.resources.call_hangup
import cmp.app.shared.generated.resources.call_mic
import cmp.app.shared.generated.resources.cd_captions
import cmp.app.shared.generated.resources.cd_hangup
import cmp.app.shared.generated.resources.cd_mic
import cmp.app.shared.generated.resources.ic_call_end
import cmp.app.shared.generated.resources.ic_closed_caption
import cmp.app.shared.generated.resources.ic_mic
import cmp.app.shared.generated.resources.ic_mic_off
import com.eliteteam.speakingcoach.ui.components.AmbientBlob
import com.eliteteam.speakingcoach.ui.components.RelevaLogo
import com.eliteteam.speakingcoach.ui.mock.MockSpeakingData
import com.eliteteam.speakingcoach.ui.theme.AppTheme
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun CallWidget(
    state: CallUiState,
    onHangup: () -> Unit,
    onMicToggle: () -> Unit,
    onCaptionsToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))
        Box(contentAlignment = Alignment.Center) {
            AmbientBlob(modifier = Modifier.size(168.dp))
            SoftRing(140.dp, scheme.primaryContainer)
            SoftRing(200.dp, scheme.primaryContainer)
            SoftRing(268.dp, scheme.primaryContainer)
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(scheme.surfaceContainerLowest),
                contentAlignment = Alignment.Center,
            ) {
                RelevaLogo(showWordmark = false, markSize = 48.dp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = state.elapsed,
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (state.captionsOn) {
            Text(
                text = state.caption,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top,
        ) {
            CallAction(
                icon = if (state.micMuted) Res.drawable.ic_mic_off else Res.drawable.ic_mic,
                label = stringResource(Res.string.call_mic),
                contentDescription = stringResource(Res.string.cd_mic),
                onClick = onMicToggle,
                filled = false,
            )
            CallAction(
                icon = Res.drawable.ic_call_end,
                label = stringResource(Res.string.call_hangup),
                contentDescription = stringResource(Res.string.cd_hangup),
                onClick = onHangup,
                filled = true,
            )
            CallAction(
                icon = Res.drawable.ic_closed_caption,
                label = stringResource(Res.string.call_captions),
                contentDescription = stringResource(Res.string.cd_captions),
                onClick = onCaptionsToggle,
                filled = false,
                emphasized = state.captionsOn,
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SoftRing(size: Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val stroke = 1.75.dp.toPx()
        drawCircle(
            color = color.copy(alpha = 0.7f),
            radius = size.toPx() / 2f - stroke / 2f,
            style = Stroke(width = stroke),
        )
    }
}

@Composable
private fun CallAction(
    icon: DrawableResource,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    filled: Boolean,
    emphasized: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (filled) scheme.error else scheme.surfaceContainerLowest
    val tint = when {
        filled -> scheme.onError
        emphasized -> scheme.primary
        else -> scheme.onBackground
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(container)
                .then(
                    if (filled) {
                        Modifier
                    } else {
                        Modifier.border(1.dp, scheme.outline, CircleShape)
                    },
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}

@Preview
@Composable
private fun CallWidgetPreview() {
    AppTheme {
        CallWidget(
            state = MockSpeakingData.call,
            onHangup = {},
            onMicToggle = {},
            onCaptionsToggle = {},
        )
    }
}
