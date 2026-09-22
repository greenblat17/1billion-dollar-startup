package com.eliteteam.speakingcoach.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.eliteteam.speakingcoach.data.ReviewPoll
import com.eliteteam.speakingcoach.data.ReviewStepDto
import com.eliteteam.speakingcoach.data.SpeakingCoachClient
import com.eliteteam.speakingcoach.ui.mock.MockSpeakingData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ReviewViewModel(
    private val sessionId: String,
    private val client: SpeakingCoachClient,
    private val logger: Logger,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        if (sessionId.isBlank()) {
            MockSpeakingData.review
        } else {
            ReviewUiState(phase = ReviewPhase.Loading)
        },
    )
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    init {
        if (sessionId.isNotBlank()) {
            logger.i { "review poll sessionId=$sessionId" }
            viewModelScope.launch { poll() }
        } else {
            logger.i { "mock review steps=${_uiState.value.steps.size}" }
        }
    }

    private suspend fun poll() {
        repeat(100) {
            when (val result = client.pollReview(sessionId)) {
                is ReviewPoll.Ready -> {
                    val steps = result.steps.mapNotNull { it.toUi() }
                    if (steps.size < 2) {
                        logger.w { "review payload steps=${steps.size}" }
                        _uiState.value = ReviewUiState(phase = ReviewPhase.Failed)
                    } else {
                        _uiState.value = ReviewUiState(phase = ReviewPhase.Ready, steps = steps)
                    }
                    return
                }
                ReviewPoll.Pending -> delay(300)
                ReviewPoll.TooShort -> {
                    _uiState.value = ReviewUiState(phase = ReviewPhase.TooShort)
                    return
                }
                ReviewPoll.Failed -> {
                    _uiState.value = ReviewUiState(phase = ReviewPhase.Failed)
                    return
                }
            }
        }
        logger.w { "review poll timeout sessionId=$sessionId" }
        _uiState.value = ReviewUiState(phase = ReviewPhase.Failed)
    }
}

internal fun ReviewStepDto.toUi(): ReviewStep? {
    val mapped = when (metric) {
        "Grammar" -> ReviewMetric.Grammar
        "Vocabulary" -> ReviewMetric.Vocabulary
        else -> return null
    }
    return ReviewStep(
        metric = mapped,
        score = score,
        lead = lead,
        bullets = bullets,
        examples = ReviewExamples(
            items = examples.map { ExamplePair(it.original, it.improved) },
        ),
        tip = tip,
    )
}
