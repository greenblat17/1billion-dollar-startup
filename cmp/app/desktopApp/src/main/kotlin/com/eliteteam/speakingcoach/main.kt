package com.eliteteam.speakingcoach

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.skydoves.compose.stability.runtime.ComposeStabilityAnalyzer

fun main() {
    ComposeStabilityAnalyzer.setLogger(ChrRecompositionLogger())
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "cmp",
        ) {
            App()
        }
    }
}
