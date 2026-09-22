package com.eliteteam.speakingcoach.ui.call

import com.eliteteam.speakingcoach.data.ApiException
import com.eliteteam.speakingcoach.data.IceFailedException
import com.eliteteam.speakingcoach.data.RealtimeCall
import com.eliteteam.speakingcoach.data.ReviewPoll
import com.eliteteam.speakingcoach.data.SpeakingCoachClient
import com.eliteteam.speakingcoach.data.TranscriptTurn
import com.eliteteam.speakingcoach.testLogger
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class CallViewModelTest {
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
    fun connectsThenShowsCaption() = runTest(dispatcher) {
        val call = FakeRealtimeCall()
        val client = FakeCallClient()
        val vm = CallViewModel("app-1", client, testLogger()) { call }
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()
        assertEquals("v=0-offer", client.lastOffer)
        assertEquals("v=0-answer", call.remoteAnswer)
        assertEquals(false, vm.uiState.value.connecting)
        assertNull(vm.uiState.value.error)
        call.pushCaption("Hello there")
        runCurrent()
        assertEquals("Hello there", vm.uiState.value.caption)
        vm.hangup { }
        runCurrent()
    }

    @Test
    fun mapsRtcConflict() = runTest(dispatcher) {
        val client = FakeCallClient(rtcError = ApiException(HttpStatusCode.Conflict))
        val vm = CallViewModel("app-1", client, testLogger()) { FakeRealtimeCall() }
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()
        assertEquals(CallError.Conflict, vm.uiState.value.error)
        assertEquals(false, vm.uiState.value.connecting)
    }

    @Test
    fun mapsIceFailedToNetwork() = runTest(dispatcher) {
        val call = FakeRealtimeCall(iceError = IceFailedException("Failed"))
        val vm = CallViewModel("app-1", FakeCallClient(), testLogger()) { call }
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()
        assertEquals(CallError.Network, vm.uiState.value.error)
        assertEquals(false, vm.uiState.value.connecting)
        assertEquals(true, call.closed)
    }

    @Test
    fun hangupCompletesAndReturnsReview() = runTest(dispatcher) {
        val call = FakeRealtimeCall()
        val client = FakeCallClient()
        val vm = CallViewModel("app-1", client, testLogger()) { call }
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()
        var outcome: HangupOutcome? = null
        vm.hangup { outcome = it }
        runCurrent()
        assertEquals(HangupOutcome.Review, outcome)
        assertEquals("app-1", client.completedSessionId)
        assertEquals(listOf(TranscriptTurn("user", "hi")), client.completedTurns)
        assertEquals(true, call.closed)
    }

    @Test
    fun hangupTooShortStaysOffReview() = runTest(dispatcher) {
        val client = FakeCallClient(completeError = ApiException(HttpStatusCode.UnprocessableEntity))
        val vm = CallViewModel("app-1", client, testLogger()) { FakeRealtimeCall() }
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()
        var outcome: HangupOutcome? = null
        vm.hangup { outcome = it }
        runCurrent()
        assertEquals(HangupOutcome.TooShort, outcome)
    }

    @Test
    fun formatElapsedPadsMinutesAndSeconds() {
        assertEquals("00:00", formatElapsed(0))
        assertEquals("03:24", formatElapsed(204))
    }
}

private class FakeRealtimeCall(
    private val iceError: Throwable? = null,
) : RealtimeCall {
    var remoteAnswer: String? = null
    var closed = false
    private val _captions = MutableSharedFlow<String>(extraBufferCapacity = 8)

    override val captions = _captions.asSharedFlow()

    override suspend fun createOffer(): String = "v=0-offer"

    override suspend fun setRemoteAnswer(sdp: String) {
        iceError?.let { throw it }
        remoteAnswer = sdp
    }

    override fun setMuted(muted: Boolean) = Unit

    override fun snapshotTurns() = listOf(TranscriptTurn("user", "hi"))

    override fun close() {
        closed = true
    }

    suspend fun pushCaption(text: String) {
        _captions.emit(text)
    }
}

private class FakeCallClient(
    private val rtcError: ApiException? = null,
    private val completeError: ApiException? = null,
    val answer: String = "v=0-answer",
) : SpeakingCoachClient {
    var lastOffer: String? = null
    var completedSessionId: String? = null
    var completedTurns: List<TranscriptTurn> = emptyList()

    override suspend fun register(email: String, password: String, displayName: String) = unused()
    override suspend fun login(email: String, password: String) = unused()
    override suspend fun logout() = Unit
    override suspend fun loadHome() = unused()
    override suspend fun createSession(topic: String, tutorVoice: String) = unused()

    override suspend fun startRtc(sessionId: String, sdpOffer: String): String {
        lastOffer = sdpOffer
        rtcError?.let { throw it }
        return answer
    }

    override suspend fun completeSession(
        sessionId: String,
        turns: List<TranscriptTurn>,
        durationSec: Int,
    ) {
        completeError?.let { throw it }
        completedSessionId = sessionId
        completedTurns = turns
    }

    override suspend fun pollReview(sessionId: String) = ReviewPoll.Failed

    private fun unused(): Nothing = error("unused")
}