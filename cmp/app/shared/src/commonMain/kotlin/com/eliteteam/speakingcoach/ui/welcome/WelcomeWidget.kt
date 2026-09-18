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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cmp.app.shared.generated.resources.Res
import cmp.app.shared.generated.resources.welcome_have_account
import cmp.app.shared.generated.resources.welcome_headline
import cmp.app.shared.generated.resources.welcome_headline_accent
import cmp.app.shared.generated.resources.welcome_legal
import cmp.app.shared.generated.resources.welcome_privacy
import cmp.app.shared.generated.resources.welcome_start
import cmp.app.shared.generated.resources.welcome_terms
import com.eliteteam.speakingcoach.ui.components.AmbientBlob
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
    onTerms: () -> Unit = {},
    onPrivacy: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        AmbientBlob(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 96.dp, y = (-64).dp)
                .size(420.dp),
        )
        AmbientBlob(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 20.dp, y = 48.dp)
                .size(260.dp),
            color = scheme.secondaryContainer,
        )
        AmbientBlob(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-160).dp, y = 40.dp)
                .size(460.dp),
        )
        AmbientBlob(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-40).dp, y = (-48).dp)
                .size(240.dp),
            color = scheme.secondaryContainer,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))
            RelevaLogo()
            Spacer(Modifier.height(80.dp))
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
                    text = welcomeLegalText(onTerms, onPrivacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun welcomeLegalText(
    onTerms: () -> Unit,
    onPrivacy: () -> Unit,
) = buildAnnotatedString {
    val terms = stringResource(Res.string.welcome_terms)
    val privacy = stringResource(Res.string.welcome_privacy)
    val full = stringResource(Res.string.welcome_legal, terms, privacy)
    val termsStart = full.indexOf(terms)
    val privacyStart = full.indexOf(privacy)
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textDecoration = TextDecoration.Underline,
        ),
    )
    var cursor = 0
    if (termsStart >= 0) {
        append(full.substring(cursor, termsStart))
        withLink(
            LinkAnnotation.Clickable(
                tag = "terms",
                styles = linkStyles,
                linkInteractionListener = { onTerms() },
            ),
        ) {
            append(terms)
        }
        cursor = termsStart + terms.length
    }
    if (privacyStart >= cursor) {
        append(full.substring(cursor, privacyStart))
        withLink(
            LinkAnnotation.Clickable(
                tag = "privacy",
                styles = linkStyles,
                linkInteractionListener = { onPrivacy() },
            ),
        ) {
            append(privacy)
        }
        cursor = privacyStart + privacy.length
    }
    if (cursor < full.length) {
        append(full.substring(cursor))
    }
}

@Preview
@Composable
private fun WelcomeWidgetPreview() {
    AppTheme {
        WelcomeWidget(onStart = {}, onHaveAccount = {})
    }
}
