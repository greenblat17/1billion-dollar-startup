package com.eliteteam.speakingcoach.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: ProfileViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ProfileWidget(
        state = state,
        onBack = onBack,
        onCaptionsToggled = viewModel::onCaptionsToggled,
        onSignOut = onSignOut,
    )
}
