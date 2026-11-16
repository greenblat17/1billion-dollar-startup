package com.eliteteam.speakingcoach.foundation

import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.learningObservationRoutes(service: FoundationService, token: String) {
    get("/learning-observations") {
        foundationResponse(token) { scope ->
            val q = call.request.queryParameters
            if (q.names().any { it !in setOf("lessonId", "taskId", "skillId", "topicId", "problemId", "offset") || q.getAll(it)?.size != 1 }) reject("invalid_observation_query")
            val offset = q["offset"]?.let { it.toIntOrNull() ?: reject("invalid_offset") } ?: 0
            service.learningObservations(scope, q.names().filter { it != "offset" }.associateWith { q[it]!! }, offset)
        }
    }
    get("/learning-observations/{id}") {
        foundationResponse(token) { scope ->
            if (call.request.queryParameters.names().isNotEmpty()) reject("invalid_observation_query")
            service.learningObservation(scope, call.parameters["id"] ?: reject("observation_required"))
        }
    }
    get("/learning-observations/{id}/history") {
        foundationResponse(token) { scope ->
            val q = call.request.queryParameters
            if (q.names().any { it != "offset" || q.getAll(it)?.size != 1 }) reject("invalid_observation_query")
            val offset = q["offset"]?.let { it.toIntOrNull() ?: reject("invalid_offset") } ?: 0
            service.learningObservation(scope, call.parameters["id"] ?: reject("observation_required"), true, offset)
        }
    }
}
