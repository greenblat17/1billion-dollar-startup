package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.telegram.TELEGRAM_WEBHOOK_SECRET_HEADER
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun publicHttpDoesNotExposeClipApiOrSwagger() = testApplication {
        application {
            module()
        }
        assertEquals(HttpStatusCode.NotFound, client.get("/swagger").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/sessions").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/clips/x").status)
    }

    private fun webhookTestConfig() = AppConfig(
        telegramBotToken = null,
        telegramWebhookUrl = "https://localhost/telegram/webhook",
        telegramWebhookSecret = "expected-secret",
        aiServiceBaseUrl = "http://127.0.0.1:8090",
        aiInternalToken = "test-internal-token",
        serverPort = 8080,
        tlsCertPath = AppConfig.DEFAULT_TLS_CERT_PATH,
        tlsKeyPath = AppConfig.DEFAULT_TLS_KEY_PATH,
    )
}
