package com.eliteteam.speakingcoach.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ReviewScreen(
    stepIndex: Int,
    onBack: () -> Unit,
    onContinue: (isLast: Boolean) -> Unit,
    viewModel: ReviewViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
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
