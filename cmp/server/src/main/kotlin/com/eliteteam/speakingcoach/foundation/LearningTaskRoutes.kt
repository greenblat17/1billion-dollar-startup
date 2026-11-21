package com.eliteteam.speakingcoach.foundation

import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.learningTaskRoutes(service: FoundationService, token: String) {
    get("/learning-tasks") {
        foundationResponse(token) { scope ->
            val query = call.request.queryParameters
            query.requireSingleQueryValues("invalid_task_query", "topicId", "problemId", "lifecycle", "offset")
            val offset = query.queryOffset()
            service.learningTasks(scope, query["topicId"], query["problemId"], query["lifecycle"], offset)
        }
    }
    get("/learning-tasks/{id}") {
        foundationResponse(token) { scope ->
            if (call.request.queryParameters.names().isNotEmpty()) reject("invalid_task_query")
            service.learningTask(scope, call.parameters["id"] ?: reject("task_required"))
        }
    }
    get("/learning-tasks/{id}/history") {
        foundationResponse(token) { scope ->
            val query = call.request.queryParameters
            query.requireSingleQueryValues("invalid_task_query", "offset")
            val offset = query.queryOffset()
            service.learningTask(scope, call.parameters["id"] ?: reject("task_required"), true, offset)
        }
    }
}
