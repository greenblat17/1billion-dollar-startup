package com.eliteteam.speakingcoach.speaking

fun interface ClipSource {
    suspend fun load(): AudioClip
}

fun interface ClipProcessor {
    suspend fun process(sessionId: SessionId, clip: AudioClip): ClipReply
}

data class ClipReply(
    val notes: List<String>,
    val audio: AudioClip,
)

data class SessionGreeting(
    val sessionId: SessionId,
    val text: String,
    val audio: AudioClip,
)

sealed interface ClipSubmitResult {
    data class Completed(val reply: ClipReply) : ClipSubmitResult
    data object QueueFull : ClipSubmitResult
}
