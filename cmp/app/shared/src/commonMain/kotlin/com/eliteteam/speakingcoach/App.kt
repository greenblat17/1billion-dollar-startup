package com.eliteteam.speakingcoach

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.skydoves.compose.stability.runtime.ComposeStabilityAnalyzer
import com.skydoves.compose.stability.runtime.TraceRecomposition
import org.jetbrains.compose.resources.painterResource

import cmp.app.shared.generated.resources.Res
import cmp.app.shared.generated.resources.compose_multiplatform

@TraceRecomposition(tag = "sandbox", traceStates = true)
@Composable
@Preview
fun App() {
    ComposeStabilityAnalyzer.setEnabled(true)
    // Must be state of App itself. Nested in MaterialTheme's content lambda, App is
    // skipped on click and @TraceRecomposition never logs `[state] showContent`.
    var showContent by remember { mutableStateOf(false) }
    MaterialTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer)
                .safeContentPadding()
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(onClick = { showContent = !showContent }) {
                Text("Click me!")
            }
            AnimatedVisibility(showContent) {
                val greeting = remember { Greeting().greet() }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(painterResource(Res.drawable.compose_multiplatform), null)
                    Text("Compose: $greeting")
                }
            }
        }
    }
}