package com.eliteteam.speakingcoach.foundation

import com.eliteteam.speakingcoach.RuntimeDiagnostics

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import java.security.MessageDigest
import java.sql.SQLException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject

internal fun Application.installFoundation(databaseUrl: String?, internalToken: String): FoundationService? {
    if (databaseUrl.isNullOrBlank()) return null
    require(internalToken.isNotBlank()) { "AI_INTERNAL_TOKEN required for foundation" }
    val service = FoundationService(FoundationDatabase(databaseUrl), BillingConfig.fromEnvironment())
    val imported = kotlinx.coroutines.runBlocking {
        service.database.transaction(readOnly = true) { connection, _ ->
            connection.document("SELECT to_jsonb(c) FROM foundation_cutover c WHERE singleton=TRUE") != null
        }
    }
    if (!imported) {
        service.close()
        error("Foundation cutover is not complete; import and validate frozen source data before enabling the new tutor")
    }
    monitor.subscribe(ApplicationStopped) { service.close() }
    routing { foundationRoutes(service, internalToken) }
    return service
}

internal fun Route.foundationRoutes(service: FoundationService, internalToken: String) {
    route("/internal/foundation/v1") {
        get("/catalog") {
            // Global published curriculum, no learner data; the service token is sufficient for this read.
            foundationResponse(internalToken, requireScope = false) {
                val query = call.request.queryParameters
                query.requireSingleQueryValues("invalid_catalog_query", "version", "skill", "cefr", "topicId")
                LearningCatalog(service.database).read(query["version"], query["skill"], query["cefr"], query["topicId"])
            }
        }
        post("/commands") {
            foundationResponse(internalToken) { scope ->
                val body = call.receiveText()
                if (body.length > 64_000) reject("request_too_large")
                service.command(scope, foundationJson.parseToJsonElement(body).jsonObject)
            }
        }
        learningTaskRoutes(service, internalToken)
        personalizedRoadmapRoutes(service, internalToken)
        trialEventRoutes(service, internalToken)
        learningObservationRoutes(service, internalToken)
        learningProblemRoutes(service, internalToken)
        learningPlanRoutes(service, internalToken)
        learningScoreRoutes(service, internalToken)
        learningContentRoutes(service, internalToken)
        get("/learning-state") {
            foundationResponse(internalToken) { scope ->
                if (call.request.queryParameters.names().isNotEmpty()) reject("invalid_learning_query")
                service.learningState(scope)
            }
        }
        get("/learning-state/topics") {
            foundationResponse(internalToken) { scope ->
                val query = call.request.queryParameters
                query.requireSingleQueryValues("invalid_learning_query", "catalogVersion", "topicId", "offset", "history")
                val offset = query.queryOffset()
                val history = query["history"]?.let { it.toBooleanStrictOrNull() ?: reject("invalid_history") } ?: false
                service.topicKnowledge(scope, query["catalogVersion"] ?: reject("catalog_version_required"), query["topicId"], offset, history)
            }
        }
        get("/snapshot") {
            foundationResponse(internalToken) { scope ->
                service.snapshot(scope, call.request.queryParameters["purpose"] ?: "text", call.request.queryParameters["lessonId"]).also { snapshot ->
                val review = snapshot["postLessonReview"] as? kotlinx.serialization.json.JsonObject
                RuntimeDiagnostics.ingressId(review?.optionalText("lesson_id"))?.let { lesson ->
                    RuntimeDiagnostics.record("post_lesson_review", "handoff", extra = mapOf(
                        "operation" to "lesson.review", "lesson_id" to lesson))
                }
            }
            }
        }
        get("/operations/{id}") { foundationResponse(internalToken) { scope -> service.receipt(scope, call.parameters["id"] ?: reject("operation_required")) } }
        get("/jobs") { foundationResponse(internalToken) { scope -> service.inspectJobs(scope) } }
        get("/diagnostics") { foundationResponse(internalToken) { scope -> service.diagnostics(scope) } }
    }
}

internal fun Parameters.requireSingleQueryValues(error: String, vararg allowed: String) {
    if (names().any { it !in allowed || getAll(it)?.size != 1 }) reject(error)
}

internal fun Parameters.queryOffset(): Int =
    this["offset"]?.let { it.toIntOrNull() ?: reject("invalid_offset") } ?: 0

internal fun Parameters.queryRevision(error: String): Long? =
    this["revision"]?.let { it.toLongOrNull()?.takeIf { revision -> revision > 0 } ?: reject(error) }

private val foundationRouteName = AttributeKey<String>("SpeakyFoundationRouteName")

private fun diagnosticRouteName(route: RoutingNode): String = route.attributes.computeIfAbsent(foundationRouteName) {
    generateSequence(route) { it.parent }
        .mapNotNull { node ->
            when (val segment = node.selector) {
                is PathSegmentConstantRouteSelector -> segment.value
                is PathSegmentParameterRouteSelector -> "param_${segment.name}"
                else -> null
            }
        }
        .toList().asReversed().joinToString(".")
}

