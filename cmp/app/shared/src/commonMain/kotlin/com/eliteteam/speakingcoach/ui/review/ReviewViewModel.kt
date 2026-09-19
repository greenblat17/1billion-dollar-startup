package com.eliteteam.speakingcoach.ui.review

import androidx.lifecycle.ViewModel
import co.touchlab.kermit.Logger
import com.eliteteam.speakingcoach.ui.mock.MockSpeakingData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ReviewViewModel(
    logger: Logger,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MockSpeakingData.review)
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    init {
        logger.i { "mock review steps=${_uiState.value.steps.size}" }
    }
}
