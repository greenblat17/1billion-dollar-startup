package com.eliteteam.speakingcoach.ui.welcome

import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun WelcomeScreen(
    onStart: () -> Unit,
    onHaveAccount: () -> Unit,
) {
    WelcomeWidget(
        onStart = onStart,
        onHaveAccount = onHaveAccount,
        modifier = Modifier.safeContentPadding(),
    )
}
