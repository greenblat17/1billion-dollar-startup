package com.eliteteam.speakingcoach

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.skydoves.compose.stability.runtime.ComposeStabilityAnalyzer

/** `true` only while debugging one widget in [SandboxHost]. Default is the real [App]. */
private const val runSandbox = false

fun main() {
    installJvmKermitWriters(ChrKermitLogWriter())
    ComposeStabilityAnalyzer.setLogger(ChrRecompositionLogger())
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "cmp",
        ) {
            if (runSandbox) SandboxHost() else App()
        }
    }
}
