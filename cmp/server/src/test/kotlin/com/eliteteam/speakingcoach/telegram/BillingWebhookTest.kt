package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.TelegramOperationalMetrics
import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviour
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class BillingWebhookTest {
    @Test fun paymentDispatchRequiresWebhookSecretAndFailureIsNotAcknowledged() = testApplication {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val delegate = object : dev.inmo.tgbotapi.bot.TelegramBot by telegramBot("123:fake") {
            override suspend fun <T : Any> execute(request: dev.inmo.tgbotapi.requests.abstracts.Request<T>): T {
                check(request.method() == "getMe") { "No network or sending permitted in billing tests" }
                return kotlinx.serialization.json.Json.decodeFromString(request.resultDeserializer,
                    """{"id":123,"is_bot":true,"first_name":"Test","username":"test_bot"}""")
            }
        }
        val bot = runBlocking { delegate.buildBehaviour(scope = scope) {} }
        var calls = 0
        var fail = false
        application {
            routing { route("/webhook") {
                installSpeakingCoachWebhook("test-secret", bot, scope, operationalMetrics = TelegramOperationalMetrics(), paymentHandler = {
                    calls++
                    if (fail) error("storage unavailable")
                    true
                })
            } }
        }
        val payload = """{"update_id":1,"callback_query":{"id":"query","from":{"id":100,"is_bot":false,"first_name":"Test"},"chat_instance":"test","data":"sp05:ok:test","message":{"message_id":1,"date":1700000000,"chat":{"id":100,"type":"private","first_name":"Test"},"text":"test"}}}"""
        try {
            for (secret in listOf("", "wrong")) {
                val response = client.post("/webhook") { header("X-Telegram-Bot-Api-Secret-Token", secret); setBody(payload) }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }
            assertEquals(0, calls)
            assertEquals(HttpStatusCode.OK, client.post("/webhook") { header("X-Telegram-Bot-Api-Secret-Token", "test-secret"); setBody(payload) }.status)
            assertEquals(1, calls)
            fail = true
            assertEquals(HttpStatusCode.InternalServerError, client.post("/webhook") { header("X-Telegram-Bot-Api-Secret-Token", "test-secret"); setBody(payload) }.status)
            assertEquals(2, calls)
        } finally { scope.cancel(); bot.close() }
    }
}
