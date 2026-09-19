package com.eliteteam.speakingcoach.ui.auth

data class AuthUiState(
    val register: Boolean,
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val busy: Boolean = false,
    val error: AuthError? = null,
)

enum class AuthError {
    Invalid,
    EmailTaken,
    Network,
}
