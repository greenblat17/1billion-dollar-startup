package com.eliteteam.speakingcoach.app

internal interface InternalAi {
    suspend fun startCall(
        sdp: String,
        topic: String,
        tutorVoice: String,
        sessionId: String = "",
        platform: String? = null,
    ): RealtimeCall

    suspend fun review(
        turns: List<TranscriptTurn>, sessionId: String = "", platform: String? = null,
    ): InternalReviewResponse

    suspend fun noteSession(sessionId: String, action: String, platform: String?) {}
}
