package com.eliteteam.speakingcoach

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import co.touchlab.kermit.Logger
import co.touchlab.kermit.koin.KermitKoinLogger
import co.touchlab.kermit.koin.kermitLoggerModule
import co.touchlab.kermit.loggerConfigInit
import co.touchlab.kermit.platformLogWriter
import com.eliteteam.speakingcoach.di.appModule
import com.eliteteam.speakingcoach.ui.navigation.AppNav
import com.eliteteam.speakingcoach.ui.theme.AppTheme
import com.skydoves.compose.stability.runtime.ComposeStabilityAnalyzer
import com.skydoves.compose.stability.runtime.TraceRecomposition
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration

private val appLogger = Logger(
    loggerConfigInit(platformLogWriter()),
    "SpeakingCoach",
)

@TraceRecomposition(tag = "app", traceStates = true)
@Composable
@Preview
fun App() {
    ComposeStabilityAnalyzer.setEnabled(true)
    KoinApplication(
        configuration = koinConfiguration {
            logger(KermitKoinLogger(Logger.withTag("koin")))
            modules(kermitLoggerModule(appLogger), appModule)
        },
    ) {
        AppTheme {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                AppNav()
            }
        }
    }
}
