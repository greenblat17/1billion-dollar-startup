package com.eliteteam.speakingcoach.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eliteteam.speakingcoach.ui.mock.DailyGoalStore
import com.eliteteam.speakingcoach.ui.mock.MockSpeakingData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class HomeViewModel(
    dailyGoalStore: DailyGoalStore,
) : ViewModel() {
    private val selectedTopic = MutableStateFlow(MockSpeakingData.home.selectedTopic)

    val uiState: StateFlow<HomeUiState> = combine(
        selectedTopic,
        dailyGoalStore.state,
    ) { topic, goal ->
        MockSpeakingData.home.copy(
            selectedTopic = topic,
            spokenSeconds = goal.spokenSeconds,
            streakDays = goal.streakDays,
            goalMinutes = goal.goalMinutes,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MockSpeakingData.home,
    )

    fun onTopicSelected(topic: TopicKind) {
        selectedTopic.update { topic }
    }
}
