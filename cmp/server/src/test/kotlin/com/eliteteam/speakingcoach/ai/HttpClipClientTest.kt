package com.eliteteam.speakingcoach.ai

import com.eliteteam.speakingcoach.speaking.AudioClip
import com.eliteteam.speakingcoach.speaking.SessionId
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
import kotlin.time.Duration.Companion.milliseconds

class HttpClipClientTest {

    @Test
    fun submitsClipThenPollsUntilAudioIsReady() = runTest {
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
                    val status = if (polls < 2) "pending" else "ok"
                    respond(
                        content = """{"jobId":"job-1","status":"$status"}""",
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
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
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

        assertEquals(byteArrayOf(1, 2, 3).toList(), reply.bytes.toList())
        assertEquals("audio/ogg", reply.contentType)
        http.close()
    }
}
