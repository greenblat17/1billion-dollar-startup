package com.eliteteam.speakingcoach.foundation

import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.learningProblemRoutes(service: FoundationService, token: String) {
    get("/learning-problems") {
        foundationResponse(token) { scope ->
            val q = call.request.queryParameters
            if (q.names().any { it !in setOf("topicId", "problemId", "offset") || q.getAll(it)?.size != 1 })
                reject("invalid_problem_query")
            val offset = q["offset"]?.toIntOrNull() ?: if (q["offset"] == null) 0 else reject("invalid_offset")
            service.learningProblems(scope, q["topicId"], q["problemId"], offset)
        }
    }
}
