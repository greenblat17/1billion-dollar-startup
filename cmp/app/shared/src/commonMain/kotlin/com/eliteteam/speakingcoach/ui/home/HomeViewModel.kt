package com.eliteteam.speakingcoach.ui.home

import androidx.lifecycle.ViewModel
import com.eliteteam.speakingcoach.ui.mock.MockSpeakingData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class HomeViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MockSpeakingData.home)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun onTopicSelected(topic: TopicKind) {
        _uiState.update { it.copy(selectedTopic = topic) }
    }
}
