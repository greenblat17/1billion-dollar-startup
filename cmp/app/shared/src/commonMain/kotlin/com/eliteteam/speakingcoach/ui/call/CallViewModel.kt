package com.eliteteam.speakingcoach.ui.call

import androidx.lifecycle.ViewModel
import co.touchlab.kermit.Logger
import com.eliteteam.speakingcoach.ui.mock.MockSpeakingData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class CallViewModel(
    private val sessionId: String,
    private val logger: Logger,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MockSpeakingData.call)
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    init {
        logger.i { "mock call sessionId=$sessionId" }
    }

    fun onMicToggled() {
        _uiState.update { it.copy(micMuted = !it.micMuted) }
        logger.i { "mic muted=${_uiState.value.micMuted}" }
    }

    fun onCaptionsToggled() {
        _uiState.update { it.copy(captionsOn = !it.captionsOn) }
        logger.i { "captions=${_uiState.value.captionsOn}" }
    }

    fun onHangup() {
        logger.i { "mock hangup sessionId=$sessionId" }
    }
}
