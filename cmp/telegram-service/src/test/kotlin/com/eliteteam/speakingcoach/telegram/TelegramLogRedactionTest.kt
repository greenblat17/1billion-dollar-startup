package com.eliteteam.speakingcoach.telegram

import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.LogLevel
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramLogRedactionTest {

    @Test
    fun stripsBotTokenFromTelegramApiUrl() {
        val token = "123456:AA-test_token"
        val raw = "Request timeout [url=https://api.telegram.org/bot$token/getUpdates]"

        val redacted = redactTelegramBotToken(raw, token)

        assertFalse(redacted.contains(token))
        assertTrue(redacted.contains("/bot***"))
    }

    @Test
    fun dropsCancellationExceptions() {
        val recorded = mutableListOf<String>()
        val log = RedactingKSLog(
            delegate = KSLog { _, _, message, _ -> recorded += message.toString() },
            token = "secret-token",
        )

        log.performLog(
            LogLevel.ERROR,
            "KTgBot",
            "Something web wrong",
            CancellationException("Job was cancelled"),
        )

        assertEquals(emptyList(), recorded)
    }
}
