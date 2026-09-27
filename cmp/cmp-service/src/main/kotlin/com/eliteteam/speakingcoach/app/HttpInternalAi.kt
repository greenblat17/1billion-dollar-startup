package com.eliteteam.speakingcoach.app

import com.eliteteam.speakingcoach.ai.AI_INTERNAL_TOKEN_HEADER
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

internal class HttpInternalAi(
    baseUrl: String,
    private val http: HttpClient,
    private val internalToken: String,
) : InternalAi {
    private val root = baseUrl.trimEnd('/')

    override suspend fun startCall(sdp: String, topic: String, tutorVoice: String): RealtimeCall {
        val response = http.post("$root/internal/realtime/call") {
            applyInternalToken()
            contentType(ContentType.Application.Json)
            setBody(InternalRealtimeRequest(sdp = sdp, topic = topic, tutorVoice = tutorVoice))
        }
        if (!response.status.isSuccess()) {
            error("ai-service POST /internal/realtime/call returned ${response.status}")
        }
        val body = response.body<InternalRealtimeResponse>()
        return RealtimeCall(sdpAnswer = body.sdp, openaiCallId = body.openaiCallId)
    }

    override suspend fun review(turns: List<TranscriptTurn>): InternalReviewResponse {
        val response = http.post("$root/internal/review") {
            applyInternalToken()
            contentType(ContentType.Application.Json)
            setBody(
                InternalReviewRequest(
                    turns = turns.map { CompleteTurnDto(role = it.role, text = it.text) },
                ),
            )
        }
        if (!response.status.isSuccess()) {
            error("ai-service POST /internal/review returned ${response.status}")
        }
        return response.body()
    }

    private fun HttpRequestBuilder.applyInternalToken() {
        if (internalToken.isNotBlank()) {
            header(AI_INTERNAL_TOKEN_HEADER, internalToken)
        }
    }
}
