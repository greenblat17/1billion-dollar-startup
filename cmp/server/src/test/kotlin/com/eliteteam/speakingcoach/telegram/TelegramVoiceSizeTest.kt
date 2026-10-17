package com.eliteteam.speakingcoach.telegram

import dev.inmo.tgbotapi.bot.exceptions.ApiException
import dev.inmo.tgbotapi.types.Response
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramVoiceSizeTest {
    @Test
    fun checksOptionalTelegramFileSizeWithoutUsingDuration() {
        assertFalse(exceedsTelegramDownloadLimit(null))
        assertFalse(exceedsTelegramDownloadLimit(20uL * 1024uL * 1024uL))
        assertTrue(exceedsTelegramDownloadLimit(20uL * 1024uL * 1024uL + 1uL))
    }

    @Test
    fun recognizesOnlyTelegramTooBigDownloadResponse() {
        val tooBig = ApiException(400, "Bad Request: file is too big",
            Response(errorCode = 400, description = "Bad Request: file is too big"))
        assertTrue(isTelegramFileTooLarge(tooBig))
        assertTrue(isTelegramFileTooLarge(IllegalStateException("wrapped", tooBig)))
        assertFalse(isTelegramFileTooLarge(ApiException(400, "Bad Request: wrong file identifier",
            Response(errorCode = 400, description = "Bad Request: wrong file identifier"))))
    }
}
