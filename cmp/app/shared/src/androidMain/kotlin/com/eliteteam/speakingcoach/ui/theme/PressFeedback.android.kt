package com.eliteteam.speakingcoach.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@Composable
internal actual fun ProvidePressFeedback(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalIndication provides ripple(),
        content = content,
    )
}
