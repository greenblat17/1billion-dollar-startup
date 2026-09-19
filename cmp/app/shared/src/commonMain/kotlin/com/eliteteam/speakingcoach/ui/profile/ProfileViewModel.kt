package com.eliteteam.speakingcoach.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eliteteam.speakingcoach.data.SessionStore
import com.eliteteam.speakingcoach.data.SpeakingCoachClient
import com.eliteteam.speakingcoach.ui.mock.DailyGoalStore
import com.eliteteam.speakingcoach.ui.mock.MockSpeakingData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val TutorVoices = listOf("marin", "cedar")

class ProfileViewModel(
    private val client: SpeakingCoachClient,
    private val sessionStore: SessionStore,
    private val dailyGoalStore: DailyGoalStore,
) : ViewModel() {
    private val captions = MutableStateFlow(sessionStore.captionsByDefault)
    private val tutorVoice = MutableStateFlow(sessionStore.tutorVoice)

    val uiState: StateFlow<ProfileUiState> = combine(
        captions,
        dailyGoalStore.state,
        sessionStore.session,
        tutorVoice,
    ) { captionsOn, goal, session, voice ->
        val user = session?.user
        val name = user?.displayName ?: MockSpeakingData.UserName
        ProfileUiState(
            displayName = name,
            email = user?.email ?: MockSpeakingData.UserEmail,
            language = MockSpeakingData.profile.language,
            tutorVoice = voice.replaceFirstChar { it.uppercase() },
            captionsByDefault = captionsOn,
            avatarLetter = name.firstOrNull()?.uppercase() ?: "?",
            goalMinutes = goal.goalMinutes,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MockSpeakingData.profile,
    )

    fun onCaptionsToggled(enabled: Boolean) {
        captions.value = enabled
        sessionStore.setCaptionsByDefault(enabled)
    }

    fun onDailyGoalCycled() {
        dailyGoalStore.cycleGoalMinutes()
    }

    fun onTutorVoiceCycled() {
        val current = tutorVoice.value
        val next = TutorVoices[(TutorVoices.indexOf(current).let { if (it < 0) 0 else it } + 1) % TutorVoices.size]
        tutorVoice.value = next
        sessionStore.setTutorVoice(next)
    }

    fun signOut() {
        viewModelScope.launch {
            runCatching { client.logout() }
            sessionStore.clear()
        }
    }
}
