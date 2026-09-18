package com.eliteteam.speakingcoach.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.serialization.Serializable

private val AudioOgg = ContentType.parse("audio/ogg")

@OptIn(ExperimentalKtorApi::class)
internal fun Route.installAiServiceContract(baseUrl: String, http: HttpClient) {
    val root = baseUrl.trimEnd('/')
    post("/v1/sessions") {
        call.proxyTo(http, "$root/v1/sessions")
    }.describe {
        operationId = "createSession"
        summary = "Start a speaking session"
        tag("sessions")
        responses {
            HttpStatusCode.Created {
                description = "Session id and greeting text"
                schema = jsonSchema<SessionCreatedResponse>()
            }
        }
    }
    get("/v1/sessions/{sessionId}/greeting/audio") {
        call.proxyTo(http, "$root/v1/sessions/${call.parameters["sessionId"]}/greeting/audio")
    }.describe {
        operationId = "getGreetingAudio"
        summary = "Welcome voice for a session"
        tag("sessions")
        responses {
            HttpStatusCode.OK {
                description = "Welcome voice"
                AudioOgg()
            }
            HttpStatusCode.NotFound {
                description = "Unknown session"
            }
        }
    }
    post("/v1/clips") {
        call.proxyTo(http, "$root/v1/clips")
    }.describe {
        operationId = "createClip"
        summary = "Submit a user voice clip"
        tag("clips")
        requestBody {
            required = true
            ContentType.MultiPart.FormData {
                schema = jsonSchema<ClipUploadParts>()
            }
        }
        responses {
            HttpStatusCode.Accepted {
                description = "Accepted job"
                schema = jsonSchema<ClipAcceptedResponse>()
            }
            HttpStatusCode.BadRequest {
                description = "Missing sessionId or audio"
            }
            HttpStatusCode.NotFound {
                description = "Unknown session"
            }
        }
    }
    get("/v1/clips/{jobId}") {
        call.proxyTo(http, "$root/v1/clips/${call.parameters["jobId"]}")
    }.describe {
        operationId = "getClip"
        summary = "Clip job status"
        description = "When status is ok, result includes the transcript and correction pairs."
        tag("clips")
        responses {
            HttpStatusCode.OK {
                description = "Job status and notes when ready"
                schema = jsonSchema<ClipStatusResponse>()
            }
            HttpStatusCode.NotFound {
                description = "Unknown job"
            }
        }
    }
    get("/v1/clips/{jobId}/audio") {
        call.proxyTo(http, "$root/v1/clips/${call.parameters["jobId"]}/audio")
    }.describe {
        operationId = "getClipAudio"
        summary = "Spoken dialogue continuation"
        tag("clips")
        responses {
            HttpStatusCode.OK {
                description = "Reply voice"
                AudioOgg()
            }
            HttpStatusCode.NotFound {
                description = "Job missing or not ready"
            }
        }
    }
}

@Serializable
private class ClipUploadParts(
    val sessionId: String,
    val audio: ByteArray,
)

private suspend fun ApplicationCall.proxyTo(http: HttpClient, url: String) {
    val incomingMethod = request.httpMethod
    val incomingType = request.contentType()
    val response = http.request(url) {
        method = incomingMethod
        if (incomingMethod != HttpMethod.Get && incomingMethod != HttpMethod.Head) {
            contentType(incomingType)
            setBody(receiveChannel())
        }
    }
    respondBytes(response.bodyAsBytes(), response.contentType(), response.status)
}
