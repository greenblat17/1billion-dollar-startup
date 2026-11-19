package com.eliteteam.speakingcoach.foundation

import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.learningContentRoutes(service: FoundationService, token: String) {
    get("/learning-content/{id}") {
        foundationResponse(token) { scope ->
            val query = call.request.queryParameters
            if (query.names().any { it != "revision" || query.getAll(it)?.size != 1 }) reject("invalid_content_query")
            val revision = query["revision"]?.let { it.toLongOrNull()?.takeIf { n -> n > 0 } ?: reject("invalid_content_revision") }
            service.learningContent(scope, call.parameters["id"] ?: reject("content_required"), revision)
        }
    }
    get("/learning-content/{id}/history") {
        foundationResponse(token) { scope ->
            val query = call.request.queryParameters
            if (query.names().any { it != "offset" || query.getAll(it)?.size != 1 }) reject("invalid_content_query")
            val offset = query["offset"]?.let { it.toIntOrNull() ?: reject("invalid_offset") } ?: 0
            service.learningContent(scope, call.parameters["id"] ?: reject("content_required"), history = true, offset = offset)
        }
    }
}
