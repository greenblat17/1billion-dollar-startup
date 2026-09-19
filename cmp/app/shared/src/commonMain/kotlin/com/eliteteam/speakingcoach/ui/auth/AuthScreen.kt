package com.eliteteam.speakingcoach.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AuthScreen(
    register: Boolean,
    onBack: () -> Unit,
    viewModel: AuthViewModel = koinViewModel(parameters = { parametersOf(register) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AuthWidget(
        state = state,
        onEmailChanged = viewModel::onEmailChanged,
        onPasswordChanged = viewModel::onPasswordChanged,
        onDisplayNameChanged = viewModel::onDisplayNameChanged,
        onSubmit = viewModel::onSubmit,
        onBack = onBack,
    )
}
