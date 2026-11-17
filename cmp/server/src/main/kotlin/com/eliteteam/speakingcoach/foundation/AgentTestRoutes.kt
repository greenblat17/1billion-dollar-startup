package com.eliteteam.speakingcoach.foundation

import com.eliteteam.speakingcoach.ai.TutorClient
import com.eliteteam.speakingcoach.ai.TutorRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Opt-in DEV adapter. Its identity has no Telegram recipient, so workers cannot message a real chat. */
internal fun Route.agentTestRoutes(service: FoundationService, tutor: TutorClient, token: String) {
    route("/internal/agent-test/v1/{id}") {
        post("/turn") {
            if (call.request.local.remoteHost !in setOf("127.0.0.1", "::1")) {
                call.respondText("not found", status = HttpStatusCode.NotFound)
                return@post
            }
            if (!agentAuthorized(token, call.request.header("X-Internal-Token"))) {
                call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
                return@post
            }
            try {
                val id = agentId(call.parameters["id"])
                val body = call.receiveText()
                if (body.length > 12_500) reject("request_too_large")
                val input = foundationJson.parseToJsonElement(body).jsonObject
                input.only("messageId", "text", "receivedAt")
                val messageId = agentId(input.text("messageId"))
                val content = input.text("text")
                if (content.isBlank() || content.length > 12_000 || content.trim().startsWith("/start")) reject("invalid_input")
                val receivedAt = input["receivedAt"]?.jsonPrimitive?.doubleOrNull ?: reject("invalid_receivedAt")
                val now = System.currentTimeMillis() / 1000.0
                if (!receivedAt.isFinite() || receivedAt !in (now - 86400)..(now + 60)) reject("invalid_receivedAt")
                val request = TutorRequest("agent-$id", "agent:$messageId", content, receivedAt)
                val answer = tutor.respond(request, false)
                val state = agentState(service, id)
                call.respondText(json("response" to foundationJson.encodeToJsonElement(com.eliteteam.speakingcoach.ai.TutorResponse.serializer(), answer),
                    "state" to state).toString(), ContentType.Application.Json)
            } catch (error: FoundationFailure) {
                call.respondText(json("error" to error.code).toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
            } catch (error: SerializationException) {
                call.respondText(json("error" to "invalid_payload").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
            } catch (error: IllegalStateException) {
                val budget = error.message?.startsWith("Tutor request failed: 402") == true
                call.respondText(json("error" to if (budget) "budget_exhausted" else "tutor_unavailable").toString(),
                    ContentType.Application.Json, if (budget) HttpStatusCode(402, "Payment Required") else HttpStatusCode.BadGateway)
            }
        }
        get("/state") {
            if (call.request.local.remoteHost !in setOf("127.0.0.1", "::1")) {
                call.respondText("not found", status = HttpStatusCode.NotFound)
                return@get
            }
            if (!agentAuthorized(token, call.request.header("X-Internal-Token"))) {
                call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
                return@get
            }
            try {
                call.respondText(agentState(service, agentId(call.parameters["id"])).toString(), ContentType.Application.Json)
            } catch (error: FoundationFailure) {
                call.respondText(json("error" to error.code).toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        }
    }
}

private fun agentAuthorized(token: String, supplied: String?): Boolean = token.isNotBlank() &&
    MessageDigest.isEqual(token.toByteArray(), supplied.orEmpty().toByteArray())

private fun agentId(value: String?): String = value?.takeIf {
    runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)
} ?: reject("invalid_identity")

private suspend fun agentState(service: FoundationService, id: String): kotlinx.serialization.json.JsonObject {
    val student = service.identity.resolve("agent_test", id)
    return service.snapshot(service.identity.issue(student, "operator"), "text")
}
