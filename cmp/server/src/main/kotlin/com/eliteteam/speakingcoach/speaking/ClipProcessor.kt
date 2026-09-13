package com.eliteteam.speakingcoach.speaking

fun interface ClipSource {
    suspend fun load(): AudioClip
}

fun interface ClipProcessor {
    suspend fun process(sessionId: SessionId, clip: AudioClip): AudioClip
}

sealed interface ClipSubmitResult {
    data class Completed(val reply: AudioClip) : ClipSubmitResult
    data object QueueFull : ClipSubmitResult
}
