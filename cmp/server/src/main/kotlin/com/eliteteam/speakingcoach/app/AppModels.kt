package com.eliteteam.speakingcoach.app

internal data class AppUser(
    val id: String,
    val email: String,
    val displayName: String,
    val passwordHash: String,
)

internal data class SpeakingSession(
    val id: String,
    val userId: String,
    val topic: String,
    val tutorVoice: String,
    val rtcActive: Boolean = false,
    val openaiCallId: String? = null,
    val durationSec: Int? = null,
    val status: SessionStatus = SessionStatus.Created,
)

internal enum class SessionStatus {
    Created,
    Rtc,
    Reviewing,
    Ready,
    TooShort,
    ReviewFailed,
}

internal data class TranscriptTurn(
    val role: String,
    val text: String,
)

internal data class RealtimeCall(
    val sdpAnswer: String,
    val openaiCallId: String?,
)
