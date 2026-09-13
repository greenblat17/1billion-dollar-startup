package com.eliteteam.speakingcoach.telegram

import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.LogLevel
import dev.inmo.tgbotapi.utils.isCausedByCancellation

private val telegramBotTokenInUrl = Regex("/bot\\d+:[A-Za-z0-9_-]+")

internal fun redactTelegramBotToken(text: String, token: String = ""): String {
    val withoutToken = if (token.isNotEmpty()) text.replace(token, "***") else text
    return telegramBotTokenInUrl.replace(withoutToken, "/bot***")
}

internal class RedactingKSLog(
    private val delegate: KSLog,
    private val token: String,
) : KSLog {
    override fun performLog(level: LogLevel, tag: String?, message: Any, throwable: Throwable?) {
        if (throwable?.isCausedByCancellation() == true) {
            return
        }
        val text = buildString {
            append(redactTelegramBotToken(message.toString(), token))
            if (throwable != null) {
                append('\n')
                append(redactTelegramBotToken(throwable.stackTraceToString(), token))
            }
        }
        delegate.performLog(level, tag, text, null)
    }
}
