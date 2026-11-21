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
            query.requireSingleQueryValues("invalid_roadmap_query", "offset")
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
