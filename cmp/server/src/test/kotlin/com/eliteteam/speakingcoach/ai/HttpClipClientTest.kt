package com.eliteteam.speakingcoach.ai

import com.eliteteam.speakingcoach.speaking.AudioClip
import com.eliteteam.speakingcoach.speaking.ClipReply
import com.eliteteam.speakingcoach.speaking.Correction
import com.eliteteam.speakingcoach.speaking.CorrectionKind
import com.eliteteam.speakingcoach.speaking.SessionId
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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class HttpClipClientTest {

    @Test
    fun startsSessionThenDownloadsGreetingAudio() = runTest {
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/v1/sessions" -> {
                    respond(
                        content = """{"sessionId":"s-1","greeting":{"text":"Hi!"}}""",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                request.method == HttpMethod.Get &&
                    request.url.encodedPath == "/v1/sessions/s-1/greeting/audio" -> {
                    respond(
                        content = byteArrayOf(7, 8),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "audio/ogg"),
                    )
                }
                else -> error("Unexpected request ${request.method} ${request.url}")
            }
        }
        val http = client(engine)
        val greeting = HttpClipClient(
            "http://ai.local",
            http,
            internalToken = "secret-token",
        ).startSession()

        assertEquals("s-1", greeting.sessionId.value)
        assertEquals("Hi!", greeting.text)
        assertEquals(byteArrayOf(7, 8).toList(), greeting.audio.bytes.toList())
        assertEquals("secret-token", engine.requestHistory.first().headers[AI_INTERNAL_TOKEN_HEADER])
        http.close()
    }

    @Test
    fun startsSessionWithProvidedId() = runTest {
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/v1/sessions" -> {
                    val body = (request.body as TextContent).text
                    assertContains(body, "\"sessionId\":\"tg-7\"")
                    respond(
                        content = """{"sessionId":"tg-7","greeting":{"text":"Hi!"}}""",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                request.method == HttpMethod.Get &&
                    request.url.encodedPath == "/v1/sessions/tg-7/greeting/audio" -> {
                    respond(
                        content = byteArrayOf(7, 8),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "audio/ogg"),
                    )
                }
                else -> error("Unexpected request ${request.method} ${request.url}")
            }
        }
        val http = client(engine)
        val greeting = HttpClipClient("http://ai.local", http).startSession(SessionId("tg-7"))

        assertEquals("tg-7", greeting.sessionId.value)
        http.close()
    }

    @Test
    fun ensureSessionPostsIdWithoutDownloadingAudio() = runTest {
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/v1/sessions" -> {
                    respond(
                        content = """{"sessionId":"tg-7","greeting":{"text":"Hi!"}}""",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> error("Unexpected request ${request.method} ${request.url}")
            }
        }
        val http = client(engine)
        val sessionId = HttpClipClient("http://ai.local", http).ensureSession(SessionId("tg-7"))
        assertEquals("tg-7", sessionId.value)
        http.close()
    }

    @Test
    fun submitsClipThenPollsUntilNotesAndAudioAreReady() = runTest {
        val reply = processUntilOk(
            """{"jobId":"job-1","status":"ok","result":{"notes":["I go|||I went"],"transcript":"I go to shop"},"transcript":"I go to shop"}""",
        )

        assertEquals(listOf(Correction("I go", "I went")), reply.corrections)
        assertEquals("I go to shop", reply.transcript)
        assertEquals(byteArrayOf(1, 2, 3).toList(), reply.audio.bytes.toList())
        assertEquals("audio/ogg", reply.audio.contentType)
    }

    @Test
    fun readsTypedCorrectionsOverLegacyNotes() = runTest {
        val reply = processUntilOk(
            """{"jobId":"job-1","status":"ok","result":{"notes":["I go|||I went","a photo|||photos"],""" +
                """"corrections":[{"wrong":"I go","better":"I went","kind":"grammar"},""" +
                """{"wrong":"made a photo","better":"took a photo","kind":"word"},""" +
                """{"wrong":"very fun","better":"really fun","kind":"style"}],"transcript":"I go"}}""",
        )

        assertEquals(
            listOf(
                Correction("I go", "I went", CorrectionKind.GRAMMAR),
                Correction("made a photo", "took a photo", CorrectionKind.WORD),
                Correction("very fun", "really fun", null),
            ),
            reply.corrections,
        )
    }

    private suspend fun processUntilOk(okBody: String): ClipReply {
        var polls = 0
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/v1/clips" -> {
                    respond(
                        content = """{"jobId":"job-1"}""",
                        status = HttpStatusCode.Accepted,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                request.method == HttpMethod.Get && request.url.encodedPath == "/v1/clips/job-1" -> {
                    polls += 1
                    val body = if (polls < 2) {
                        """{"jobId":"job-1","status":"pending"}"""
                    } else {
                        okBody
                    }
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                request.method == HttpMethod.Get && request.url.encodedPath == "/v1/clips/job-1/audio" -> {
                    respond(
                        content = byteArrayOf(1, 2, 3),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "audio/ogg"),
                    )
                }
                else -> error("Unexpected request ${request.method} ${request.url}")
            }
        }
        val http = client(engine)
        val client = HttpClipClient(
            baseUrl = "http://ai.local",
            http = http,
            pollInterval = 1.milliseconds,
            timeout = 500.milliseconds,
        )

        val reply = client.process(
            SessionId("tg-1"),
            AudioClip(byteArrayOf(9), "audio/ogg", "voice.ogg"),
        )
        http.close()
        return reply
    }

    private fun client(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
}
