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

data class TranscriptTurn(
    val role: String,
    val text: String,
)

sealed class ReviewPoll {
    data class Ready(val steps: List<ReviewStepDto>) : ReviewPoll()
    data object Pending : ReviewPoll()
    data object TooShort : ReviewPoll()
    data object Failed : ReviewPoll()
}

@Serializable
internal data class CompleteRequestDto(
    val turns: List<CompleteTurnDto>,
    val durationSec: Int,
)

@Serializable
internal data class CompleteTurnDto(
    val role: String,
    val text: String,
)

@Serializable
data class ReviewStepDto(
    val metric: String,
    val score: Int,
    val lead: String,
    val bullets: List<String> = emptyList(),
    val examples: List<ReviewExampleDto> = emptyList(),
    val tip: String,
)

@Serializable
data class ReviewExampleDto(
    val original: String,
    val improved: String,
)

@Serializable
internal data class ReviewResponseDto(
    val sessionId: String,
    val steps: List<ReviewStepDto> = emptyList(),
)
