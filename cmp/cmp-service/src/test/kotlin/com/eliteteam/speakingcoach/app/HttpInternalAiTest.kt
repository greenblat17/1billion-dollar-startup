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
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

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
            .startCall("v=0 offer", "Work", "marin")
        assertEquals("v=0 answer", started.sdpAnswer)
        assertEquals("rtc_1", started.openaiCallId)
        http.close()
    }

    private fun client(engine: MockEngine): HttpClient = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
}
