package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.telegram.TELEGRAM_WEBHOOK_SECRET_HEADER
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplicationTest {

    @Test
    fun testRoot() = testApplication {
        application {
            module()
        }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Hello, Ktor!", response.bodyAsText())
    }

    @Test
    fun healthDoesNotRequireWebhookSecret() = testApplication {
        application {
            module(webhookTestConfig())
        }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun webhookRejectsMismatchedSecret() = testApplication {
        application {
            module(webhookTestConfig())
        }
        val response = client.post("/telegram/webhook") {
            header(TELEGRAM_WEBHOOK_SECRET_HEADER, "wrong-secret")
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun webhookRejectsMissingSecret() = testApplication {
        application {
            module(webhookTestConfig())
        }
        val response = client.post("/telegram/webhook") {
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun swaggerUiIsServed() = testApplication {
        application {
            module()
        }
        val response = client.get("/swagger")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun swaggerSpecDescribesAiServiceNotTelegramOrHealth() = testApplication {
        application {
            module()
        }
        val html = client.get("/swagger").bodyAsText()
        val specPath = Regex("""url:\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            ?: error("swagger ui did not reference a spec: $html")
        val specResponse = client.get(specPath)
        assertEquals(HttpStatusCode.OK, specResponse.status)
        val specType = specResponse.headers[HttpHeaders.ContentType].orEmpty()
        assertTrue(specType.contains("yaml"), specType)
        val spec = specResponse.bodyAsText()
        assertTrue(spec.contains("/v1/sessions"), spec)
        assertTrue(spec.contains("/v1/clips"), spec)
        assertTrue(spec.contains("createSession"), spec)
        assertTrue(spec.contains("createClip"), spec)
        assertFalse(spec.contains("/telegram"), spec)
        assertFalse(spec.contains("/health"), spec)
    }

    private fun webhookTestConfig() = AppConfig(
        telegramBotToken = null,
        telegramWebhookUrl = "https://localhost/telegram/webhook",
        telegramWebhookSecret = "expected-secret",
        aiServiceBaseUrl = "http://127.0.0.1:8090",
        serverPort = 8080,
        tlsCertPath = AppConfig.DEFAULT_TLS_CERT_PATH,
        tlsKeyPath = AppConfig.DEFAULT_TLS_KEY_PATH,
    )
}
