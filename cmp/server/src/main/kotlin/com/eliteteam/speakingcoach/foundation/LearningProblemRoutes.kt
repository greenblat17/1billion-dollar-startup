package com.eliteteam.speakingcoach.foundation

import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.learningProblemRoutes(service: FoundationService, token: String) {
    get("/learning-problems") {
        foundationResponse(token) { scope ->
            val query = call.request.queryParameters
            query.requireSingleQueryValues("invalid_problem_query", "topicId", "problemId", "offset")
            val offset = query.queryOffset()
            service.learningProblems(scope, query["topicId"], query["problemId"], offset)
        }
    }
}
