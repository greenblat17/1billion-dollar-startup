package com.eliteteam.speakingcoach.foundation

import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.personalizedRoadmapRoutes(service: FoundationService, token: String) {
    get("/learning-roadmap/priority") {
        foundationResponse(token) { scope ->
            if (call.request.queryParameters.names().isNotEmpty()) reject("invalid_roadmap_query")
            service.learningRoadmapPriority(scope)
        }
    }
    get("/learning-roadmap/history") {
        foundationResponse(token) { scope ->
            val query = call.request.queryParameters
            if (query.names().any { it != "offset" || query.getAll(it)?.size != 1 }) reject("invalid_roadmap_query")
            val offset = query["offset"]?.toIntOrNull() ?: 0
            service.learningRoadmapHistory(scope, offset)
        }
    }
    get("/learning-roadmap") {
        foundationResponse(token) { scope ->
            if (call.request.queryParameters.names().isNotEmpty()) reject("invalid_roadmap_query")
            service.learningRoadmap(scope)
        }
    }
}
