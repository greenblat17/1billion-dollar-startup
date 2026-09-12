package com.eliteteam.speakingcoach.telegram

import kotlin.test.Test
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
}
