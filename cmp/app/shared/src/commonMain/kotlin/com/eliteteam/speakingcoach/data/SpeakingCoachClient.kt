package com.eliteteam.speakingcoach.data

interface SpeakingCoachClient {
    suspend fun register(email: String, password: String, displayName: String): AuthSession
    suspend fun login(email: String, password: String): AuthSession
    suspend fun logout()
    suspend fun loadHome(): String
    suspend fun createSession(topic: String, tutorVoice: String): String
    suspend fun startRtc(sessionId: String, sdpOffer: String): String
    suspend fun completeSession(sessionId: String, turns: List<TranscriptTurn>, durationSec: Int)
    suspend fun pollReview(sessionId: String): ReviewPoll
}
