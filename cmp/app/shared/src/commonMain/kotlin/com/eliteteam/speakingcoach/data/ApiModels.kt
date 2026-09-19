package com.eliteteam.speakingcoach.data

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

data class AuthUser(
    val id: String,
    val email: String,
    val displayName: String,
)

data class AuthSession(
    val token: String,
    val user: AuthUser,
)

class ApiException(
    val status: HttpStatusCode,
) : Exception("HTTP ${status.value}")

@Serializable
internal data class RegisterRequestDto(
    val email: String,
    val password: String,
    val displayName: String,
)

@Serializable
internal data class LoginRequestDto(
    val email: String,
    val password: String,
)

@Serializable
internal data class AuthUserDto(
    val id: String,
    val email: String,
    val displayName: String,
)

@Serializable
internal data class AuthResponseDto(
    val token: String,
    val user: AuthUserDto,
)

@Serializable
internal data class HomeResponseDto(
    val userName: String,
)

@Serializable
internal data class CreateSessionRequestDto(
    val topic: String,
    val tutorVoice: String,
)

@Serializable
internal data class CreateSessionResponseDto(
    val sessionId: String,
)
