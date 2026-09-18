package com.eliteteam.speakingcoach.ui.home

import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun HomeScreen(
    onStart: () -> Unit,
    onProfile: () -> Unit,
    onLastConversation: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeWidget(
        state = state,
        onStart = onStart,
        onTopicSelected = viewModel::onTopicSelected,
        onProfile = onProfile,
        onLastConversation = onLastConversation,
        modifier = modifier.safeContentPadding(),
    )
}
