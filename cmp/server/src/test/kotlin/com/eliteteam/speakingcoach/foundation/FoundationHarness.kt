package com.eliteteam.speakingcoach.foundation

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.time.Instant
import kotlinx.serialization.json.jsonObject

/** Test classpath only. Never packaged in the production JAR. */
fun main() {
    val url = requireNotNull(System.getenv("TEST_POSTGRES_URL"))
    val token = requireNotNull(System.getenv("SP00_TEST_TOKEN"))
    val port = requireNotNull(System.getenv("SP00_TEST_PORT")).toInt()
    val service = FoundationService(FoundationDatabase(url))
    val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
        routing {
            foundationRoutes(service, token)
            trialRoutes(service, token, "123:sp02-local-only")
            legacyFoundationRoutes(service, token)
            get("/test/health") { call.respondText("ok") }
            post("/test/import-tutor") {
                if (call.request.header("X-Test-Token") != token) {
                    call.respondText("forbidden", status = HttpStatusCode.Forbidden)
                    return@post
                }
                val input = foundationJson.parseToJsonElement(call.receiveText()).jsonObject
                try {
                    val result = TutorStateImport(service).import(input.text("subject"), input.obj("state"))
                    call.respondText(result.toString(), ContentType.Application.Json)
                } catch (error: FoundationFailure) {
                    call.respondText(json("error" to error.code).toString(), ContentType.Application.Json, HttpStatusCode.Conflict)
                }
            }
            post("/test/job-scope") {
                if (call.request.header("X-Test-Token") != token) {
                    call.respondText("forbidden", status = HttpStatusCode.Forbidden)
                    return@post
                }
                val job = foundationJson.parseToJsonElement(call.receiveText()).jsonObject
                call.respondText(json("scope" to service.identity.issueJob(job)).toString(), ContentType.Application.Json)
            }
            post("/test/student") {
                if (call.request.header("X-Test-Token") != token) {
                    call.respondText("forbidden", status = HttpStatusCode.Forbidden)
                    return@post
                }
                val body = foundationJson.parseToJsonElement(call.receiveText()).jsonObject
                val student = service.identity.resolve("telegram", body.text("subject"))
                val source = body.optionalText("text")?.let {
                    service.history.accept(student, body.text("requestId"), it, Instant.parse(body.text("occurredAt")), false)
                }
                val scope = service.identity.issue(student, body.text("role"),
                    request = if (source != null) body.text("requestId") else null,
                    lesson = body.optionalText("lessonId"))
                call.respondText(json("studentId" to student, "scope" to scope, "input" to source).toString(), ContentType.Application.Json)
            }
        }
    }
    Runtime.getRuntime().addShutdownHook(Thread { server.stop(100, 1000); service.close() })
    server.start(wait = true)
}
