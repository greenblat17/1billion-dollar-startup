package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.ai.installAiServiceContract
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.openapi.OpenApiInfo
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.routing
import io.ktor.utils.io.ExperimentalKtorApi

@OptIn(ExperimentalKtorApi::class)
internal fun Application.installSpeakingCoachHttp(
    aiServiceBaseUrl: String = "http://127.0.0.1:8090",
    extra: Route.() -> Unit = {},
) {
    val aiHttp = HttpClient(CIO) { expectSuccess = false }
    monitor.subscribe(ApplicationStopped) { aiHttp.close() }
    routing {
        swaggerUI("/swagger") {
            info = OpenApiInfo(
                title = "AI service",
                version = "1.0",
                description = "Clip and session contract used by the speaking-coach bot. Name is not part of the API.",
            )
            source = OpenApiDocSource.Routing(ContentType.Application.Yaml)
        }
        get("/") {
            call.respondText(sayHello("Ktor"))
        }.hide()
        get("/health") {
            call.respondText("ok")
        }.hide()
        installAiServiceContract(aiServiceBaseUrl, aiHttp)
        extra()
    }
}
