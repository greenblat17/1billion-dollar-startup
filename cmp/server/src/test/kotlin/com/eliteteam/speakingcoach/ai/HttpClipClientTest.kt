package com.eliteteam.speakingcoach.ai

import com.eliteteam.speakingcoach.speaking.AudioClip
import com.eliteteam.speakingcoach.speaking.SessionId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class HttpClipClientTest {

    @Test
    fun startSessionReportsMissingGreetingVoice() = runTest {
        val http = client(MockEngine { error("walkie-talkie has no greeting") })
        val error = assertFailsWith<NotImplementedError> {
            HttpClipClient("http://ai.local", http).startSession(SessionId("tg-7"))
        }
        assertContains(error.message ?: "", "Greeting voice is not implemented")
        assertContains(error.message ?: "", "tg-7")
        http.close()
    }

    @Test
    fun ensureSessionKeepsIdWithoutCallingAi() = runTest {
        val http = client(MockEngine { error("unexpected ${it.method} ${it.url}") })
        val sessionId = HttpClipClient("http://ai.local", http).ensureSession(SessionId("tg-7"))
        assertEquals("tg-7", sessionId.value)
        http.close()
    }

    @Test
    fun postsWalkieTalkieAndReturnsTranscriptWithoutNotes() = runTest {
        val wav = byteArrayOf(1, 2, 3)
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v1/walkie-talkie", request.url.encodedPath)
            assertEquals("secret-token", request.headers[AI_INTERNAL_TOKEN_HEADER])
            respond(
                content = """
                    {
                      "session_id": "tg-1",
                      "transcript": "I go to shop",
                      "response_text": "Where did you go?",
                      "audio_mime_type": "audio/wav",
                      "audio_base64": "${Base64.getEncoder().encodeToString(wav)}"
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = client(engine)
        val reply = HttpClipClient(
            baseUrl = "http://ai.local",
            http = http,
            timeout = 5.seconds,
            internalToken = "secret-token",
        ).process(
            SessionId("tg-1"),
            AudioClip(byteArrayOf(9), "audio/ogg", "voice.ogg"),
        )

        assertEquals(emptyList(), reply.notes)
        assertEquals("I go to shop", reply.transcript)
        assertEquals(wav.toList(), reply.audio.bytes.toList())
        assertEquals("audio/wav", reply.audio.contentType)
        assertEquals("reply.wav", reply.audio.fileName)
        http.close()
    }

    private fun client(engine: MockEngine) = HttpClient(engine) {
        install(HttpTimeout)
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
}
