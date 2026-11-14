package com.eliteteam.speakingcoach.foundation

import com.eliteteam.speakingcoach.RuntimeDiagnostics

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
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
        post("/commands") {
            foundationResponse(internalToken) { scope ->
                val body = call.receiveText()
                if (body.length > 64_000) reject("request_too_large")
                service.command(scope, foundationJson.parseToJsonElement(body).jsonObject)
            }
        }
        get("/snapshot") {
            foundationResponse(internalToken) { scope ->
                service.snapshot(scope, call.request.queryParameters["purpose"] ?: "text", call.request.queryParameters["lessonId"])
            }
        }
        get("/operations/{id}") { foundationResponse(internalToken) { scope -> service.receipt(scope, call.parameters["id"] ?: reject("operation_required")) } }
        get("/jobs") { foundationResponse(internalToken) { scope -> service.inspectJobs(scope) } }
        get("/diagnostics") { foundationResponse(internalToken) { scope -> service.diagnostics(scope) } }
    }
}

internal suspend fun io.ktor.server.routing.RoutingContext.foundationResponse(
    serviceToken: String,
    requireScope: Boolean = true,
    operation: suspend (String) -> kotlinx.serialization.json.JsonObject,
) {
    val supplied = call.request.header("X-Internal-Token") ?: ""
    if (!MessageDigest.isEqual(serviceToken.toByteArray(), supplied.toByteArray())) {
        call.respondText(json("ok" to false, "error" to "unauthorized").toString(), ContentType.Application.Json, HttpStatusCode.Unauthorized)
        return
    }
    val diagnostic = mapOf(
        "trace_id" to (RuntimeDiagnostics.id(call.request.header("X-Diagnostic-Trace")) ?: java.util.UUID.randomUUID().toString()),
        "lesson_id" to (RuntimeDiagnostics.id(call.request.header("X-Diagnostic-Lesson")) ?: ""),
        "call_session_id" to (RuntimeDiagnostics.id(call.request.header("X-Diagnostic-Call")) ?: ""),
    )
    RuntimeDiagnostics.withContext(diagnostic) {
    try {
        val scope = call.request.header("X-Execution-Scope") ?: if (requireScope) reject("scope_expired") else ""
        val result = operation(scope)
        call.respondText(json("ok" to true, "result" to result).toString(), ContentType.Application.Json)
    } catch (error: CancellationException) {
        throw error
    } catch (error: FoundationFailure) {
        RuntimeDiagnostics.record("foundation_failed", "command", error)
        val status = when (error.code) {
            "scope_expired", "not_permitted", "not_owned" -> HttpStatusCode.Forbidden
            "version_conflict", "operation_conflict", "stale_job", "active_booking_exists", "active_episode", "not_ready", "conversation_busy", "stale_run" -> HttpStatusCode.Conflict
            else -> HttpStatusCode.BadRequest
        }
        call.respondText(json("ok" to false, "error" to error.code).toString(), ContentType.Application.Json, status)
    } catch (error: SQLException) {
        RuntimeDiagnostics.record("foundation_failed", "command", error)
        call.respondText(json("ok" to false, "error" to "storage_unavailable").toString(), ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
    } catch (error: SerializationException) {
        RuntimeDiagnostics.record("foundation_failed", "command", error)
        call.respondText(json("ok" to false, "error" to "invalid_payload").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    } catch (error: IllegalArgumentException) {
        RuntimeDiagnostics.record("foundation_failed", "command", error)
        call.respondText(json("ok" to false, "error" to "invalid_payload").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    }
    }
}
