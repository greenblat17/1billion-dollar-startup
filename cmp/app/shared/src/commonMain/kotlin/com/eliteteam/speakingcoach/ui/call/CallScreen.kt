package com.eliteteam.speakingcoach.ui.call

import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun CallScreen(
    onHangup: () -> Unit,
    viewModel: CallViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CallWidget(
        state = state,
        onHangup = onHangup,
        onMicToggle = viewModel::onMicToggled,
        onCaptionsToggle = viewModel::onCaptionsToggled,
        modifier = Modifier.safeContentPadding(),
    )
}
