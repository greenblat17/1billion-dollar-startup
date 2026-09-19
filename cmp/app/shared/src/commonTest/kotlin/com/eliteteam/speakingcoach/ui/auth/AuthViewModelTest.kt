package com.eliteteam.speakingcoach.ui.auth

import com.eliteteam.speakingcoach.data.ApiException
import com.eliteteam.speakingcoach.data.AuthSession
import com.eliteteam.speakingcoach.data.AuthUser
import com.eliteteam.speakingcoach.data.SessionStore
import com.eliteteam.speakingcoach.data.SpeakingCoachClient
import com.russhwolf.settings.MapSettings
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
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
    fun registerSuccessWritesSession() = runTest(dispatcher) {
        val store = SessionStore(MapSettings())
        val vm = AuthViewModel(FakeClient(), store, register = true)
        vm.onEmailChanged("ed@example.com")
        vm.onPasswordChanged("secret12")
        vm.onDisplayNameChanged("Ed")
        vm.onSubmit()
        advanceUntilIdle()
        assertEquals("Ed", store.session.value?.user?.displayName)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun loginUnauthorizedShowsInvalid() = runTest(dispatcher) {
        val store = SessionStore(MapSettings())
        val client = FakeClient().apply { loginError = ApiException(HttpStatusCode.Unauthorized) }
        val vm = AuthViewModel(client, store, register = false)
        vm.onEmailChanged("ed@example.com")
        vm.onPasswordChanged("nope")
        vm.onSubmit()
        advanceUntilIdle()
        assertEquals(AuthError.Invalid, vm.uiState.value.error)
        assertNull(store.session.value)
    }
}

private class FakeClient : SpeakingCoachClient {
    var loginError: ApiException? = null

    override suspend fun register(email: String, password: String, displayName: String) =
        AuthSession("jwt", AuthUser("1", email.lowercase(), displayName))

    override suspend fun login(email: String, password: String): AuthSession {
        loginError?.let { throw it }
        return AuthSession("jwt", AuthUser("1", email.lowercase(), "Ed"))
    }

    override suspend fun logout() = Unit
    override suspend fun loadHome() = "Ed"
    override suspend fun createSession(topic: String, tutorVoice: String) = "app-1"
}
