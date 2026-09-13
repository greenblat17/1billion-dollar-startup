package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.speaking.AudioClip
import com.eliteteam.speakingcoach.speaking.ClipSubmitResult
import com.eliteteam.speakingcoach.speaking.SessionClipQueue
import com.eliteteam.speakingcoach.speaking.SessionId
import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.extensions.api.files.downloadFile
import dev.inmo.tgbotapi.extensions.api.send.media.sendVoice
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onContentMessage
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.message.content.VoiceContent
import dev.inmo.tgbotapi.utils.DefaultKTgBotAPIKSLog
import org.slf4j.LoggerFactory

internal const val TELEGRAM_WEBHOOK_SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token"

internal fun speakingCoachTelegramBot(token: String) = telegramBot(token) {
    logger = RedactingKSLog(DefaultKTgBotAPIKSLog, token)
}

internal fun BehaviourContext.installSpeakingCoachHandlers(sessionClipQueue: SessionClipQueue) {
    val log = LoggerFactory.getLogger("TelegramHandlers")
    onCommand("start") { message ->
        reply(message, START_INFO_TEXT)
    }
    onContentMessage { message ->
        when (val content = message.content) {
            is VoiceContent -> {
                val sessionId = SessionId("tg-${message.chat.id}")
                try {
                    val result = sessionClipQueue.submit(
                        sessionId = sessionId,
                        source = {
                            val bytes = downloadFile(content.media)
                            AudioClip(bytes, "audio/ogg", "voice.ogg")
                        },
                        onQueuedBehind = {
                            reply(message, QUEUED_TEXT)
                        },
                    )
                    when (result) {
                        ClipSubmitResult.QueueFull -> reply(message, QUEUE_FULL_TEXT)
                        is ClipSubmitResult.Completed -> sendVoice(
                            message.chat.id,
                            result.reply.bytes.asMultipartFile(result.reply.fileName),
                        )
                    }
                } catch (error: Throwable) {
                    log.error("Failed to handle voice for {}", sessionId.value, error)
                    reply(message, ERROR_TEXT)
                }
            }
            is TextContent -> {
                if (!content.text.startsWith("/")) {
                    reply(message, SEND_VOICE_HINT)
                }
            }
            else -> Unit
        }
    }
}
