package com.eliteteam.speakingcoach.foundation

import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.learningContentRoutes(service: FoundationService, token: String) {
    get("/learning-content/{id}") {
        foundationResponse(token) { scope ->
            val query = call.request.queryParameters
            query.requireSingleQueryValues("invalid_content_query", "revision")
            val revision = query.queryRevision("invalid_content_revision")
            service.learningContent(scope, call.parameters["id"] ?: reject("content_required"), revision)
        }
    }
    get("/learning-content/{id}/history") {
        foundationResponse(token) { scope ->
            val query = call.request.queryParameters
            query.requireSingleQueryValues("invalid_content_query", "offset")
            val offset = query.queryOffset()
            service.learningContent(scope, call.parameters["id"] ?: reject("content_required"), history = true, offset = offset)
        }
    }
}
