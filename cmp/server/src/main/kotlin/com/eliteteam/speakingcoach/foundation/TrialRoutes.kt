package com.eliteteam.speakingcoach.foundation

import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

internal fun Route.trialRoutes(service: FoundationService, internalToken: String, botToken: String) {
    post("/internal/foundation/v1/trial/access") {
        foundationResponse(internalToken, requireScope = false) {
            val body = call.receiveText()
            if (body.length > 20000) reject("request_too_large")
            TrialAccess(service).access(foundationJson.parseToJsonElement(body).jsonObject, botToken)
        }
    }
    post("/internal/foundation/v1/trial/audio_cleanup") {
        foundationResponse(internalToken, requireScope = false) {
            service.database.transaction { c, now ->
                c.update("UPDATE trial_audio SET status='purge',metadata='{}' WHERE expires_at<=? AND status NOT IN ('deleted','purge')", now)
                json("objects" to JsonArray(c.documents("SELECT to_jsonb(a) FROM trial_audio a WHERE status='purge' AND expires_at<? ORDER BY expires_at LIMIT 100", now.minusSeconds(120))))
            }
        }
    }
    post("/internal/foundation/v1/trial/audio_deleted") {
        foundationResponse(internalToken, requireScope = false) {
            val args = foundationJson.parseToJsonElement(call.receiveText()).jsonObject
            service.database.transaction { c, _ ->
                c.update("UPDATE trial_audio SET status='deleted' WHERE object_key=? AND status='purge'", args.text("objectKey"))
                json("deleted" to true)
            }
        }
    }
}
