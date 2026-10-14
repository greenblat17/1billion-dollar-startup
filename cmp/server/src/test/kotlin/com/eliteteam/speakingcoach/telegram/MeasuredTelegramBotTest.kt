package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.TelegramOperationalMetrics
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.requests.abstracts.Request
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.DeserializationStrategy
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MeasuredTelegramBotTest {
    private val request = object : Request<Boolean> {
        override fun method() = "testMethod"
        override val resultDeserializer: DeserializationStrategy<Boolean> = Boolean.serializer()
    }

    @Test
    fun countsSuccessfulAndFailedExecuteCalls() = runTest {
        val metrics = TelegramOperationalMetrics()
        var fail = false
        val delegate = object : TelegramBot by telegramBot("123:fake") {
            @Suppress("UNCHECKED_CAST")
            override suspend fun <T : Any> execute(request: Request<T>): T {
                if (fail) throw IllegalStateException("failed")
                return true as T
            }
        }
        val bot = MeasuredTelegramBot(delegate, metrics, "secret")

        assertEquals(true, bot.execute(request))
        fail = true
        assertFailsWith<IllegalStateException> { bot.execute(request) }

        val output = metrics.prometheus()
        assertContains(output, "speaking_provider_calls_total{type=\"telegram_api\",result=\"success\"} 1")
        assertContains(output, "speaking_provider_calls_total{type=\"telegram_api\",result=\"error\"} 1")
    }
}
