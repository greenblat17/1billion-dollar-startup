package com.eliteteam.speakingcoach.app

import kotlinx.serialization.Serializable

@Serializable
internal data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String,
)

@Serializable
internal data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
internal data class AuthUserResponse(
    val id: String,
    val email: String,
    val displayName: String,
)

@Serializable
internal data class AuthResponse(
    val token: String,
    val user: AuthUserResponse,
)

@Serializable
internal data class HomeResponse(
    val userName: String,
)

@Serializable
internal data class CreateSessionRequest(
    val topic: String,
    val tutorVoice: String,
)

@Serializable
internal data class CreateSessionResponse(
    val sessionId: String,
)

@Serializable
internal data class RtcOfferJson(
    val sdp: String,
)

@Serializable
internal data class CompleteRequest(
    val turns: List<CompleteTurnDto> = emptyList(),
    val durationSec: Int = 0,
)

@Serializable
internal data class CompleteTurnDto(
    val role: String,
    val text: String,
)

@Serializable
internal data class ReviewResponse(
    val sessionId: String,
    val steps: List<ReviewStepDto>,
)

@Serializable
internal data class ReviewStepDto(
    val metric: String,
    val score: Int,
    val lead: String,
    val bullets: List<String> = emptyList(),
    val examples: List<ReviewExampleDto> = emptyList(),
    val tip: String,
)

@Serializable
internal data class ReviewExampleDto(
    val original: String,
    val improved: String,
)

@Serializable
internal data class InternalRealtimeRequest(
    val sdp: String,
    val topic: String,
    val tutorVoice: String,
)

@Serializable
internal data class InternalRealtimeResponse(
    val sdp: String,
    val openaiCallId: String? = null,
)

@Serializable
internal data class InternalReviewRequest(
    val turns: List<CompleteTurnDto>,
)

@Serializable
internal data class InternalReviewResponse(
    val steps: List<ReviewStepDto> = emptyList(),
)
