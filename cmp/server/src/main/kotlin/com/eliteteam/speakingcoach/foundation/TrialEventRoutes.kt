package com.eliteteam.speakingcoach.foundation

import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.trialEventRoutes(service: FoundationService, token: String) {
    get("/trial/events") {
        foundationResponse(token) { tokenScope ->
            val query = call.request.queryParameters
            query.requireSingleQueryValues("invalid_query", "lessonId", "offset")
            val args = json("lessonId" to (query["lessonId"] ?: reject("lesson_required")),
                "offset" to query.queryOffset())
            service.database.transaction(readOnly = true) { c, now ->
                TrialCallEvents().read(c, authenticateScope(c, tokenScope, now), args, now)
            }
        }
    }
    get("/trial/audio") {
        foundationResponse(token) { tokenScope ->
            val query = call.request.queryParameters
            query.requireSingleQueryValues("invalid_query", "lessonId", "cursor")
            val args = json("lessonId" to (query["lessonId"] ?: reject("lesson_required")), "cursor" to (query["cursor"] ?: ""))
            service.database.transaction(readOnly = true) { c, now ->
                TrialCallEvents().audio(c, authenticateScope(c, tokenScope, now), args, now)
            }
        }
    }
}
