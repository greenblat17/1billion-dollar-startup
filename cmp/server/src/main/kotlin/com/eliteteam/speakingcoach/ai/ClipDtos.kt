package com.eliteteam.speakingcoach.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SessionCreateRequest(
    val sessionId: String? = null,
)

@Serializable
data class SessionCreatedResponse(
    val sessionId: String,
    val greeting: GreetingResponse,
)

@Serializable
data class GreetingResponse(
    val text: String,
)

@Serializable
data class ClipAcceptedResponse(
    val jobId: String,
)

@Serializable
data class ClipStatusResponse(
    val jobId: String,
    val status: String,
    val result: ClipResultResponse? = null,
    val error: ClipErrorResponse? = null,
    val transcript: String? = null,
)

@Serializable
data class ClipResultResponse(
    val notes: List<String> = emptyList(),
    val transcript: String = "",
)

@Serializable
data class ClipErrorResponse(
    val code: String,
    val message: String,
)

@Serializable
data class WalkieTalkieResponse(
    @SerialName("session_id")
    val sessionId: String,
    val transcript: String = "",
    @SerialName("response_text")
    val responseText: String = "",
    @SerialName("audio_mime_type")
    val audioMimeType: String = "audio/wav",
    @SerialName("audio_base64")
    val audioBase64: String,
)
