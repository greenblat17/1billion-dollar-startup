package com.eliteteam.speakingcoach.foundation

import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.learningPlanRoutes(service: FoundationService, token: String) {
    get("/learning-plans/{id}") {
        foundationResponse(token) { scope ->
            val query = call.request.queryParameters
            if (query.names().any { it != "revision" || query.getAll(it)?.size != 1 }) reject("invalid_plan_query")
            val revision = query["revision"]?.let { it.toLongOrNull()?.takeIf { n -> n > 0 } ?: reject("invalid_plan_revision") }
            service.learningPlan(scope, call.parameters["id"] ?: reject("plan_required"), revision)
        }
    }
    get("/learning-plans/{id}/history") {
        foundationResponse(token) { scope ->
            val query = call.request.queryParameters
            if (query.names().any { it != "offset" || query.getAll(it)?.size != 1 }) reject("invalid_plan_query")
            val offset = query["offset"]?.let { it.toIntOrNull() ?: reject("invalid_offset") } ?: 0
            service.learningPlan(scope, call.parameters["id"] ?: reject("plan_required"), history = true, offset = offset)
        }
    }
    get("/learning-plans/{id}/stages/{stageId}") {
        foundationResponse(token) { scope ->
            val query = call.request.queryParameters
            if (query.names().any { it !in setOf("taskId", "revision") || query.getAll(it)?.size != 1 }) reject("invalid_plan_query")
            val revision = query["revision"]?.toLongOrNull()?.takeIf { it > 0 } ?: reject("invalid_plan_revision")
            service.learningStage(scope, query["taskId"] ?: reject("task_required"),
                call.parameters["id"] ?: reject("plan_required"), revision,
                call.parameters["stageId"] ?: reject("stage_required"))
        }
    }
}
