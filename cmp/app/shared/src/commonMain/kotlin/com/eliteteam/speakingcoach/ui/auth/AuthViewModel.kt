package com.eliteteam.speakingcoach.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.eliteteam.speakingcoach.data.ApiException
import com.eliteteam.speakingcoach.data.SessionStore
import com.eliteteam.speakingcoach.data.SpeakingCoachClient
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AuthViewModel(
    private val client: SpeakingCoachClient,
    private val sessionStore: SessionStore,
    register: Boolean,
    private val logger: Logger,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthUiState(register = register))
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun onEmailChanged(value: String) {
        _uiState.update { it.copy(email = value, error = null) }
    }

    fun onPasswordChanged(value: String) {
        _uiState.update { it.copy(password = value, error = null) }
    }

    fun onDisplayNameChanged(value: String) {
        _uiState.update { it.copy(displayName = value, error = null) }
    }

    fun onSubmit() {
        val current = _uiState.value
        if (current.busy) return
        _uiState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val action = if (current.register) "register" else "login"
            logger.i { "$action submit" }
            try {
                val session = if (current.register) {
                    client.register(current.email, current.password, current.displayName)
                } else {
                    client.login(current.email, current.password)
                }
                logger.i { "$action ok userId=${session.user.id}" }
                sessionStore.save(session)
            } catch (error: ApiException) {
                val mapped = error.toAuthError(current.register)
                logger.w { "$action failed HTTP ${error.status.value} $mapped" }
                _uiState.update {
                    it.copy(busy = false, error = mapped)
                }
            } catch (error: Throwable) {
                logger.e(error) { "$action failed ${error::class.simpleName}" }
                _uiState.update { it.copy(busy = false, error = AuthError.Network) }
            }
        }
    }
}

private fun ApiException.toAuthError(register: Boolean): AuthError = when {
    register && status == HttpStatusCode.Conflict -> AuthError.EmailTaken
    status == HttpStatusCode.Unauthorized || status == HttpStatusCode.BadRequest -> AuthError.Invalid
    else -> AuthError.Network
}
