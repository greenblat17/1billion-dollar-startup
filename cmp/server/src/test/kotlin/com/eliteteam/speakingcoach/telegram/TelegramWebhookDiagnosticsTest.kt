package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.DiagnosticOutput
import com.eliteteam.speakingcoach.TelegramOperationalMetrics
import com.eliteteam.speakingcoach.ai.TutorClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.FileNotFoundException
import java.nio.file.Files
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TelegramWebhookDiagnosticsTest {
    @Test
    fun missingWebhookCertificateEmitsOneSafeRegistrationFailure() = runTest {
        val temp = Files.createTempFile("private-certificate-path", ".pem")
        Files.delete(temp)
        DiagnosticOutput().use { logs ->
            assertFailsWith<FileNotFoundException> {
                registerTelegramWebhook(speakingCoachTelegramBot("123:fixture"),
                    "https://private-webhook-url.invalid", "private-webhook-secret", temp.toFile())
            }
            val row = logs.events().single()
            assertEquals("telegram_webhook_registration", row["event"]?.jsonPrimitive?.content)
            assertEquals("certificate", row["stage"]?.jsonPrimitive?.content)
            assertEquals("failed", row["outcome"]?.jsonPrimitive?.content)
            assertEquals("certificate_unreadable", row["reason_code"]?.jsonPrimitive?.content)
            assertTrue(logs.lines().none { "private-" in it })
        }
    }

    @Test
    fun webhookSeparatesAuthorizationAndMalformedUpdatesWithoutLoggingTheBody() = testApplication {
        val secret = "private-webhook-secret"
        val payload = "private-telegram-update"
        val scope = newTelegramWebhookScope()
        try {
            HttpClient(MockEngine { error("Unaccepted updates must not call the tutor") }).use { http ->
                val bot = buildSpeakyTelegram("123:fixture", TutorClient("https://ai.test", http, "unused"), scope)
                application { routing {
                    installSpeakingCoachWebhook(secret, bot, scope, operationalMetrics = TelegramOperationalMetrics())
                } }
                DiagnosticOutput().use { logs ->
                    val unauthenticated = client.post("/") { setBody(payload) }
                    val malformed = client.post("/") {
                        header(TELEGRAM_WEBHOOK_SECRET_HEADER, secret)
                        setBody(payload)
                    }
                    assertEquals(HttpStatusCode.Forbidden, unauthenticated.status)
                    assertEquals(HttpStatusCode.InternalServerError, malformed.status)
                    val records = logs.events()
                    assertEquals(2, records.size)
                    assertEquals(listOf("authorize", "decode"), records.map { it["stage"]?.jsonPrimitive?.content })
                    assertEquals(listOf("unauthorized", "invalid_update"), records.map { it["reason_code"]?.jsonPrimitive?.content })
                    for ((response, row) in listOf(unauthenticated, malformed).zip(records)) {
                        assertEquals("rejected", row["outcome"]?.jsonPrimitive?.content)
                        assertEquals(assertNotNull(response.headers["X-Diagnostic-Trace"]), row["trace_id"]?.jsonPrimitive?.content)
                        assertEquals(assertNotNull(response.headers["X-Error-ID"]), row["error_id"]?.jsonPrimitive?.content)
                    }
                    assertTrue(logs.lines().none { payload in it || secret in it })
                }
            }
        } finally {
            scope.cancel()
        }
    }
}
