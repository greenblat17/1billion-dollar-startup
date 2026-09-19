package com.eliteteam.speakingcoach.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cmp.app.shared.generated.resources.Res
import cmp.app.shared.generated.resources.review_failed
import cmp.app.shared.generated.resources.review_loading
import cmp.app.shared.generated.resources.review_too_short
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ReviewScreen(
    sessionId: String,
    stepIndex: Int,
    onBack: () -> Unit,
    onContinue: (isLast: Boolean) -> Unit,
    viewModel: ReviewViewModel = koinViewModel(parameters = { parametersOf(sessionId) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    when (state.phase) {
        ReviewPhase.Loading -> ReviewStatus(
            loading = true,
            text = stringResource(Res.string.review_loading),
        )
        ReviewPhase.TooShort -> ReviewStatus(
            loading = false,
            text = stringResource(Res.string.review_too_short),
        )
        ReviewPhase.Failed -> ReviewStatus(
            loading = false,
            text = stringResource(Res.string.review_failed),
        )
        ReviewPhase.Ready -> {
            if (state.steps.isEmpty()) {
                ReviewStatus(
                    loading = false,
                    text = stringResource(Res.string.review_failed),
                )
                return
            }
            val safeIndex = stepIndex.coerceIn(0, state.steps.lastIndex)
            val step = state.steps[safeIndex]
            ReviewWidget(
                step = step,
                stepIndex = safeIndex,
                stepCount = state.steps.size,
                onBack = onBack,
                onContinue = { onContinue(safeIndex == state.steps.lastIndex) },
            )
        }
    }
}

@Composable
private fun ReviewStatus(
    loading: Boolean,
    text: String,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onBackground,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onBackground,
                textAlign = TextAlign.Center,
            )
        }
    }
}
