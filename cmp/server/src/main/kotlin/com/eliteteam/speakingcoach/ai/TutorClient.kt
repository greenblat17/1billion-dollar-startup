package com.eliteteam.speakingcoach.ai

import com.eliteteam.speakingcoach.RuntimeDiagnostics

import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
data class TutorRequest(val sessionId: String, val requestId: String, val text: String, val receivedAt: Double)
@Serializable
data class TrialBooking(val id: String, val learnerId: String, val status: String,
                        val startsAtUtc: String, val timezone: String, val version: Int,
                        val durationMinutes: Int = 15, val timing: String = "scheduled") {
    val startsImmediately: Boolean get() = timing == "now" && status in setOf("scheduled", "in_progress")
}
@Serializable
data class TutorResponse(val conversationId: String, val reply: String, val booking: TrialBooking? = null,
                         val bookingText: String? = null, val degraded: Boolean = false,
                         val replyMessageId: Long? = null, val cardMessageId: Long? = null,
                         val showProgressNavigation: Boolean = true, val cardIsWidget: Boolean = false,
                         val cardDelivered: Boolean = false, val joinLessonId: String? = null,
                         val splitIntroductionReply: Boolean = false)

@Serializable
data class TutorDelivery(val sessionId: String, val requestId: String, val replyMessageId: Long? = null,
                         val bookingId: String? = null, val cardMessageId: Long? = null, val version: Int? = null, val cardIsWidget: Boolean = false)

class TutorClient(private val root: String, private val http: HttpClient, private val token: String) {
    internal var foundation: com.eliteteam.speakingcoach.foundation.FoundationChat? = null

    suspend fun beginDelivery(delivery: TutorDelivery): Boolean = foundation?.beginDelivery(delivery) ?: true

    /** DEV agent-test preview; caller retains the scope and never looks up a Telegram recipient. */
    internal suspend fun initialRoadmapPreview(scope: String, revision: Long): JsonObject {
        require(revision > 0)
        val response = http.post("$root/internal/trial/initial-roadmap") {
            header(AI_INTERNAL_TOKEN_HEADER, token)
            header("X-Execution-Scope", scope)
            contentType(ContentType.Application.Json)
            timeout { requestTimeoutMillis = 120_000; socketTimeoutMillis = 120_000 }
            setBody("{\"roadmapRevision\":$revision}")
        }
        check(response.status.isSuccess()) { "Initial roadmap preview failed: ${response.status}" }
        val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val reply = result["reply"] as? JsonPrimitive
        check(reply != null && reply.isString && reply.content.isNotBlank() &&
            (result["roadmapRevision"] as? JsonPrimitive)?.longOrNull == revision) { "Invalid initial roadmap preview" }
        return JsonObject(mapOf("reply" to reply, "roadmapRevision" to JsonPrimitive(revision)))
    }

    suspend fun delivered(delivery: TutorDelivery) {
        foundation?.let { it.delivered(delivery); return }
        val response = http.post("$root/internal/tutor/delivery") {
            header(AI_INTERNAL_TOKEN_HEADER, token)
            header("X-Diagnostic-Trace", RuntimeDiagnostics.current()["trace_id"].orEmpty())
            contentType(ContentType.Application.Json)
            setBody(delivery)
        }
        check(response.status.isSuccess()) { "Tutor delivery receipt failed: ${response.status}" }
    }

    suspend fun respond(request: TutorRequest, start: Boolean): TutorResponse =
        RuntimeDiagnostics.withContext(mapOf("trace_id" to RuntimeDiagnostics.trace(request.sessionId + ":" + request.requestId))) {
            try { respondCurrent(request, start) }
            catch (error: Exception) {
                if (error !is kotlinx.coroutines.CancellationException) RuntimeDiagnostics.record("tutor_failed", "respond", error)
                throw error
            }
        }

    private suspend fun respondCurrent(request: TutorRequest, start: Boolean): TutorResponse {
        val bridge = foundation
        val scope = bridge?.accept(request, start)
        val path = if (bridge != null) "/internal/foundation/tutor/turn?start=$start" else "/internal/tutor/${if (start) "start" else "turn"}"
        val started = System.nanoTime()
        val response = http.post("$root$path") {
            header(AI_INTERNAL_TOKEN_HEADER, token)
            header("X-Diagnostic-Trace", RuntimeDiagnostics.current()["trace_id"].orEmpty())
            if (scope != null) header("X-Execution-Scope", scope)
            contentType(ContentType.Application.Json)
            timeout { requestTimeoutMillis = 120_000; socketTimeoutMillis = 120_000 }
            setBody(request)
        }
        if (!response.status.isSuccess()) RuntimeDiagnostics.record("tutor_http_failed", "ai_response",
            extra = RuntimeDiagnostics.httpFields(response.status.value, response.bodyAsText(), response.headers["X-Error-ID"]))
        check(response.status.isSuccess()) { "Tutor request failed: ${response.status}" }
        val answer: TutorResponse = try {
            response.body()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            RuntimeDiagnostics.record("tutor_failed", "parse", error, mapOf("reason_code" to "invalid_ai_response"))
            throw error
        }
        RuntimeDiagnostics.record("tutor_http", "response", extra = mapOf("operation" to "tutor_turn",
            "provider" to "ai", "http_status" to response.status.value.toString(),
            "duration_ms" to RuntimeDiagnostics.elapsed(started)))
        return bridge?.withDelivery(request, answer) ?: answer
    }
}