internal suspend fun io.ktor.server.routing.RoutingContext.foundationResponse(
    serviceToken: String,
    requireScope: Boolean = true,
    operation: suspend (String) -> kotlinx.serialization.json.JsonObject,
) {
    val supplied = call.request.header("X-Internal-Token").orEmpty()
    val authorized = MessageDigest.isEqual(serviceToken.toByteArray(), supplied.toByteArray())
    val diagnostic = mutableMapOf("trace_id" to RuntimeDiagnostics.newId(), "request_id" to RuntimeDiagnostics.newId(),
        "route_name" to diagnosticRouteName(call.route))
    if (authorized) {
        for ((key, header) in mapOf("trace_id" to "Trace", "lesson_id" to "Lesson", "call_session_id" to "Call",
            "job_id" to "Job", "notice_id" to "Notice")) {
            RuntimeDiagnostics.ingressId(call.request.header("X-Diagnostic-$header"))?.let { diagnostic[key] = it }
        }
        for ((key, header) in mapOf("attempt" to "Attempt", "fence" to "Fence")) {
            call.request.header("X-Diagnostic-$header")?.toLongOrNull()?.takeIf { it in 1L until 1_000_000_000_000L }
                ?.let { diagnostic[key] = it.toString() }
        }
    }
    val operationName = when (call.request.path()) {
        "/internal/foundation/v1/commands" -> "foundation_command"
        "/internal/foundation/v1/trial/access" -> "trial_access"
        "/internal/foundation/v1/trial/audio_cleanup", "/internal/foundation/v1/trial/audio_deleted" -> "trial_audio_retention"
        else -> "foundation_read"
    }
    val started = System.nanoTime()
    call.response.header("X-Diagnostic-Trace", diagnostic.getValue("trace_id"))
    call.response.header("X-Request-ID", diagnostic.getValue("request_id"))
    RuntimeDiagnostics.withContext(diagnostic) {
        suspend fun rejectResponse(stage: String, code: String, status: HttpStatusCode, error: Exception? = null) {
            val errorId = RuntimeDiagnostics.newId()
            val recorded = RuntimeDiagnostics.record("foundation_failed", stage, error, mapOf(
                "operation" to operationName, "reason_code" to code, "error_id" to errorId,
                "http_status" to status.value.toString(), "duration_ms" to RuntimeDiagnostics.elapsed(started),
                "outcome" to if (status.value < 500) "rejected" else "failed"))
            call.response.header("X-Error-ID", recorded ?: errorId)
            call.respondText(json("ok" to false, "error" to code).toString(), ContentType.Application.Json, status)
        }

        // The command handler logs its own boundaries and aggregates routine audio traffic.
        if (!authorized) {
            rejectResponse("authorize", "unauthorized", HttpStatusCode.Unauthorized)
            return@withContext
        }
        try {
            val scope = call.request.header("X-Execution-Scope") ?: if (requireScope) reject("scope_expired") else ""
            val result = operation(scope)
            call.respondText(json("ok" to true, "result" to result).toString(), ContentType.Application.Json)
            if (operationName != "foundation_command") RuntimeDiagnostics.record("http_request", "response", extra = mapOf("operation" to operationName,
                "http_status" to "200", "duration_ms" to RuntimeDiagnostics.elapsed(started)))
        } catch (error: CancellationException) {
            RuntimeDiagnostics.record("http_request", "handler", extra = mapOf("operation" to operationName, "outcome" to "cancelled"))
            throw error
        } catch (error: FoundationFailure) {
            val status = when (error.code) {
                "scope_expired", "not_permitted", "not_owned" -> HttpStatusCode.Forbidden
                "version_conflict", "operation_conflict", "plan_already_exists", "stale_job", "active_booking_exists", "active_episode", "not_ready", "conversation_busy", "stale_run" -> HttpStatusCode.Conflict
                else -> HttpStatusCode.BadRequest
            }
            rejectResponse(if (status == HttpStatusCode.Forbidden || error.code == "invalid_telegram_auth") "authorize" else "validate", error.code, status, error)
        } catch (error: SQLException) {
            rejectResponse(if (operationName == "foundation_command") "response" else "persist",
                "storage_unavailable", HttpStatusCode.ServiceUnavailable, error)
        } catch (error: SerializationException) {
            rejectResponse("validate", "invalid_payload", HttpStatusCode.BadRequest, error)
        } catch (error: IllegalArgumentException) {
            rejectResponse("validate", "invalid_payload", HttpStatusCode.BadRequest, error)
        } catch (error: Exception) {
            rejectResponse("handler", "internal_error", HttpStatusCode.InternalServerError, error)
        }
    }
}
