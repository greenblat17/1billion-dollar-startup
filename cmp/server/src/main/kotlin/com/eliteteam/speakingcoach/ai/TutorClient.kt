package com.eliteteam.speakingcoach.ai

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

@Serializable
data class TutorRequest(val sessionId: String, val requestId: String, val text: String, val receivedAt: Double)
@Serializable
data class TrialBooking(val id: String, val learnerId: String, val status: String,
                        val startsAtUtc: String, val timezone: String, val version: Int,
                        val durationMinutes: Int = 15)
@Serializable
data class TutorResponse(val conversationId: String, val reply: String, val booking: TrialBooking? = null,
                         val bookingText: String? = null, val degraded: Boolean = false,
                         val replyMessageId: Long? = null, val cardMessageId: Long? = null)

@Serializable
data class TutorDelivery(val sessionId: String, val requestId: String, val replyMessageId: Long? = null,
                         val bookingId: String? = null, val cardMessageId: Long? = null, val version: Int? = null)

class TutorClient(private val root: String, private val http: HttpClient, private val token: String) {
    internal var foundation: com.eliteteam.speakingcoach.foundation.FoundationChat? = null

    suspend fun beginDelivery(delivery: TutorDelivery): Boolean = foundation?.beginDelivery(delivery) ?: true

    suspend fun delivered(delivery: TutorDelivery) {
        foundation?.let { it.delivered(delivery); return }
        val response = http.post("$root/internal/tutor/delivery") {
            header(AI_INTERNAL_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(delivery)
        }
        check(response.status.isSuccess()) { "Tutor delivery receipt failed: ${response.status}" }
    }

    suspend fun respond(request: TutorRequest, start: Boolean): TutorResponse {
        val bridge = foundation
        val scope = bridge?.accept(request, start)
        val path = if (bridge != null) "/internal/foundation/tutor/turn?start=$start" else "/internal/tutor/${if (start) "start" else "turn"}"
        val response = http.post("$root$path") {
            header(AI_INTERNAL_TOKEN_HEADER, token)
            if (scope != null) header("X-Execution-Scope", scope)
            contentType(ContentType.Application.Json)
            timeout { requestTimeoutMillis = 120_000; socketTimeoutMillis = 120_000 }
            setBody(request)
        }
        check(response.status.isSuccess()) { "Tutor request failed: ${response.status}" }
        val answer: TutorResponse = response.body()
        return bridge?.withDelivery(request, answer) ?: answer
    }
}
