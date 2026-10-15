package com.eliteteam.speakingcoach.load

import com.eliteteam.speakingcoach.AppConfig
import com.eliteteam.speakingcoach.ai.HttpClipClient
import com.eliteteam.speakingcoach.speaking.SessionClipQueue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class LoadTestRoutesTest {
    @Test
    fun guardedRouteProcessesClipThroughQueueAndMarksAiRequest() = testApplication {
        var clipsSubmitted = 0
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/v1/sessions" ->
                    respond("""{"sessionId":"loadtest-run-1","greeting":{"text":"Hi"}}""",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"))
                request.method == HttpMethod.Post && request.url.encodedPath == "/v1/clips" -> {
                    assertEquals("1", request.headers["X-Speaky-Load-Test"])
                    clipsSubmitted++
                    respond("""{"jobId":"job-1"}""", status = HttpStatusCode.Accepted,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
                request.url.encodedPath == "/v1/clips/job-1" ->
                    respond("""{"jobId":"job-1","status":"ok","result":{"audioAvailable":true}}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"))
                request.url.encodedPath == "/v1/clips/job-1/audio" ->
                    respond(byteArrayOf(1, 2, 3), headers = headersOf(HttpHeaders.ContentType, "audio/ogg"))
                else -> error("Unexpected AI request ${request.method} ${request.url}")
            }
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val ai = HttpClipClient("http://ai.local", http, internalToken = "ai-secret")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queue = SessionClipQueue(ai, scope)
        application { routing { installLoadTestRoutes(config(), ai, queue) } }

        assertEquals(HttpStatusCode.Forbidden,
            client.post("/internal/load-test/sessions?sessionId=loadtest-run-1").status)
        assertEquals(HttpStatusCode.BadRequest,
            client.post("/internal/load-test/sessions?sessionId=tg-1") {
                header("X-Load-Test-Token", "load-secret")
            }.status)
        assertEquals(HttpStatusCode.Created,
            client.post("/internal/load-test/sessions?sessionId=loadtest-run-1") {
                header("X-Load-Test-Token", "load-secret")
            }.status)
        assertEquals(HttpStatusCode.OK,
            client.post("/internal/load-test/clips?sessionId=loadtest-run-1&durationSeconds=3.4") {
                header("X-Load-Test-Token", "load-secret")
                header(HttpHeaders.ContentType, "audio/ogg")
                setBody(byteArrayOf(1, 2, 3))
            }.status)
        assertEquals(1, clipsSubmitted)
        scope.cancel()
        http.close()
    }

    @Test
    fun routeRequiresDevAndToken() {
        assertEquals(false, config().copy(speakyEnv = "prod").loadTestEnabled)
        assertEquals(false, config().copy(loadTestToken = null).loadTestEnabled)
    }

    private fun config() = AppConfig(
        telegramBotToken = null,
        telegramWebhookUrl = null,
        telegramWebhookSecret = null,
        aiServiceBaseUrl = "http://ai.local",
        aiInternalToken = "ai-secret",
        serverPort = 8080,
        tlsCertPath = "",
        tlsKeyPath = "",
        jwtSecret = null,
        databaseUrl = null,
        loadTestToken = "load-secret",
        speakyEnv = "dev",
    )
}
