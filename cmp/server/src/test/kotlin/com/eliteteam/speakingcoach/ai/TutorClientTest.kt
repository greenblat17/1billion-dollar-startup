package com.eliteteam.speakingcoach.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class TutorClientTest {
    @Test fun textAndStartUseDedicatedApiWithStableIdentity() = runTest {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            assertEquals("test-token", request.headers[AI_INTERNAL_TOKEN_HEADER])
            assertContains((request.body as TextContent).text, "\"requestId\":\"message:42\"")
            respond("""{"conversationId":"persisted","reply":"Продолжим","booking":null}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            val client = TutorClient("http://ai.local", http, "test-token")
            val request = TutorRequest("tg-1", "message:42", "Вопрос после записи", 100.0)
            assertEquals("persisted", client.respond(request, false).conversationId)
            assertEquals("persisted", client.respond(request.copy(text = "/start"), true).conversationId)
            assertEquals(listOf("/internal/tutor/turn", "/internal/tutor/start"), paths)
        } finally { http.close() }
    }

    @Test fun failedResponseDoesNotBecomeBookingConfirmation() = runTest {
        val http = HttpClient(MockEngine { respond("unavailable", HttpStatusCode.ServiceUnavailable) }) {
            install(ContentNegotiation) { json() }
        }
        try {
            assertFailsWith<IllegalStateException> {
                TutorClient("http://ai.local", http, "test-token")
                    .respond(TutorRequest("tg-1", "message:42", "Запиши", 100.0), false)
            }
        } finally { http.close() }
    }
}
