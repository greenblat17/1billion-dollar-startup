package com.eliteteam.speakingcoach.app

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal fun Application.installAppPlugins(api: AppApi) {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            },
        )
    }
    install(StatusPages) {
        exception<SerializationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest)
        }
    }
    install(Authentication) {
        jwt("app-jwt") {
            verifier(api.tokens.verifier)
            validate { credential ->
                credential.payload.subject?.let { JWTPrincipal(credential.payload) }
            }
        }
    }
}
