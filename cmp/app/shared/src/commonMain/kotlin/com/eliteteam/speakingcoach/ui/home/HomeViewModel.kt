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
    private val userName = MutableStateFlow(
        sessionStore.session.value?.user?.displayName ?: MockSpeakingData.UserName,
    )
    private val startBusy = MutableStateFlow(false)
    private val startFailed = MutableStateFlow(false)

    val uiState: StateFlow<HomeUiState> = combine(
        selectedTopic,
        dailyGoalStore.state,
        userName,
        startBusy,
        startFailed,
    ) { topic, goal, name, busy, failed ->
        MockSpeakingData.home.copy(
            userName = name,
            selectedTopic = topic,
            spokenSeconds = goal.spokenSeconds,
            streakDays = goal.streakDays,
            goalMinutes = goal.goalMinutes,
            startBusy = busy,
            startFailed = failed,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MockSpeakingData.home.copy(
            userName = userName.value,
        ),
    )

    init {
        refreshHome()
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
        if (startBusy.value) return
        startBusy.value = true
        startFailed.value = false
        val topic = selectedTopic.value.toApiTopic()
        logger.i { "start session topic=$topic" }
        viewModelScope.launch {
            try {
                val sessionId = client.createSession(
                    topic = topic,
                    tutorVoice = sessionStore.tutorVoice,
                )
                startBusy.value = false
                logger.i { "open call sessionId=$sessionId" }
                onStarted(sessionId)
            } catch (error: ApiException) {
                startBusy.value = false
                if (error.status == HttpStatusCode.Unauthorized) {
                    logger.w { "start session HTTP 401, clearing session" }
                    sessionStore.clear()
                } else {
                    logger.w { "start session HTTP ${error.status.value}" }
                    startFailed.value = true
                }
            } catch (error: Throwable) {
                startBusy.value = false
                startFailed.value = true
                logger.e(error) { "start session ${error::class.simpleName}" }
            }
        }
    }

    private fun refreshHome() {
        viewModelScope.launch {
            try {
                userName.value = client.loadHome()
            } catch (error: ApiException) {
                if (error.status == HttpStatusCode.Unauthorized) {
                    logger.w { "home HTTP 401, clearing session" }
                    sessionStore.clear()
                } else {
                    logger.w { "home HTTP ${error.status.value}" }
                }
            } catch (error: Throwable) {
                logger.w(error) { "home ${error::class.simpleName}, keep cached name" }
            }
        }
    }
}
