package com.eliteteam.speakingcoach

import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.routing
import io.ktor.utils.io.ExperimentalKtorApi

@OptIn(ExperimentalKtorApi::class)
internal fun Application.installSpeakingCoachHttp(
    extra: Route.() -> Unit = {},
) {
    routing {
        get("/") {
            call.respondText(sayHello("Ktor"))
        }.hide()
        get("/health") {
            call.respondText("ok")
        }.hide()
        extra()
    }
}
