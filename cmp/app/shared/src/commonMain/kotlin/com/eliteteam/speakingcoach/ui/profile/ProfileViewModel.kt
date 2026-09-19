package com.eliteteam.speakingcoach.ui.profile

import androidx.lifecycle.ViewModel
import com.eliteteam.speakingcoach.ui.mock.MockSpeakingData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ProfileViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MockSpeakingData.profile)
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    fun onCaptionsToggled(enabled: Boolean) {
        _uiState.update { it.copy(captionsByDefault = enabled) }
    }
}
