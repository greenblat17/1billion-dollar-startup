package com.eliteteam.speakingcoach.ui.home

import com.eliteteam.speakingcoach.data.AuthSession
import com.eliteteam.speakingcoach.data.AuthUser
import com.eliteteam.speakingcoach.data.ReviewPoll
import com.eliteteam.speakingcoach.data.SessionStore
import com.eliteteam.speakingcoach.data.SpeakingCoachClient
import com.eliteteam.speakingcoach.data.TranscriptTurn
import com.eliteteam.speakingcoach.testLogger
import com.eliteteam.speakingcoach.ui.mock.DailyGoalStore
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
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
    fun reloadsNameWhenSessionUserChanges() = runTest(dispatcher) {
        val store = SessionStore(MapSettings(), testLogger())
        store.save(session("1", "Ed"))
        val client = FakeHomeClient(homeName = "Ed")
        val vm = HomeViewModel(client, store, DailyGoalStore(), testLogger())
        backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()
        assertEquals("Ed", vm.uiState.value.userName)
        assertEquals(1, client.loadHomeCount)

        store.clear()
        client.homeName = "Ned"
        store.save(session("2", "Ned"))
        advanceUntilIdle()
        assertEquals("Ned", vm.uiState.value.userName)
        assertEquals(2, client.loadHomeCount)
    }

    @Test
    fun showsSessionNameUntilHomeReturns() = runTest(dispatcher) {
        val store = SessionStore(MapSettings(), testLogger())
        store.save(session("1", "Ed"))
        val client = FakeHomeClient(homeName = "Ed From Api")
        client.pendingHome = true
        val vm = HomeViewModel(client, store, DailyGoalStore(), testLogger())
        backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()
        assertEquals("Ed", vm.uiState.value.userName)
        assertEquals(0, client.loadHomeCount)

        client.releaseHome()
        advanceUntilIdle()
        assertEquals("Ed From Api", vm.uiState.value.userName)
        assertEquals(1, client.loadHomeCount)
    }
}

private fun session(id: String, name: String) = AuthSession(
    token = "jwt-$id",
    user = AuthUser(id = id, email = "$name@releva.test", displayName = name),
)

private class FakeHomeClient(
    var homeName: String,
) : SpeakingCoachClient {
    var loadHomeCount = 0
    var pendingHome = false
    private val homeGate = CompletableDeferred<Unit>()

    fun releaseHome() {
        pendingHome = false
        homeGate.complete(Unit)
    }

    override suspend fun register(email: String, password: String, displayName: String) =
        error("unused")

    override suspend fun login(email: String, password: String) = error("unused")

    override suspend fun logout() = Unit

    override suspend fun loadHome(): String {
        if (pendingHome) {
            homeGate.await()
        }
        loadHomeCount += 1
        return homeName
    }

    override suspend fun createSession(topic: String, tutorVoice: String) = "app-1"
    override suspend fun startRtc(sessionId: String, sdpOffer: String) = "v=0"
    override suspend fun completeSession(
        sessionId: String,
        turns: List<TranscriptTurn>,
        durationSec: Int,
    ) = Unit
    override suspend fun pollReview(sessionId: String) = ReviewPoll.Failed
}
