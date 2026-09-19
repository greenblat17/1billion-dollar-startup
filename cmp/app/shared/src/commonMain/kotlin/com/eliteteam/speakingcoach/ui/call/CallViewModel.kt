package com.eliteteam.speakingcoach.ui.call

import androidx.lifecycle.ViewModel
import com.eliteteam.speakingcoach.ui.mock.MockSpeakingData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class CallViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MockSpeakingData.call)
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    fun onMicToggled() {
        _uiState.update { it.copy(micMuted = !it.micMuted) }
    }

    fun onCaptionsToggled() {
        _uiState.update { it.copy(captionsOn = !it.captionsOn) }
    }
}
