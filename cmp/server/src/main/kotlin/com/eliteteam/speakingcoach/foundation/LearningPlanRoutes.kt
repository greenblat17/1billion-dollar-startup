package com.eliteteam.speakingcoach.foundation

import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.learningPlanRoutes(service: FoundationService, token: String) {
    get("/learning-plans/{id}") {
        foundationResponse(token) { scope ->
            val query = call.request.queryParameters
            query.requireSingleQueryValues("invalid_plan_query", "revision")
            val revision = query.queryRevision("invalid_plan_revision")
            service.learningPlan(scope, call.parameters["id"] ?: reject("plan_required"), revision)
        }
    }
    get("/learning-plans/{id}/history") {
        foundationResponse(token) { scope ->
            val query = call.request.queryParameters
            query.requireSingleQueryValues("invalid_plan_query", "offset")
            val offset = query.queryOffset()
            service.learningPlan(scope, call.parameters["id"] ?: reject("plan_required"), history = true, offset = offset)
        }
    }
    get("/learning-plans/{id}/stages/{stageId}") {
        foundationResponse(token) { scope ->
            val query = call.request.queryParameters
            query.requireSingleQueryValues("invalid_plan_query", "taskId", "revision")
            val revision = query.queryRevision("invalid_plan_revision") ?: reject("invalid_plan_revision")
            service.learningStage(scope, query["taskId"] ?: reject("task_required"),
                call.parameters["id"] ?: reject("plan_required"), revision,
                call.parameters["stageId"] ?: reject("stage_required"))
        }
    }
}
