package com.eliteteam.speakingcoach

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Isolated host for one shared widget plus mocks. Same `:app:desktopApp` / MCP —
 * not a second Gradle target. Flip `runSandbox` in `main.kt` while debugging.
 *
 * Put the widget under [SandboxContent] with mocks. When done, restore the stub.
 * Do not copy the widget into this file; it lives in `:app:shared`.
 *
 * Theme chrome stays here so MCP can click `Theme: light` / `Theme: dark`.
 * Widgets must use [MaterialTheme.colorScheme], not hardcoded colors.
 */
@Composable
fun SandboxHost() {
    var dark by remember { mutableStateOf(false) }
    val colorScheme = if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colorScheme) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.background)
                .safeContentPadding(),
        ) {
            Button(onClick = { dark = !dark }) {
                Text(if (dark) "Theme: dark" else "Theme: light")
            }
            SandboxContent()
        }
    }
}

@Composable
private fun SandboxContent() {
    Text(
        "Sandbox empty",
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(16.dp),
    )
}
