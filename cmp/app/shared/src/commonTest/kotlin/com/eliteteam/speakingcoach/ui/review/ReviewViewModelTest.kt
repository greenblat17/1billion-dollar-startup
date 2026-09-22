package com.eliteteam.speakingcoach.ui.review

import com.eliteteam.speakingcoach.data.ReviewExampleDto
import com.eliteteam.speakingcoach.data.ReviewPoll
import com.eliteteam.speakingcoach.data.ReviewStepDto
import com.eliteteam.speakingcoach.data.SpeakingCoachClient
import com.eliteteam.speakingcoach.data.TranscriptTurn
import com.eliteteam.speakingcoach.testLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun blankSessionKeepsMock() = runTest(dispatcher) {
        val vm = ReviewViewModel("", FakeReviewClient(emptyList()), testLogger())
        assertEquals(ReviewPhase.Ready, vm.uiState.value.phase)
        assertEquals(2, vm.uiState.value.steps.size)
    }

    @Test
    fun pollsUntilReady() = runTest(dispatcher) {
        val client = FakeReviewClient(
            listOf(
                ReviewPoll.Pending,
                ReviewPoll.Ready(twoSteps()),
            ),
        )
        val vm = ReviewViewModel("app-1", client, testLogger())
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        assertEquals(ReviewPhase.Ready, vm.uiState.value.phase)
        assertEquals(ReviewMetric.Grammar, vm.uiState.value.steps.first().metric)
        assertEquals(2, client.polls)
    }

    @Test
    fun tooShortStopsPolling() = runTest(dispatcher) {
        val vm = ReviewViewModel("app-1", FakeReviewClient(listOf(ReviewPoll.TooShort)), testLogger())
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()
        assertEquals(ReviewPhase.TooShort, vm.uiState.value.phase)
    }
}

private fun twoSteps() = listOf(
    ReviewStepDto(
        metric = "Grammar",
        score = 78,
        lead = "lead",
        bullets = listOf("Past Simple"),
        examples = listOf(ReviewExampleDto("a", "b")),
        tip = "tip",
    ),
    ReviewStepDto(
        metric = "Vocabulary",
        score = 84,
        lead = "lead",
        bullets = emptyList(),
        examples = emptyList(),
        tip = "tip",
    ),
)

private class FakeReviewClient(
    private val results: List<ReviewPoll>,
) : SpeakingCoachClient {
    var polls = 0

    override suspend fun register(email: String, password: String, displayName: String) = unused()
    override suspend fun login(email: String, password: String) = unused()
    override suspend fun logout() = Unit
    override suspend fun loadHome() = unused()
    override suspend fun createSession(topic: String, tutorVoice: String) = unused()
    override suspend fun startRtc(sessionId: String, sdpOffer: String) = unused()
    override suspend fun completeSession(
        sessionId: String,
        turns: List<TranscriptTurn>,
        durationSec: Int,
    ) = Unit

    override suspend fun pollReview(sessionId: String): ReviewPoll {
        val index = polls.coerceAtMost(results.lastIndex)
        polls += 1
        return results[index]
    }

    private fun unused(): Nothing = error("unused")
}