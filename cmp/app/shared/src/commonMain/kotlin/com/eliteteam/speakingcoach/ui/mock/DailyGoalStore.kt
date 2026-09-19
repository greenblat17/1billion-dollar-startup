package com.eliteteam.speakingcoach.ui.mock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DailyGoalState(
    val spokenSeconds: Int = 2,
    val streakDays: Int = 1,
    val goalMinutes: Int = 10,
)

class DailyGoalStore {
    private val _state = MutableStateFlow(DailyGoalState())
    val state: StateFlow<DailyGoalState> = _state.asStateFlow()

    fun cycleGoalMinutes() {
        _state.update { current ->
            val index = GoalPresets.indexOf(current.goalMinutes).let { if (it < 0) 0 else it }
            current.copy(goalMinutes = GoalPresets[(index + 1) % GoalPresets.size])
        }
    }

    companion object {
        val GoalPresets = listOf(5, 10, 15, 20)
    }
}
