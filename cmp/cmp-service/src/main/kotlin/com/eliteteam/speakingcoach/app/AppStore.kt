package com.eliteteam.speakingcoach.app

internal class DuplicateEmailException : RuntimeException()

internal sealed interface RtcStart {
    data object NotFound : RtcStart
    data object Conflict : RtcStart
    data class Ok(val session: SpeakingSession) : RtcStart
}

internal interface AppStore {
    suspend fun createUser(email: String, passwordHash: String, displayName: String): AppUser

    suspend fun findUserByEmail(email: String): AppUser?

    suspend fun findUserById(id: String): AppUser?

    suspend fun createSession(userId: String, topic: String, tutorVoice: String): SpeakingSession

    suspend fun findSession(id: String): SpeakingSession?

    suspend fun userHasActiveRtc(userId: String): Boolean

    suspend fun tryStartRtc(sessionId: String): RtcStart

    suspend fun attachOpenaiCallId(sessionId: String, openaiCallId: String?)

    suspend fun releaseRtc(sessionId: String)

    suspend fun markCompleted(sessionId: String, durationSec: Int, tooShort: Boolean): SpeakingSession?

    suspend fun saveReview(sessionId: String, payloadJson: String)

    suspend fun findReviewJson(sessionId: String): String?

    suspend fun markReviewFailed(sessionId: String)
}
