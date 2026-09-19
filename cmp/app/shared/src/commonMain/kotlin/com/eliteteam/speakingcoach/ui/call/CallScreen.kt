package com.eliteteam.speakingcoach.ui.call

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun CallScreen(
    sessionId: String,
    onHangup: () -> Unit,
    viewModel: CallViewModel = koinViewModel(parameters = { parametersOf(sessionId) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CallWidget(
        state = state,
        onHangup = {
            viewModel.onHangup()
            onHangup()
        },
        onMicToggle = viewModel::onMicToggled,
        onCaptionsToggle = viewModel::onCaptionsToggled,
    )
}
