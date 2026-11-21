package com.eliteteam.speakingcoach.foundation

import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.learningScoreRoutes(service: FoundationService, token: String) {
    get("/learning-tasks/{id}/score") {
        foundationResponse(token) { scope ->
            if (call.request.queryParameters.names().isNotEmpty()) reject("invalid_score_query")
            service.learningScore(scope, call.parameters["id"] ?: reject("task_required"))
        }
    }
    get("/learning-tasks/{id}/score/history") {
        foundationResponse(token) { scope ->
            val query = call.request.queryParameters
            query.requireSingleQueryValues("invalid_score_query", "offset")
            val offset = query.queryOffset()
            service.learningScore(scope, call.parameters["id"] ?: reject("task_required"), history = true, offset = offset)
        }
    }
}
