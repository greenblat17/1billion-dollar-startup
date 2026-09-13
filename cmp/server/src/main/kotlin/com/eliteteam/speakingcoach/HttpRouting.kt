package com.eliteteam.speakingcoach

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.OpenApiInfo
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.routing
import io.ktor.utils.io.ExperimentalKtorApi

@OptIn(ExperimentalKtorApi::class)
internal fun Application.installSpeakingCoachHttp(extra: Route.() -> Unit = {}) {
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
    }
    routing {
        swaggerUI("/swagger") {
            info = OpenApiInfo("Speaking coach", "1.0")
            source = OpenApiDocSource.Routing(ContentType.Application.Json)
        }
        get("/") {
            call.respondText(sayHello("Ktor"))
        }.describe {
            summary = "Root"
            responses {
                HttpStatusCode.OK {
                    description = "Greeting"
                    ContentType.Text.Plain()
                }
            }
        }
        get("/health") {
            call.respondText("ok")
        }.describe {
            summary = "Health"
            responses {
                HttpStatusCode.OK {
                    description = "ok"
                    ContentType.Text.Plain()
                }
            }
        }
        extra()
    }
}
