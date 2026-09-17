package com.eliteteam.speakingcoach.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cmp.app.shared.generated.resources.Res
import cmp.app.shared.generated.resources.welcome_have_account
import cmp.app.shared.generated.resources.welcome_headline
import cmp.app.shared.generated.resources.welcome_headline_accent
import cmp.app.shared.generated.resources.welcome_legal
import cmp.app.shared.generated.resources.welcome_start
import com.eliteteam.speakingcoach.ui.components.PrimaryButton
import com.eliteteam.speakingcoach.ui.components.RelevaLogo
import com.eliteteam.speakingcoach.ui.components.SecondaryButton
import com.eliteteam.speakingcoach.ui.theme.AppTheme
import org.jetbrains.compose.resources.stringResource

@Composable
fun WelcomeWidget(
    onStart: () -> Unit,
    onHaveAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 40.dp, y = 80.dp)
                .size(220.dp)
                .clip(CircleShape)
                .background(scheme.primary.copy(alpha = 0.08f)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-60).dp, y = (-180).dp)
                .size(200.dp)
                .clip(CircleShape)
                .background(scheme.primary.copy(alpha = 0.06f)),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))
            RelevaLogo()
            Spacer(Modifier.height(72.dp))
            Text(
                text = stringResource(Res.string.welcome_headline),
                style = MaterialTheme.typography.headlineLarge,
                color = scheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(Res.string.welcome_headline_accent),
                style = MaterialTheme.typography.headlineLarge,
                color = scheme.primary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PrimaryButton(
                    text = stringResource(Res.string.welcome_start),
                    onClick = onStart,
                    trailingArrow = true,
                )
                SecondaryButton(
                    text = stringResource(Res.string.welcome_have_account),
                    onClick = onHaveAccount,
                )
                Text(
                    text = stringResource(Res.string.welcome_legal),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Preview
@Composable
private fun WelcomeWidgetPreview() {
    AppTheme {
        WelcomeWidget(onStart = {}, onHaveAccount = {})
    }
}
