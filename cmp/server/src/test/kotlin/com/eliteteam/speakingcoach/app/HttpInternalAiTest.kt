package com.eliteteam.speakingcoach.app

import com.eliteteam.speakingcoach.ai.AI_INTERNAL_TOKEN_HEADER
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains

class HttpInternalAiTest {

    @Test
    fun postsRealtimeCallWithInternalToken() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/internal/realtime/call", request.url.encodedPath)
            assertEquals("secret", request.headers[AI_INTERNAL_TOKEN_HEADER])
            respond(
                content = """{"sdp":"v=0 answer","openaiCallId":"rtc_1"}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = client(engine)
        val started = HttpInternalAi("http://ai.local", http, "secret")
            .startCall("v=0 offer", "Work", "marin", "", null)
        assertEquals("v=0 answer", started.sdpAnswer)
        assertEquals("rtc_1", started.openaiCallId)
        http.close()
    }

    @Test
    fun postsReviewWithSessionAndPlatformForProviderLogs() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/internal/review", request.url.encodedPath)
            val body = (request.body as TextContent).text
            assertContains(body, "\"sessionId\":\"app-user-session\"")
            assertContains(body, "\"platform\":\"ios\"")
            respond(
                content = """{"steps":[]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = client(engine)
        HttpInternalAi("http://ai.local", http, "secret")
            .review(listOf(TranscriptTurn("user", "Hello")), "app-user-session", "ios")
        http.close()
    }

    private fun client(engine: MockEngine): HttpClient = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
}
