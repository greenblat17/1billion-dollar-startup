package com.eliteteam.speakingcoach.foundation

import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.post
import io.ktor.server.request.receiveText
import kotlinx.serialization.json.jsonObject

// Transitional adapters retained for their tests; deliberately absent from the shipping runtime.
internal fun Route.legacyFoundationRoutes(service: FoundationService, internalToken: String) {
    route("/internal/foundation/v1") {
        post("/legacy/memory-queue") {
            foundationResponse(internalToken, requireScope = false) {
                val body = call.receiveText()
                if (body.length > 64_000) reject("request_too_large")
                LegacyMemoryQueue(service.database).command(foundationJson.parseToJsonElement(body).jsonObject)
            }
        }
        post("/legacy/feedback") {
            foundationResponse(internalToken, requireScope = false) {
                val body = call.receiveText()
                if (body.length > 4000) reject("request_too_large")
                LegacyFeedback(service.database).command(foundationJson.parseToJsonElement(body).jsonObject)
            }
        }
        post("/legacy/dialogue") {
            foundationResponse(internalToken, requireScope = false) {
                val body = call.receiveText()
                if (body.length > 256_000) reject("request_too_large")
                LegacyDialogue(service.database).command(foundationJson.parseToJsonElement(body).jsonObject)
            }
        }
        post("/legacy/voice-cost") {
            foundationResponse(internalToken, requireScope = false) {
                val body = call.receiveText()
                if (body.length > 64_000) reject("request_too_large")
                LegacyVoiceCost(service.database).command(foundationJson.parseToJsonElement(body).jsonObject)
            }
        }
        post("/legacy/calls") {
            foundationResponse(internalToken, requireScope = false) {
                val body = call.receiveText()
                if (body.length > 1_000_000) reject("request_too_large")
                LegacyCalls(service.database).command(foundationJson.parseToJsonElement(body).jsonObject)
            }
        }
        post("/legacy/preferences") {
            foundationResponse(internalToken, requireScope = false) {
                val body = call.receiveText()
                if (body.length > 4000) reject("request_too_large")
                LegacyVoiceStorage(service.database).preferences(foundationJson.parseToJsonElement(body).jsonObject)
            }
        }
        post("/legacy/artifacts") {
            foundationResponse(internalToken, requireScope = false) {
                val body = call.receiveText()
                if (body.length > 128_000) reject("request_too_large")
                LegacyVoiceStorage(service.database).artifact(foundationJson.parseToJsonElement(body).jsonObject)
            }
        }
        post("/legacy/learners") {
            foundationResponse(internalToken, requireScope = false) {
                val body = call.receiveText()
                if (body.length > 256_000) reject("request_too_large")
                LegacyLearners(service.database).command(foundationJson.parseToJsonElement(body).jsonObject)
            }
        }
    }
}
