package com.eliteteam.speakingcoach.ui.history

import androidx.lifecycle.ViewModel
import com.eliteteam.speakingcoach.ui.mock.MockSpeakingData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HistoryViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MockSpeakingData.history)
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()
}
