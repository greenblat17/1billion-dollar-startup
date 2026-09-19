package com.eliteteam.speakingcoach.app

internal interface InternalAi {
    suspend fun startCall(sdp: String, topic: String, tutorVoice: String): RealtimeCall

    suspend fun review(turns: List<TranscriptTurn>): InternalReviewResponse
}
