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
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.time.Instant
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
        post("/regular-entitlement") {
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
                val student = service.identity.resolve("agent_test", id)
                val result = service.database.transaction { c, now ->
                    c.lockStudent(student)
                    val identities = c.rows("SELECT provider, subject FROM student_identity WHERE student_id=?", student) {
                        it.getString(1) to it.getString(2)
                    }
                    if (!agentTestOnlyIdentity(identities, id)) reject("not_owned")
                    val existing = c.activeEntitlement(student, now)
                    if (existing != null) json("status" to "already_active", "entitlementId" to existing.text("id"),
                        "validUntil" to existing.text("valid_until"))
                    else {
                        val epoch = c.rows("SELECT data_epoch FROM student WHERE id=? AND deleted_at IS NULL", student) { it.getLong(1) }
                            .singleOrNull() ?: reject("not_owned")
                        val subscription = newId()
                        val receipt = "agent-test:${newId()}"
                        val validUntil = now.plus(Duration.ofDays(2))
                        c.update("INSERT INTO subscription(id,student_id,status,provider,valid_from,valid_until,auto_renew) VALUES (?,?,'active','agent_test',?,?,FALSE)",
                            subscription, student, now, validUntil)
                        c.update("INSERT INTO payment_receipt(provider,external_id,student_id,subscription_id,input_digest,confirmed_at) VALUES ('agent_test',?,?,?,?,?)",
                            receipt, student, subscription, digest("$receipt:$epoch"), now)
                        val entitlement = newId()
                        c.update("INSERT INTO entitlement(id,student_id,subscription_id,valid_from,valid_until,source_provider,source_external_id) VALUES (?,?,?,?,?,'agent_test',?)",
                            entitlement, student, subscription, now, validUntil, receipt)
                        json("status" to "created", "entitlementId" to entitlement, "validUntil" to validUntil.toString())
                    }
                }
                call.respondText(result.toString(), ContentType.Application.Json)
            } catch (error: FoundationFailure) {
                call.respondText(json("error" to error.code).toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        }
        /** Synthetic Foundation contract probe. Scope stays inside Ktor and the route stays on DEV loopback. */
        post("/foundation-check") {
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
                if (body.length > 64_000) reject("request_too_large")
                val input = foundationJson.parseToJsonElement(body).jsonObject
                input.only("kind", "operationId", "action", "args", "resource", "resourceId", "history", "revision", "offset", "occurredAt")
                val student = service.identity.resolve("agent_test", id)
                requireAgentTestOnlyStudent(service, student, id)
                val scope = service.identity.issue(student, "operator")
                val result = when (input.text("kind")) {
                    "catalog" -> {
                        val filters = input.obj("args")
                        filters.only("version", "skill", "cefr", "topicId")
                        LearningCatalog(service.database).read(filters.optionalText("version"), filters.optionalText("skill"),
                            filters.optionalText("cefr"), filters.optionalText("topicId"))
                    }
                    "source" -> {
                        val requestId = agentId(input.text("operationId"))
                        val at = Instant.parse(input.text("occurredAt"))
                        if (at.isBefore(Instant.now().minusSeconds(86400)) || at.isAfter(Instant.now().plusSeconds(60))) reject("invalid_receivedAt")
                        val event = appendAgentFixtureSource(service, student, requestId, at)
                        json("sourceEventId" to event.text("id"), "sourceSequence" to event.number("sequence"),
                            "sources" to JsonArray(listOf(json("type" to "event", "id" to event.text("id"),
                                "revision" to event.number("sequence")))))
                    }
                    "command" -> {
                        val action = input.text("action")
                        if (action !in setOf("lesson.list", "learning.update", "learning.topic.record", "learning.task.create", "learning.task.update",
                                "learning.task.transition", "learning.task.progress",
                                "learning.roadmap.save", "learning.roadmap.priority.save",
                                "learning.plan.create", "learning.plan.revise", "learning.plan.select-stage", "learning.content.create",
                                "learning.content.revise", "learning.content.issue", "learning.content.mark-defective",
                                "learning.observation.record", "learning.observation.revise", "learning.observation.invalidate",
                                "learning.score.fixture")) reject("not_permitted")
                        service.command(scope, json("version" to 1, "operationId" to agentId(input.text("operationId")),
                            "action" to action, "args" to input.obj("args")))
                    }
                    "read" -> {
                        val resource = input.text("resource")
                        val resourceId = input.optionalText("resourceId")?.let(::agentId)
                        val history = input["history"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                        val offset = input["offset"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        if (offset !in 0..10000) reject("invalid_offset")
                        when (resource) {
                            "state" -> service.learningState(scope)
                            "tasks" -> service.learningTasks(scope, null, null, null, offset)
                            "task" -> service.learningTask(scope, resourceId ?: reject("invalid_identity"), history, offset)
                            "plan" -> service.learningPlan(scope, resourceId ?: reject("invalid_identity"),
                                input["revision"]?.jsonPrimitive?.content?.toLongOrNull(), history, offset)
                            "content" -> service.learningContent(scope, resourceId ?: reject("invalid_identity"),
                                input["revision"]?.jsonPrimitive?.content?.toLongOrNull(), history, offset)
                            "observations" -> service.learningObservations(scope, offset = offset)
                            "observation" -> service.learningObservation(scope, resourceId ?: reject("invalid_identity"), history, offset)
                            "score" -> service.learningScore(scope, resourceId ?: reject("invalid_identity"), history, offset)
                            "roadmap", "priority" -> try {
                                if (resource == "roadmap") service.learningRoadmap(scope) else service.learningRoadmapPriority(scope)
                            } catch (error: FoundationFailure) {
                                if (error.code != "roadmap_not_found") throw error
                                json("schemaVersion" to 1, "status" to "roadmap_not_found")
                            }
                            "jobs" -> service.database.transaction(readOnly = true) { c, now ->
                                val authorized = authenticateScope(c, scope, now)
                                json("schemaVersion" to 1, "jobs" to JsonArray(c.documents("""SELECT jsonb_build_object(
                                    'id',id,'kind',kind,'status',status,'targetId',target_id,'errorCode',error_code)
                                    FROM foundation_job WHERE student_id=? AND data_epoch=?
                                    ORDER BY created_at DESC,id DESC LIMIT 100""",
                                    authorized.studentId, authorized.epoch)))
                            }
                            "inbox" -> service.database.transaction(readOnly = true) { c, now ->
                                val authorized = authenticateScope(c, scope, now)
                                json("schemaVersion" to 1, "inbox" to JsonArray(c.documents("""SELECT jsonb_build_object(
                                    'requestId',i.request_id,'eventId',i.event_id,'status',i.status,
                                    'leaseUntil',i.lease_until,'expiresAt',i.expires_at,'sequence',e.sequence)
                                    FROM tutor_inbox i JOIN conversation_event e ON e.student_id=i.student_id AND e.id=i.event_id
                                    WHERE i.student_id=? ORDER BY e.sequence DESC,i.request_id LIMIT 100 OFFSET ?""",
                                    authorized.studentId, offset)))
                            }
                            "contents" -> service.database.transaction(readOnly = true) { c, now ->
                                val authorized = authenticateScope(c, scope, now)
                                json("schemaVersion" to 1, "contents" to JsonArray(c.documents("""SELECT jsonb_build_object(
                                    'contentId',content.id,'taskId',content.task_id,'revision',revision.revision,
                                    'status',revision.status,'stageRef',revision.document->'stageRef',
                                    'issuedLessonId',revision.issued_lesson_id)
                                    FROM learning_content content JOIN learning_content_revision revision
                                      ON revision.content_id=content.id AND revision.revision=content.current_revision
                                    WHERE content.student_id=? ORDER BY content.updated_at DESC,content.id DESC LIMIT 100""",
                                    authorized.studentId)))
                            }
                            "receipt" -> service.receipt(scope, resourceId ?: reject("invalid_identity"))
                            else -> reject("invalid_resource")
                        }
                    }
                    else -> reject("invalid_probe_kind")
                }
                call.respondText(json("result" to result).toString(), ContentType.Application.Json)
            } catch (error: FoundationFailure) {
                call.respondText(json("error" to error.code).toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
            } catch (error: SerializationException) {
                call.respondText(json("error" to "invalid_payload").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
            } catch (error: java.time.format.DateTimeParseException) {
                call.respondText(json("error" to "invalid_receivedAt").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        }
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
                val scopeToken = service.identity.issue(student, "operator")
                val state = service.snapshot(scopeToken, "voice", lessonId)
                val lesson = state.obj("lesson")
                val regular = lesson.text("kind") == "regular"
                val evidence = service.database.transaction(readOnly = true) { c, now ->
                    val scope = authenticateScope(c, scopeToken, now)
                    val events = when {
                        regular -> json("schemaVersion" to 1, "lesson" to lesson, "execution" to null,
                            "events" to JsonArray(emptyList()), "nextOffset" to null,
                            "assessmentStatus" to "not_started", "observationStatus" to "not_started")
                        lesson.text("kind") == "trial" && trialV55(lesson) ->
                            TrialCallEvents().read(c, scope, json("lessonId" to lessonId, "offset" to 0), now)
                        else -> reject("not_v55_trial")
                    }
                    val audio = JsonArray(c.documents("SELECT to_jsonb(a) FROM trial_audio a WHERE student_id=? AND lesson_id=? AND status='stored' ORDER BY object_key LIMIT 500", student, lessonId))
                    val jobs = JsonArray(c.documents("""SELECT jsonb_build_object('id',id,'kind',kind,'status',status,'inputRefs',input_refs,'result',result)
                        FROM foundation_job WHERE student_id=? AND target_id=?
                        AND ((? AND kind IN ('lesson.prepare','lesson.content.prepare','lesson.evaluate','lesson.review'))
                          OR (NOT ? AND kind IN ('trial.observe','trial.assess','roadmap.generate')))
                        ORDER BY created_at,id LIMIT 30""", student, lessonId, regular, regular))
                    json("trialEvents" to events, "audio" to audio, "jobs" to jobs)
                }
                call.respondText(json("state" to state, "trialEvents" to evidence["trialEvents"],
                    "audio" to evidence["audio"], "jobs" to evidence["jobs"]).toString(), ContentType.Application.Json)
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

internal fun agentTestOnlyIdentity(identities: List<Pair<String, String>>, subject: String): Boolean =
    identities.size == 1 && identities.single() == ("agent_test" to subject)

internal suspend fun appendAgentFixtureSource(service: FoundationService, student: String, requestId: String, at: Instant) =
    service.database.transaction { c, now ->
        c.lockStudent(student)
        val conversation = c.conversation(student, "telegram")
        c.appendEvent(student, conversation, "foundation-check:$requestId", "user", "Synthetic foundation acceptance event", at, now)
    }

internal suspend fun requireAgentTestOnlyStudent(service: FoundationService, student: String, subject: String) {
    val identities = service.database.transaction(readOnly = true) { c, _ ->
        c.rows("SELECT provider, subject FROM student_identity WHERE student_id=?", student) {
            it.getString(1) to it.getString(2)
        }
    }
    if (!agentTestOnlyIdentity(identities, subject)) reject("not_owned")
}

private suspend fun agentState(service: FoundationService, id: String): kotlinx.serialization.json.JsonObject {
    val student = service.identity.resolve("agent_test", id)
    return service.snapshot(service.identity.issue(student, "operator"), "text")
}
