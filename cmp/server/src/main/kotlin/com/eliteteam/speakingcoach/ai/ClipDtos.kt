package com.eliteteam.speakingcoach.ai

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
data class MetricsSnapshot(
    val timezone: String,
    val day: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val tpm: Long,
    val tps: Double,
    val turns: Long,
    val dau: Long,
    val sttSeconds: Double,
    val ttsChars: Long,
    val rubPerTurn: Double? = null,
    val rubPerDau: Double? = null,
    val ratesConfigured: Boolean = false,
    val chats: List<MetricsChat> = emptyList(),
    val activated7: Long = 0,
    val funnelDays: List<FunnelDay> = emptyList(),
    val funnelSources: List<FunnelSource> = emptyList(),
)

@Serializable
data class FunnelDay(
    val day: String,
    val start: Long = 0,
    val activated: Long = 0,
    val engaged: Long = 0,
    val returned: Long = 0,
)

@Serializable
data class FunnelSource(
    val source: String,
    val start: Long = 0,
    val activated: Long = 0,
    val engaged: Long = 0,
    val returned: Long = 0,
)

@Serializable
data class FunnelStartRequest(
    val sessionId: String,
    val source: String? = null,
)

@Serializable
data class FunnelVoiceRequest(
    val sessionId: String,
)

@Serializable
data class MetricsChat(
    val sessionId: String,
    val turns: Long,
    val lastAt: String,
)
