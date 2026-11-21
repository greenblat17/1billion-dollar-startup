package com.eliteteam.speakingcoach.foundation

import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.learningObservationRoutes(service: FoundationService, token: String) {
    get("/learning-observations") {
        foundationResponse(token) { scope ->
            val query = call.request.queryParameters
            query.requireSingleQueryValues("invalid_observation_query", "lessonId", "taskId", "skillId", "topicId", "problemId", "offset")
            val offset = query.queryOffset()
            service.learningObservations(scope, query.names().filter { it != "offset" }.associateWith { query[it]!! }, offset)
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
            val query = call.request.queryParameters
            query.requireSingleQueryValues("invalid_observation_query", "offset")
            val offset = query.queryOffset()
            service.learningObservation(scope, call.parameters["id"] ?: reject("observation_required"), true, offset)
        }
    }
}
