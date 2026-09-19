package com.eliteteam.speakingcoach.data

interface SpeakingCoachClient {
    suspend fun register(email: String, password: String, displayName: String): AuthSession
    suspend fun login(email: String, password: String): AuthSession
    suspend fun logout()
    suspend fun loadHome(): String
    suspend fun createSession(topic: String, tutorVoice: String): String
}
