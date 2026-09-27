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
    fun claimsReminderTargetsWithToken() = runTest {
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/internal/reminders/claim" -> {
                    respond(
                        content = """{"targets":[{"sessionId":"tg-1","name":"Alex Green"},{"sessionId":"tg-2","name":null}]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> error("Unexpected request ${request.method} ${request.url}")
            }
        }
        val http = client(engine)
        val targets = HttpClipClient("http://ai.local", http, internalToken = "secret-token").claimReminders()

        assertEquals(
            listOf(ReminderTarget("tg-1", "Alex Green"), ReminderTarget("tg-2", null)),
            targets,
        )
        assertEquals("secret-token", engine.requestHistory.single().headers[AI_INTERNAL_TOKEN_HEADER])
        http.close()
    }

    @Test
    fun reportsReminderRoundAsJson() = runTest {
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/internal/reminders/report" -> {
                    val body = (request.body as TextContent).text
                    assertContains(body, "\"mode\":\"manual\"")
                    assertContains(body, "{\"sessionId\":\"tg-1\",\"templateId\":\"day_went\",\"status\":\"blocked\"}")
                    respond(
                        content = """{"ok":true}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> error("Unexpected request ${request.method} ${request.url}")
            }
        }
        val http = client(engine)
        HttpClipClient("http://ai.local", http).reportReminders(
            ReminderReport(
                mode = "manual",
                startedAt = "2026-09-26T19:00+03:00",
                finishedAt = "2026-09-26T19:00:02+03:00",
                claimed = 1,
                results = listOf(ReminderSendResult("tg-1", "day_went", "blocked")),
            ),
        )
        assertEquals(1, engine.requestHistory.size)
        http.close()
    }

    @Test
    fun readsReminderBlockAndChatMarksFromMetrics() = runTest {
        val engine = MockEngine { request ->
            respond(
                content = """
                    {"timezone":"Europe/Moscow","day":"2026-09-26","promptTokens":0,"completionTokens":0,"tpm":0,
                    "tps":0,"turns":0,"dau":0,"sttSeconds":0,"ttsChars":0,
                    "chats":[{"sessionId":"tg-1","turns":1,"lastAt":"x","lastReminderAt":"2026-09-26T19:00:00+03:00","reminderIgnored":2}],
                    "reminders":{"today":{"sent":3,"blocked":1,"failed":0,"returned":1},"forecast":7,
                    "replyMedianSeconds":null,"runs":[{"mode":"auto","day":"2026-09-26","claimed":4,"sent":3}],
                    "autoToday":{"mode":"auto","sent":3},"templates":[{"templateId":"day_went","sent":3,"returned":1,"blocked":0}]}}
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = client(engine)
        val snapshot = HttpClipClient("http://ai.local", http).loadMetrics()

        val reminders = snapshot.reminders!!
        assertEquals(3, reminders.today.sent)
        assertEquals(7, reminders.forecast)
        assertEquals(4, reminders.runs.single().claimed)
        assertEquals("day_went", reminders.templates.single().templateId)
        assertEquals(2, snapshot.chats.single().reminderIgnored)
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
