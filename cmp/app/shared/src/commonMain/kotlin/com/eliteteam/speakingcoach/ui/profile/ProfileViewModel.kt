package com.eliteteam.speakingcoach.ui.profile

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

class ProfileViewModel(
    private val dailyGoalStore: DailyGoalStore,
) : ViewModel() {
    private val captions = MutableStateFlow(MockSpeakingData.profile.captionsByDefault)

    val uiState: StateFlow<ProfileUiState> = combine(
        captions,
        dailyGoalStore.state,
    ) { captionsOn, goal ->
        MockSpeakingData.profile.copy(
            captionsByDefault = captionsOn,
            goalMinutes = goal.goalMinutes,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MockSpeakingData.profile,
    )

    fun onCaptionsToggled(enabled: Boolean) {
        captions.update { enabled }
    }

    fun onDailyGoalCycled() {
        dailyGoalStore.cycleGoalMinutes()
    }
}
