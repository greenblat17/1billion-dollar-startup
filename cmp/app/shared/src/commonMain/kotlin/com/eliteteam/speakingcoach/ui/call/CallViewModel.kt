package com.eliteteam.speakingcoach.ui.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.eliteteam.speakingcoach.data.ApiException
import com.eliteteam.speakingcoach.data.RealtimeCall
import com.eliteteam.speakingcoach.data.SpeakingCoachClient
import com.eliteteam.speakingcoach.data.createRealtimeCall
import com.eliteteam.speakingcoach.ui.mock.MockSpeakingData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class CallError {
    Mic,
    Conflict,
    Network,
}

enum class HangupOutcome {
    Review,
    TooShort,
    Failed,
    Stay,
}

class CallViewModel(
    private val sessionId: String,
    private val client: SpeakingCoachClient,
    private val logger: Logger,
    private val callFactory: () -> RealtimeCall = { createRealtimeCall() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        MockSpeakingData.call.copy(elapsed = "00:00", caption = "", captionsOn = true),
    )
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private var call: RealtimeCall? = null
    private var timer: Job? = null
    private var captionsJob: Job? = null
    private var elapsedSec = 0
    private var hangupStarted = false

    init {
        logger.i { "call start sessionId=$sessionId" }
        viewModelScope.launch { connect() }
    }

    fun onMicToggled() {
        val muted = !_uiState.value.micMuted
        call?.setMuted(muted)
        _uiState.update { it.copy(micMuted = muted) }
        logger.i { "mic muted=$muted" }
    }

    fun onCaptionsToggled() {
        _uiState.update { it.copy(captionsOn = !it.captionsOn) }
        logger.i { "captions=${_uiState.value.captionsOn}" }
    }

    fun hangup(onDone: (HangupOutcome) -> Unit) {
        if (hangupStarted) return
        hangupStarted = true
        timer?.cancel()
        captionsJob?.cancel()
        viewModelScope.launch {
            val outcome = completeCall()
            logger.i { "hangup sessionId=$sessionId outcome=$outcome" }
            onDone(outcome)
        }
    }

    override fun onCleared() {
        timer?.cancel()
        captionsJob?.cancel()
        if (!hangupStarted) {
            hangupStarted = true
            val session = call
            call = null
            val durationSec = elapsedSec
            val turns = session?.snapshotTurns().orEmpty()
            runCatching { session?.close() }
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                runCatching { client.completeSession(sessionId, turns, durationSec) }
            }
        }
        super.onCleared()
    }

    private suspend fun connect() {
        _uiState.update { it.copy(connecting = true, error = null) }
        val session = try {
            callFactory()
        } catch (error: Throwable) {
            logger.e(error) { "webrtc create ${error::class.simpleName}" }
            _uiState.update { it.copy(connecting = false, error = CallError.Mic) }
            return
        }
        call = session
        try {
            val offer = session.createOffer()
            logger.i { "rtc offer bytes=${offer.length}" }
            val answer = client.startRtc(sessionId, offer)
            session.setRemoteAnswer(answer)
            _uiState.update { it.copy(connecting = false) }
            listenCaptions(session)
            startTimer()
        } catch (error: ApiException) {
            session.close()
            call = null
            val mapped = when (error.status) {
                HttpStatusCode.Conflict -> CallError.Conflict
                HttpStatusCode.Unauthorized -> {
                    logger.w { "rtc HTTP 401" }
                    CallError.Network
                }
                else -> CallError.Network
            }
            logger.w { "rtc HTTP ${error.status.value}" }
            _uiState.update { it.copy(connecting = false, error = mapped) }
        } catch (error: TimeoutCancellationException) {
            session.close()
            call = null
            logger.e(error) { "rtc ice timeout" }
            _uiState.update { it.copy(connecting = false, error = CallError.Network) }
        } catch (error: Throwable) {
            session.close()
            call = null
            logger.e(error) { "rtc ${error::class.simpleName}" }
            _uiState.update { it.copy(connecting = false, error = CallError.Mic) }
        }
    }

    private fun listenCaptions(session: RealtimeCall) {
        captionsJob = viewModelScope.launch {
            session.captions.collect { caption ->
                _uiState.update { it.copy(caption = caption) }
            }
        }
    }

    private fun startTimer() {
        elapsedSec = 0
        timer = viewModelScope.launch {
            while (isActive) {
                _uiState.update { it.copy(elapsed = formatElapsed(elapsedSec)) }
                delay(1_000)
                elapsedSec += 1
            }
        }
    }

    private suspend fun completeCall(): HangupOutcome {
        val session = call
        val durationSec = elapsedSec
        val turns = session?.snapshotTurns().orEmpty()
        runCatching { session?.close() }
        call = null
        return try {
            client.completeSession(sessionId, turns, durationSec)
            HangupOutcome.Review
        } catch (error: ApiException) {
            when (error.status) {
                HttpStatusCode.UnprocessableEntity -> HangupOutcome.TooShort
                HttpStatusCode.Conflict -> HangupOutcome.Review
                else -> HangupOutcome.Failed
            }
        } catch (error: Throwable) {
            logger.e(error) { "complete ${error::class.simpleName}" }
            HangupOutcome.Failed
        }
    }
}

internal fun formatElapsed(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}
