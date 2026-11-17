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
import java.time.temporal.ChronoUnit
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Opt-in DEV adapter. Its identity has no Telegram recipient, so workers cannot message a real chat. */
internal fun Route.agentTestRoutes(service: FoundationService, tutor: TutorClient, token: String) {
    route("/internal/agent-test/v1/{id}") {
        post("/mock-payment") {
            if (!agentAuthorized(token, call.request.header("X-Internal-Token"))) {
                call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
                return@post
            }
            if (!agentLoopbackPeer(call.request.local.remoteHost)) {
                call.respondText(json("error" to "invalid_test_peer").toString(), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@post
            }
            try {
                val id = agentId(call.parameters["id"])
                val body = call.receiveText()
                if (body.length > 1000) reject("request_too_large")
                val args = foundationJson.parseToJsonElement(body).jsonObject
                args.only("checkoutId")
                val student = service.identity.resolve("agent_test", id)
                val billing = MockPaymentAdapter(service).agentCallback(student, agentId(args.text("checkoutId")))
                call.respondText(json("billing" to billing).toString(), ContentType.Application.Json)
            } catch (error: FoundationFailure) {
                call.respondText(json("error" to error.code).toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
            } catch (error: SerializationException) {
                call.respondText(json("error" to "invalid_payload").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        }
        get("/voice-state") {
            if (!agentAuthorized(token, call.request.header("X-Internal-Token"))) {
                call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!agentLoopbackPeer(call.request.local.remoteHost)) {
                call.respondText(json("error" to "invalid_test_peer").toString(), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }
            try {
                val id = agentId(call.parameters["id"])
                val lessonId = agentId(call.request.queryParameters["lessonId"])
                val student = service.identity.resolve("agent_test", id)
                val state = service.snapshot(service.identity.issue(student, "operator"), "voice", lessonId)
                val audio = service.database.transaction(readOnly = true) { c, _ ->
                    JsonArray(c.documents("SELECT to_jsonb(a) FROM trial_audio a WHERE student_id=? AND lesson_id=? AND status='stored' ORDER BY object_key LIMIT 500", student, lessonId))
                }
                call.respondText(json("state" to state, "audio" to audio).toString(), ContentType.Application.Json)
            } catch (error: FoundationFailure) {
                call.respondText(json("error" to error.code).toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        }
        post("/voice-grant") {
            if (!agentAuthorized(token, call.request.header("X-Internal-Token"))) {
                call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
                return@post
            }
            if (!agentLoopbackPeer(call.request.local.remoteHost)) {
                call.respondText(json("error" to "invalid_test_peer").toString(), ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@post
            }
            try {
                val id = agentId(call.parameters["id"])
                val body = call.receiveText()
                if (body.length > 1000) reject("request_too_large")
                val args = foundationJson.parseToJsonElement(body).jsonObject
                args.only("lessonId")
                val lessonId = agentId(args.text("lessonId"))
                val student = service.identity.resolve("agent_test", id)
                val grant = service.database.transaction { c, now ->
                    val lesson = c.document("SELECT to_jsonb(l) FROM lesson l WHERE student_id=? AND id=? AND kind IN ('trial','regular')", student, lessonId)
                        ?: reject("not_owned")
                    if (lesson.text("launch_mode") != "executable" || lesson.text("status") !in setOf("scheduled", "in_progress")) reject("lesson_unavailable")
                    val value = newId() + newId()
                    val epoch = c.rows("SELECT data_epoch FROM student WHERE id=? AND deleted_at IS NULL", student) { it.getLong(1) }.singleOrNull() ?: reject("not_owned")
                    c.update("INSERT INTO execution_scope(token_digest,student_id,role,lesson_id,data_epoch,expires_at) VALUES (?,?,'agent_voice_grant',?,?,?)",
                        digest(value), student, lessonId, epoch, now.plus(60, ChronoUnit.SECONDS))
                    json("grant" to value, "expiresAt" to now.plusSeconds(60).toString(), "lessonId" to lessonId)
                }
                call.respondText(grant.toString(), ContentType.Application.Json)
            } catch (error: FoundationFailure) {
                call.respondText(json("error" to error.code).toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
            } catch (error: SerializationException) {
                call.respondText(json("error" to "invalid_payload").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        }
        post("/turn") {
            if (!agentAuthorized(token, call.request.header("X-Internal-Token"))) {
                call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
                return@post
            }
            if (!agentLoopbackPeer(call.request.local.remoteHost)) {
                call.respondText(json("error" to "invalid_test_peer",
                    "peer" to call.request.local.remoteHost).toString(), ContentType.Application.Json, HttpStatusCode.Forbidden)
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
            if (!agentAuthorized(token, call.request.header("X-Internal-Token"))) {
                call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
                return@get
            }
            if (!agentLoopbackPeer(call.request.local.remoteHost)) {
                call.respondText(json("error" to "invalid_test_peer",
                    "peer" to call.request.local.remoteHost).toString(), ContentType.Application.Json, HttpStatusCode.Forbidden)
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

private fun agentLoopbackPeer(host: String): Boolean = runCatching {
    InetAddress.getByName(host).isLoopbackAddress
}.getOrDefault(false)

private fun agentId(value: String?): String = value?.takeIf {
    runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)
} ?: reject("invalid_identity")

private suspend fun agentState(service: FoundationService, id: String): kotlinx.serialization.json.JsonObject {
    val student = service.identity.resolve("agent_test", id)
    return service.snapshot(service.identity.issue(student, "operator"), "text")
}
