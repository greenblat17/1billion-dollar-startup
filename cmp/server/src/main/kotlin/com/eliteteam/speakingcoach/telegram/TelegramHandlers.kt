package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.ChatProfile
import com.eliteteam.speakingcoach.ai.HttpClipClient
import com.eliteteam.speakingcoach.speaking.AudioClip
import com.eliteteam.speakingcoach.speaking.ClipSubmitResult
import com.eliteteam.speakingcoach.speaking.SessionClipQueue
import com.eliteteam.speakingcoach.speaking.SessionId
import com.eliteteam.speakingcoach.speaking.TurnStreak
import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.extensions.api.files.downloadFile
import dev.inmo.tgbotapi.extensions.api.send.media.sendVoice
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onContentMessage
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.types.chat.PrivateChat
import dev.inmo.tgbotapi.types.message.abstracts.ChatMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.message.content.VoiceContent
import dev.inmo.tgbotapi.utils.DefaultKTgBotAPIKSLog
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

internal const val TELEGRAM_WEBHOOK_SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token"

internal fun telegramSessionId(chatId: Any): SessionId = SessionId("tg-$chatId")

private val startSourcePattern = Regex("^[A-Za-z0-9_-]{1,64}$")

internal fun isStartCommand(text: String): Boolean = isCommand(text, "/start")

internal fun isStreakCommand(text: String): Boolean = isCommand(text, "/streak")

private fun isCommand(text: String, name: String): Boolean {
    val command = text.trim().substringBefore(' ').substringBefore('@')
    return command == name
}

internal fun startSource(text: String): String? {
    val trimmed = text.trim()
    val payload = when {
        trimmed.startsWith("/start@") -> {
            val space = trimmed.indexOf(' ')
            if (space < 0) "" else trimmed.substring(space + 1).trim()
        }
        trimmed.startsWith("/start") -> trimmed.removePrefix("/start").trim()
        else -> return null
    }
    return payload.takeIf { startSourcePattern.matches(it) }
}

internal fun telegramProfile(chat: Chat): ChatProfile {
    val privateChat = chat as? PrivateChat ?: return ChatProfile(username = null, name = null)
    val name = listOf(privateChat.firstName, privateChat.lastName)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
    return ChatProfile(
        username = privateChat.username?.withoutAt,
        name = name.ifEmpty { null },
    )
}

internal fun speakingCoachTelegramBot(token: String) = telegramBot(token) {
    logger = RedactingKSLog(DefaultKTgBotAPIKSLog, token)
}

internal fun BehaviourContext.installSpeakingCoachHandlers(
    ai: HttpClipClient,
    sessionClipQueue: SessionClipQueue,
) {
    val log = LoggerFactory.getLogger("TelegramHandlers")
    val greetedStarts = ConcurrentHashMap.newKeySet<String>()
    val answeredStreaks = ConcurrentHashMap.newKeySet<String>()
    suspend fun sendStreak(message: ChatMessage) {
        val claim = "${message.chat.id}:${message.messageId}"
        if (!answeredStreaks.add(claim)) {
            return
        }
        val sessionId = telegramSessionId(message.chat.id)
        try {
            reply(message, streakProfileText(ai.streakProfile(sessionId)), allowSendingWithoutReply = true)
        } catch (error: Throwable) {
            answeredStreaks.remove(claim)
            log.error("Failed to send streak for {}", sessionId.value, error)
            reply(message, ERROR_TEXT, allowSendingWithoutReply = true)
        }
    }
    suspend fun greet(message: ChatMessage, text: String) {
        val claim = "${message.chat.id}:${message.messageId}"
        if (!greetedStarts.add(claim)) {
            return
        }
        val sessionId = telegramSessionId(message.chat.id)
        val source = startSource(text)
        log.info("Start command {} from {}", message.messageId, message.chat.id)
        try {
            ai.recordFunnelStart(sessionId, source, telegramProfile(message.chat))
        } catch (error: Throwable) {
            log.warn("Failed to record start for {}", sessionId.value, error)
        }
        try {
            val greeting = ai.startSession(sessionId)
            val firstName = (message.chat as? PrivateChat)?.firstName
            val textMessage = reply(
                message,
                startTextMessage(firstName),
                allowSendingWithoutReply = true,
            )
            val voiceMessage = sendVoice(
                message.chat.id,
                greeting.audio.bytes.asMultipartFile(greeting.audio.fileName),
            )
            log.info(
                "Started session {} for tg-{} textMessage={} voiceMessage={}",
                greeting.sessionId.value,
                message.chat.id,
                textMessage.messageId,
                voiceMessage.messageId,
            )
        } catch (error: Throwable) {
            greetedStarts.remove(claim)
            log.error("Failed to start session for tg-{}", message.chat.id, error)
            reply(message, ERROR_TEXT, allowSendingWithoutReply = true)
        }
    }
    suspend fun sendStreakNote(message: ChatMessage, streak: TurnStreak) {
        try {
            reply(message, streakMessage(streak), allowSendingWithoutReply = true)
        } catch (error: Throwable) {
            log.warn("Failed to send streak note for {}", message.chat.id, error)
        }
    }
    onCommand("start", requireOnlyCommandInMessage = false) { message ->
        val text = message.content.text
        if (isStartCommand(text)) {
            greet(message, text)
        }
    }
    onCommand("streak", requireOnlyCommandInMessage = false) { message ->
        if (isStreakCommand(message.content.text)) {
            sendStreak(message)
        }
    }
    onContentMessage { message ->
        when (val content = message.content) {
            is VoiceContent -> {
                val sessionId = telegramSessionId(message.chat.id)
                try {
                    ai.recordFunnelVoice(sessionId, telegramProfile(message.chat))
                } catch (error: Throwable) {
                    log.warn("Failed to record voice for {}", sessionId.value, error)
                }
                try {
                    ai.ensureSession(sessionId)
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
                        ClipSubmitResult.QueueFull -> {
                            reply(message, QUEUE_FULL_TEXT)
                            log.info("Voice queue is full for {}", sessionId.value)
                        }
                        is ClipSubmitResult.Completed -> {
                            if (result.reply.transcript.isNotBlank()) {
                                reply(
                                    message,
                                    coachingEntities(result.reply.transcript, result.reply.corrections),
                                )
                            }
                            sendVoice(
                                message.chat.id,
                                result.reply.audio.bytes.asMultipartFile(result.reply.audio.fileName),
                            )
                            log.info("Sent voice reply for {}", sessionId.value)
                            result.reply.streak?.let { streak ->
                                if (streak.firstToday) {
                                    sendStreakNote(message, streak)
                                }
                            }
                        }
                    }
                } catch (error: Throwable) {
                    log.error("Failed to handle voice for tg-{}", message.chat.id, error)
                    reply(message, ERROR_TEXT)
                }
            }
            is TextContent -> {
                if (isStartCommand(content.text)) {
                    greet(message, content.text)
                } else if (isStreakCommand(content.text)) {
                    sendStreak(message)
                } else if (content.text.startsWith("/")) {
                    log.info(
                        "Ignored command {} from {}",
                        content.text.substringBefore(' ').take(64),
                        message.chat.id,
                    )
                } else {
                    reply(message, SEND_VOICE_HINT)
                }
            }
            else -> Unit
        }
    }
}
