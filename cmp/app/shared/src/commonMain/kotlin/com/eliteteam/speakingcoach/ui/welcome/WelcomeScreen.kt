package com.eliteteam.speakingcoach.ui.welcome

import androidx.compose.runtime.Composable

@Composable
fun WelcomeScreen(
    onStart: () -> Unit,
    onHaveAccount: () -> Unit,
) {
    WelcomeWidget(
        onStart = onStart,
        onHaveAccount = onHaveAccount,
    )
}
