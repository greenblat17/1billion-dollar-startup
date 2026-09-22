package com.eliteteam.speakingcoach.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.eliteteam.speakingcoach.data.ApiException
import com.eliteteam.speakingcoach.data.SessionStore
import com.eliteteam.speakingcoach.data.SpeakingCoachClient
import com.eliteteam.speakingcoach.ui.mock.DailyGoalStore
import com.eliteteam.speakingcoach.ui.mock.MockSpeakingData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val client: SpeakingCoachClient,
    private val sessionStore: SessionStore,
    dailyGoalStore: DailyGoalStore,
    private val logger: Logger,
) : ViewModel() {
    private val selectedTopic = MutableStateFlow(MockSpeakingData.home.selectedTopic)
    private val remoteUserName = MutableStateFlow<String?>(null)
    private val start = MutableStateFlow(StartUi())
    private var homeLoadId = 0

    val uiState: StateFlow<HomeUiState> = combine(
        selectedTopic,
        dailyGoalStore.state,
        sessionStore.session,
        remoteUserName,
        start,
    ) { topic, goal, session, remoteName, startUi ->
        MockSpeakingData.home.copy(
            userName = remoteName
                ?: session?.user?.displayName
                ?: MockSpeakingData.UserName,
            selectedTopic = topic,
            spokenSeconds = goal.spokenSeconds,
            streakDays = goal.streakDays,
            goalMinutes = goal.goalMinutes,
            startBusy = startUi.busy,
            startFailed = startUi.failed,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MockSpeakingData.home.copy(
            userName = sessionStore.session.value?.user?.displayName
                ?: MockSpeakingData.UserName,
        ),
    )

    init {
        viewModelScope.launch {
            sessionStore.session
                .map { it?.user?.id }
                .distinctUntilChanged()
                .collect { userId ->
                    homeLoadId += 1
                    remoteUserName.value = null
                    if (userId != null) {
                        logger.i { "refresh home userId=$userId" }
                        refreshHome()
                    }
                }
        }
    }

    fun onTopicSelected(topic: TopicKind) {
        logger.i { "topic ${topic.name}" }
        selectedTopic.update { topic }
    }

    fun openLastConversation(open: () -> Unit) {
        logger.i { "last conversation mock, no API" }
        open()
    }

    fun startConversation(onStarted: (String) -> Unit) {
        if (start.value.busy) return
        start.value = StartUi(busy = true)
        val topic = selectedTopic.value.toApiTopic()
        logger.i { "start session topic=$topic" }
        viewModelScope.launch {
            try {
                val sessionId = client.createSession(
                    topic = topic,
                    tutorVoice = sessionStore.tutorVoice,
                )
                start.value = StartUi()
                logger.i { "open call sessionId=$sessionId" }
                onStarted(sessionId)
            } catch (error: ApiException) {
                if (error.status == HttpStatusCode.Unauthorized) {
                    start.value = StartUi()
                    logger.w { "start session HTTP 401, clearing session" }
                    sessionStore.clear()
                } else {
                    logger.w { "start session HTTP ${error.status.value}" }
                    start.value = StartUi(failed = true)
                }
            } catch (error: Throwable) {
                start.value = StartUi(failed = true)
                logger.e(error) { "start session ${error::class.simpleName}" }
            }
        }
    }

    private fun refreshHome() {
        val loadId = homeLoadId
        viewModelScope.launch {
            try {
                val name = client.loadHome()
                if (loadId == homeLoadId) {
                    remoteUserName.value = name
                }
            } catch (error: ApiException) {
                if (loadId != homeLoadId) return@launch
                if (error.status == HttpStatusCode.Unauthorized) {
                    logger.w { "home HTTP 401, clearing session" }
                    sessionStore.clear()
                } else {
                    logger.w { "home HTTP ${error.status.value}" }
                }
            } catch (error: Throwable) {
                if (loadId != homeLoadId) return@launch
                logger.w(error) { "home ${error::class.simpleName}, keep cached name" }
            }
        }
    }
}

private data class StartUi(
    val busy: Boolean = false,
    val failed: Boolean = false,
)
