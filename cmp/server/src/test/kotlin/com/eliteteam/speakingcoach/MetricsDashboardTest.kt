package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.ai.FunnelDay
import com.eliteteam.speakingcoach.ai.FunnelSource
import com.eliteteam.speakingcoach.ai.MetricsChat
import com.eliteteam.speakingcoach.ai.MetricsSnapshot
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetricsDashboardTest {
    @Test
    fun metricsRoutesStayHiddenWithoutPassword() = testApplication {
        application {
            module(dashboardConfig(password = null))
        }
        assertEquals(HttpStatusCode.NotFound, client.get("/admin/metrics").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/admin/metrics/login").status)
    }

    @Test
    fun metricsPageAsksForPasswordUntilLogin() = testApplication {
        application {
            module(dashboardConfig(password = PASSWORD), metricsSource = FixedMetricsSource(sampleSnapshot()))
        }
        val anonymous = createClient { followRedirects = false }
        val form = anonymous.get("/admin/metrics")
        assertEquals(HttpStatusCode.OK, form.status)
        assertTrue(form.bodyAsText().contains("<form"))

        val rejected = anonymous.post("/admin/metrics/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("password=wrong")
        }
        assertEquals(HttpStatusCode.Unauthorized, rejected.status)
        assertEquals(null, rejected.headers[HttpHeaders.SetCookie])
        assertTrue(rejected.bodyAsText().contains("Неверный пароль."))

        val login = anonymous.post("/admin/metrics/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("password=$PASSWORD")
        }
        assertEquals(HttpStatusCode.Found, login.status)
        val setCookie = login.headers[HttpHeaders.SetCookie].orEmpty()
        assertTrue(setCookie.contains("HttpOnly"))
        assertFalse(setCookie.contains(PASSWORD))
        val token = setCookie.substringAfter("$METRICS_COOKIE=").substringBefore(";")
        assertEquals(metricsSessionToken(PASSWORD), token)

        val page = anonymous.get("/admin/metrics") {
            cookie(METRICS_COOKIE, token)
        }
        val html = page.bodyAsText()
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(html.contains("tg-9"))
        assertTrue(html.contains("<td>@alex_g · Alex &lt;Green&gt;</td>"))
        assertTrue(html.contains("<tr><td>tg-10</td><td>—</td>"))
        assertTrue(html.contains("Activated за 7 дней"))
        assertTrue(html.contains("clubs"))
        assertTrue(html.contains("1.50"))
        assertTrue(html.contains("3.00"))
        assertFalse(html.contains("transcript"))
        assertFalse(html.contains("I walked on weekends"))
        assertFalse(html.contains(PASSWORD))
    }

    @Test
    fun metricsPageHidesAiFailureBody() = testApplication {
        application {
            module(dashboardConfig(password = PASSWORD), metricsSource = FailingMetricsSource())
        }
        val page = client.get("/admin/metrics") {
            cookie(METRICS_COOKIE, metricsSessionToken(PASSWORD))
        }
        val html = page.bodyAsText()
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(html.contains("Сводка сейчас недоступна."))
        assertFalse(html.contains("secret-transcript"))
    }

    private fun dashboardConfig(password: String?) = AppConfig(
        telegramBotToken = null,
        telegramWebhookUrl = null,
        telegramWebhookSecret = null,
        aiServiceBaseUrl = "http://127.0.0.1:8090",
        aiInternalToken = "test-internal-token",
        serverPort = 8080,
        tlsCertPath = AppConfig.DEFAULT_TLS_CERT_PATH,
        tlsKeyPath = AppConfig.DEFAULT_TLS_KEY_PATH,
        jwtSecret = null,
        databaseUrl = null,
        metricsPassword = password,
    )

    private fun sampleSnapshot() = MetricsSnapshot(
        timezone = "Europe/Moscow",
        day = "2026-09-24",
        promptTokens = 100,
        completionTokens = 40,
        tpm = 12,
        tps = 8.0,
        turns = 2,
        dau = 1,
        sttSeconds = 3.5,
        ttsChars = 20,
        rubPerTurn = 1.5,
        rubPerDau = 3.0,
        ratesConfigured = true,
        chats = listOf(
            MetricsChat(
                sessionId = "tg-9",
                turns = 2,
                lastAt = "2026-09-24T12:00:00+03:00",
                username = "alex_g",
                name = "Alex <Green>",
            ),
            MetricsChat(
                sessionId = "tg-10",
                turns = 1,
                lastAt = "2026-09-24T11:00:00+03:00",
            ),
        ),
        activated7 = 4,
        funnelDays = listOf(FunnelDay(day = "2026-09-24", start = 2, activated = 1)),
        funnelSources = listOf(FunnelSource(source = "clubs", start = 2, activated = 1)),
    )

    private class FixedMetricsSource(private val snapshot: MetricsSnapshot) : MetricsSource {
        override suspend fun load(): MetricsSnapshot = snapshot
    }

    private class FailingMetricsSource : MetricsSource {
        override suspend fun load(): MetricsSnapshot {
            error("ai-service GET /internal/metrics returned 500 body secret-transcript")
        }
    }

    private companion object {
        const val PASSWORD = "secret-pass"
    }
}
