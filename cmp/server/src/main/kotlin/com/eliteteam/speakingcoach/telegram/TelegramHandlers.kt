package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.ChatProfile
import com.eliteteam.speakingcoach.ai.HttpClipClient
import com.eliteteam.speakingcoach.speaking.ClipReply
import com.eliteteam.speakingcoach.speaking.AudioClip
import com.eliteteam.speakingcoach.speaking.ClipSubmitResult
import com.eliteteam.speakingcoach.speaking.SessionClipQueue
import com.eliteteam.speakingcoach.speaking.SessionId
import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onDataCallbackQuery
import dev.inmo.tgbotapi.types.queries.callback.AbstractMessageCallbackQuery
import kotlinx.coroutines.CancellationException
import dev.inmo.tgbotapi.extensions.api.files.downloadFile
import dev.inmo.tgbotapi.extensions.api.send.media.sendVoice
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.api.send.replyWithPhoto
import dev.inmo.tgbotapi.extensions.api.send.setMessageReaction
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

private fun spokenKey(chatId: Any, messageId: MessageId) = "$chatId:${messageId.long}"

private val startSourcePattern = Regex("^[A-Za-z0-9_-]{1,64}$")

internal fun isStartCommand(text: String): Boolean = isCommand(text, "/start")

internal fun isOnboardingCommand(text: String): Boolean = isCommand(text, "/onboarding")

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
    val actions = TelegramChatActions()
    val answeredStreaks = ConcurrentHashMap.newKeySet<String>()
    val progressMessages = ConcurrentHashMap<String, MessageId>()
    val spokenLines = ConcurrentHashMap<String, String>()
    suspend fun clearProgress(chat: Chat) {
        val key = chat.id.toString()
        val existing = progressMessages.remove(key) ?: return
        try {
            editMessageReplyMarkup(chat.id, existing, replyMarkup = noInlineKeyboard)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to clear onboarding progress for {}", key, error)
        }
    }
    suspend fun sendStreakWeek(message: ChatMessage, caption: String, strip: WeekStrip) {
        try {
            replyWithPhoto(
                message,
                streakWeekPng(strip).asMultipartFile("streak.png"),
                text = caption,
                allowSendingWithoutReply = true,
            )
        } catch (error: Throwable) {
            log.warn("Failed to send streak week for {}", message.chat.id, error)
            reply(message, caption, allowSendingWithoutReply = true)
        }
    }
    suspend fun sendStreak(message: ChatMessage) {
        val claim = "${message.chat.id}:${message.messageId}"
        if (!answeredStreaks.add(claim)) {
            return
        }
        val sessionId = telegramSessionId(message.chat.id)
        try {
            val profile = ai.streakProfile(sessionId)
            sendStreakWeek(message, streakProfileCaption(profile), weekStrip(profile.last7))
        } catch (error: Throwable) {
            answeredStreaks.remove(claim)
            log.error("Failed to send streak for {}", sessionId.value, error)
            reply(message, ERROR_TEXT, allowSendingWithoutReply = true)
        }
    }
    suspend fun deliver(message: ChatMessage, result: ClipReply, firstQuestion: Boolean = false) {
        if (result.onboarding?.status == "ignored") return
        val onboarding = result.onboarding?.takeIf { it.status in setOf("active", "pending", "completed") }
        val progress = onboarding?.let { onboardingProgressKeyboard(it.seconds) }
        if (progress != null) clearProgress(message.chat)
        if (onboarding != null) {
            result.corrections.minByOrNull { it.priority }?.let { correction ->
                reply(message, onboardingCorrection(correction), allowSendingWithoutReply = true)
            }
        } else if (result.transcript.isNotBlank()) {
            reply(message, coachingEntities(result.transcript, result.corrections), allowSendingWithoutReply = true)
        }
        val audio = result.audio
        if (audio != null) {
            val spoken = result.text.isNotBlank()
            val voice = sendVoice(
                message.chat.id,
                audio.bytes.asMultipartFile(audio.fileName),
                replyMarkup = withSpokenText(progress, spoken),
            )
            if (spoken) spokenLines[spokenKey(message.chat.id, voice.messageId)] = result.text.trim()
            if (firstQuestion) {
                reply(message, ONBOARDING_VOICE_HINT, allowSendingWithoutReply = true)
            }
            if (result.onboarding?.status == "completed") {
                reply(message, ONBOARDING_REMEMBERED, allowSendingWithoutReply = true)
            }
            if (progress != null) progressMessages[message.chat.id.toString()] = voice.messageId
        } else if (result.text.isNotBlank()) {
            val state = result.onboarding
            val action = state?.let {
                onboardingKeyboard(if (it.status == "pending") "retry" else "continue", it.runId)
            }
            val sent = reply(
                message,
                result.text,
                allowSendingWithoutReply = true,
                replyMarkup = when {
                    progress != null && action != null -> progress + action
                    else -> progress ?: action
                },
            )
            if (progress != null) progressMessages[message.chat.id.toString()] = sent.messageId
        }
    }
    suspend fun greet(message: ChatMessage, text: String, force: Boolean = false) {
        val sessionId = telegramSessionId(message.chat.id)
        val requestId = "message:${message.messageId}"
        // Resolve eligibility before today's funnel event makes a new user look existing.
        val state = ai.onboardingState(sessionId, requestId, if (force) "force" else "start")
        try {
            ai.recordFunnelStart(sessionId, startSource(text), telegramProfile(message.chat))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to record start for {}", sessionId.value, error)
        }
        if (state.status == "waiting") {
            clearProgress(message.chat)
            reply(
                message,
                onboardingInvitation((message.chat as? PrivateChat)?.firstName),
                allowSendingWithoutReply = true,
                replyMarkup = onboardingKeyboard("begin", state.runId),
            )
        } else {
            val greeting = ai.startSession(sessionId)
            reply(message, startTextMessage((message.chat as? PrivateChat)?.firstName), allowSendingWithoutReply = true)
            sendVoice(message.chat.id, greeting.audio.bytes.asMultipartFile(greeting.audio.fileName))
        }
    }
    suspend fun voice(message: ChatMessage, content: VoiceContent) {
        val sessionId = telegramSessionId(message.chat.id)
        val requestId = "message:${message.messageId}"
        val state = ai.onboardingState(sessionId, requestId)
        if (state.status == "waiting") {
            reply(message, ONBOARDING_BEGIN_HINT, replyMarkup = onboardingKeyboard("begin", state.runId))
            return
        }
        if (state.status == "pending") {
            reply(message, "I couldn't prepare your result. Please try again.", replyMarkup = onboardingKeyboard("retry", state.runId))
            return
        }
        if (state.react) {
            try {
                setMessageReaction(message.chat.id, message.messageId, "❤")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.warn("Failed to react to the first onboarding voice for {}", sessionId.value, error)
            }
        }
        try {
            ai.recordFunnelVoice(sessionId, telegramProfile(message.chat))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to record voice for {}", sessionId.value, error)
        }
        ai.ensureSession(sessionId)
        val result = sessionClipQueue.submit(
            sessionId = sessionId,
            source = {
                AudioClip(
                    downloadFile(content.media), "audio/ogg", "voice.ogg",
                    onboardingRunId = state.runId.takeIf { state.status == "active" || state.status == "completed" },
                    requestId = requestId,
                    durationSeconds = (content.media.duration ?: 0L).toDouble(),
                )
            },
        )
        when (result) {
            ClipSubmitResult.QueueFull -> reply(message, QUEUE_FULL_TEXT)
            is ClipSubmitResult.Completed -> deliver(message, result.reply)
        }
    }
    suspend fun handle(message: ChatMessage, isVoice: Boolean = false, block: suspend () -> Unit) {
        try {
            actions.run(
                chatId = message.chat.id.toString(),
                requestId = "message:${message.messageId}",
                voice = isVoice,
                onQueued = { reply(message, QUEUED_TEXT) },
                onFull = { reply(message, QUEUE_FULL_TEXT) },
                action = block,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.error("Telegram message failed for {}", message.chat.id, error)
            val state = if (isVoice) {
                runCatching { ai.onboardingState(telegramSessionId(message.chat.id), "error:${message.messageId}") }.getOrNull()
            } else null
            reply(
                message, if (state?.retryAvailable == true) "Something went wrong. Tap the button to retry with your saved answer." else ERROR_TEXT,
                allowSendingWithoutReply = true,
                replyMarkup = state?.takeIf { it.retryAvailable }
                    ?.let { onboardingKeyboard("retry", it.runId) },
            )
        }
    }
    onDataCallbackQuery { query ->
        // Stop Telegram's spinner before waiting for synthesis or the per-chat queue.
        try {
            answerCallbackQuery(query)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to answer callback", error)
        }
        if (query.data == SPOKEN_TEXT_CALLBACK) {
            val voiceMessage = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@onDataCallbackQuery
            val spoken = spokenLines[spokenKey(voiceMessage.chat.id, voiceMessage.messageId)] ?: return@onDataCallbackQuery
            reply(voiceMessage, spokenQuote(spoken), allowSendingWithoutReply = true)
            return@onDataCallbackQuery
        }
        val callback = parseOnboardingCallback(query.data) ?: return@onDataCallbackQuery
        val message = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@onDataCallbackQuery
        try {
            actions.run(
                message.chat.id.toString(),
                if (callback.action == "retry") "callback:${query.id}" else "callback:${callback.action}:${callback.runId}",
            ) {
                val result = ai.onboardingAction(
                    telegramSessionId(message.chat.id), "callback:${query.id}", callback.runId, callback.action,
                )
                deliver(message, result, firstQuestion = callback.action == "begin")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.error("Onboarding callback failed for {}", message.chat.id, error)
            sendMessage(
                message.chat.id, "Something went wrong. Please try again.",
                replyMarkup = onboardingKeyboard(callback.action, callback.runId),
            )
        }
    }
    onCommand("start", requireOnlyCommandInMessage = false) { message ->
        if (isStartCommand(message.content.text)) handle(message) { greet(message, message.content.text) }
    }
    onCommand("onboarding", requireOnlyCommandInMessage = false) { message ->
        if (isOnboardingCommand(message.content.text)) handle(message) { greet(message, message.content.text, force = true) }
    }
    onCommand("streak", requireOnlyCommandInMessage = false) { message ->
        if (isStreakCommand(message.content.text)) sendStreak(message)
    }
    onContentMessage { message ->
        handle(message, isVoice = message.content is VoiceContent) {
            when (val content = message.content) {
                is VoiceContent -> voice(message, content)
                is TextContent -> when {
                    isStartCommand(content.text) -> greet(message, content.text)
                    isOnboardingCommand(content.text) -> greet(message, content.text, force = true)
                    isStreakCommand(content.text) -> sendStreak(message)
                    content.text.startsWith("/") -> Unit
                    else -> reply(message, SEND_VOICE_HINT)
                }
                else -> Unit
            }
        }
    }
}
