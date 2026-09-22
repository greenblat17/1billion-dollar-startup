package com.eliteteam.speakingcoach.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ProfileWidget(
        state = state,
        onCaptionsToggled = viewModel::onCaptionsToggled,
        onDailyGoalCycled = viewModel::onDailyGoalCycled,
        onTutorVoiceCycled = viewModel::onTutorVoiceCycled,
        onSignOut = viewModel::signOut,
    )
}
