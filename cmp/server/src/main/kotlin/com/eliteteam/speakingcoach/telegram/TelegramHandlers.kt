package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.ChatProfile
import com.eliteteam.speakingcoach.withRequestLog
import com.eliteteam.speakingcoach.ai.HttpClipClient
import com.eliteteam.speakingcoach.ai.ClipJobFailure
import com.eliteteam.speakingcoach.ai.ClipUploadFailure
import com.eliteteam.speakingcoach.ai.ClipPollingTimeout
import com.eliteteam.speakingcoach.ai.ConversationReview
import com.eliteteam.speakingcoach.ai.voiceExtension
import com.eliteteam.speakingcoach.ai.VocabularyResponse
import com.eliteteam.speakingcoach.analytics.AttemptMark
import com.eliteteam.speakingcoach.analytics.OnboardingAnalytics
import com.eliteteam.speakingcoach.analytics.OnboardingVoiceFacts
import com.eliteteam.speakingcoach.analytics.AnalyticsWriteBuffer
import com.eliteteam.speakingcoach.analytics.VoiceAttempt
import com.eliteteam.speakingcoach.analytics.VoiceAttemptRecorder
import com.eliteteam.speakingcoach.analytics.InteractionAudit
import com.eliteteam.speakingcoach.analytics.InteractionEvent
import com.eliteteam.speakingcoach.analytics.CallEvent
import com.eliteteam.speakingcoach.analytics.CallEventRecorder
import com.eliteteam.speakingcoach.analytics.VocabularyFact
import com.eliteteam.speakingcoach.analytics.voiceAttemptId
import com.eliteteam.speakingcoach.analytics.safely
import com.eliteteam.speakingcoach.speaking.ClipReply
import com.eliteteam.speakingcoach.speaking.AudioClip
import com.eliteteam.speakingcoach.speaking.ClipSubmitResult
import com.eliteteam.speakingcoach.speaking.SessionClipQueue
import com.eliteteam.speakingcoach.TelegramOperationalMetrics
import com.eliteteam.speakingcoach.speaking.SessionId
import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.bot.exceptions.BotException
import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.deleteMessage
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.api.send.copyMessage
import dev.inmo.tgbotapi.extensions.api.send.sendBotAction
import dev.inmo.tgbotapi.extensions.api.send.withRecordVoiceAction
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.buttons.ReplyKeyboardRemove
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onDataCallbackQuery
import dev.inmo.tgbotapi.types.queries.callback.AbstractMessageCallbackQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
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
import dev.inmo.tgbotapi.types.actions.BotAction
import dev.inmo.tgbotapi.types.actions.RecordVoiceAction
import dev.inmo.tgbotapi.types.actions.TypingAction
import dev.inmo.tgbotapi.types.message.abstracts.ChatMessage
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.message.content.VoiceContent
import dev.inmo.tgbotapi.types.message.textsources.TextSourcesList
import dev.inmo.tgbotapi.utils.DefaultKTgBotAPIKSLog
import dev.inmo.tgbotapi.utils.buildEntities
import dev.inmo.tgbotapi.utils.regular
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Base64
import kotlin.time.TimeSource

internal const val TELEGRAM_WEBHOOK_SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token"
private const val CLARIFY_TEXT = "I didn't catch that. Could you say it again?"
private const val TELEGRAM_DOWNLOAD_LIMIT_BYTES = 20L * 1024 * 1024
private val wordsCommand = Regex("^/words(?:@[A-Za-z0-9_]+)?\\s*$", RegexOption.IGNORE_CASE)

internal class VoiceFileTooLargeException(cause: Throwable? = null) : Exception("Telegram voice file is too large", cause)

internal fun exceedsTelegramDownloadLimit(bytes: ULong?): Boolean =
    bytes != null && bytes > TELEGRAM_DOWNLOAD_LIMIT_BYTES.toULong()

internal fun isTelegramFileTooLarge(error: Throwable): Boolean = generateSequence(error) { it.cause }.any { cause ->
    cause is BotException && cause.response?.errorCode == 400 &&
        cause.response?.description?.contains("file is too big", ignoreCase = true) == true
}

internal fun telegramSessionId(chatId: Any): SessionId = SessionId("tg-$chatId")
private val telegramChatIdPattern = Regex("(?:ChatId\\(chatId=)?(-?\\d+)\\)?")
internal fun telegramChatNumber(chatId: Any): Long? =
    telegramChatIdPattern.matchEntire(chatId.toString())?.groupValues?.get(1)?.toLongOrNull()

private fun aiFailureStage(stage: String): String = when (stage) {
    "stt" -> "stt"
    "llm" -> "reply_llm"
    "tts" -> "tts"
    "state" -> "state"
    else -> "other"
}

private fun localFailureReason(error: Throwable): String = when {
    error.javaClass.simpleName.contains("timeout", ignoreCase = true) -> "timeout"
    error.javaClass.simpleName.contains("connect", ignoreCase = true) ||
        error.javaClass.simpleName.contains("network", ignoreCase = true) ||
        error.javaClass.simpleName.contains("socket", ignoreCase = true) -> "network"
    error is IllegalArgumentException -> "invalid_input"
    else -> "unknown"
}

private fun spokenKey(chatId: Any, messageId: MessageId) = "$chatId:${messageId.long}"

private suspend fun noteUserAction(
    ai: HttpClipClient,
    log: org.slf4j.Logger,
    sessionId: SessionId,
    action: String,
    eventId: String? = null,
) {
    try {
        ai.recordUserAction(sessionId, action, eventId = eventId)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        log.warn("Failed to record user action {} for {}", action, sessionId.value, error)
    }
}

private fun userAction(text: String): String = when {
    isStartCommand(text) -> "start"
    isOnboardingCommand(text) -> "onboarding"
    isProfileCommand(text) -> "profile"
    isStreakCommand(text) -> "streak"
    isRemindCommand(text) -> "remind"
    isVoiceCommand(text) -> "voice"
    isSpeedCommand(text) -> "speed"
    isStartCallButton(text) -> "start-call"
    text == VOCABULARY_BUTTON || wordsCommand.matches(text) -> "vocabulary"
    text == PRACTICE_SITUATION_BUTTON -> "practice-situation"
    text.startsWith("/") -> "command"
    else -> "text"
}

private fun callbackAction(data: String): String = when {
    parseCallFeedbackCallback(data) != null -> "callback:call-feedback"
    data == VOICE_MENU_CALLBACK -> "callback:voice-menu"
    parseVoiceCallback(data) != null -> "callback:voice"
    parseSpeedCallback(data) != null -> "callback:speed"
    data == REMINDER_STOP_CALLBACK -> "callback:reminder-stop"
    data == SPOKEN_TEXT_CALLBACK -> "callback:spoken-text"
    data == PROFILE_STREAK_CALLBACK -> "callback:streak"
    data.startsWith("scenario:") -> "callback:scenario"
    else -> parseCallCallback(data)?.let { "callback:call:${it.action}" }

        ?: "callback"
}

private val startSourcePattern = Regex("^[A-Za-z0-9_-]{1,64}$")

internal fun isStartCommand(text: String): Boolean = isCommand(text, "/start")

internal fun isOnboardingCommand(text: String): Boolean = isCommand(text, "/onboarding")

internal fun isProfileCommand(text: String): Boolean = isCommand(text, "/profile")

internal fun isStreakCommand(text: String): Boolean = isCommand(text, "/streak")

internal fun isRemindCommand(text: String): Boolean = isCommand(text, "/remind")

internal fun isVoiceCommand(text: String): Boolean = isCommand(text, "/voice")

internal fun isSpeedCommand(text: String): Boolean = isCommand(text, "/speed")

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

internal fun speakingCoachTelegramBot(
    token: String,
    audit: InteractionAudit? = null,
    metrics: TelegramOperationalMetrics? = null,
    userIdHashKey: String = "",
) =
    telegramBot(token) { logger = RedactingKSLog(DefaultKTgBotAPIKSLog, token) }
        .let { if (metrics == null) it else MeasuredTelegramBot(it, metrics, userIdHashKey) }
        .let { if (audit == null) it else AuditedTelegramBot(it, audit) }

internal fun BehaviourContext.installSpeakingCoachHandlers(
    ai: HttpClipClient,
    sessionClipQueue: SessionClipQueue,
    analytics: OnboardingAnalytics? = null,
    voiceAttempts: VoiceAttemptRecorder? = null,
    operationalMetrics: TelegramOperationalMetrics? = null,
    audit: InteractionAudit? = null,
    callEvents: CallEventRecorder? = null,
    voiceCost: com.eliteteam.speakingcoach.analytics.VoiceCostStore? = null,
    followups: FollowupStore? = null,
    reminderButtons: ReminderButtonStore? = null,
    vocabularyAnalytics: com.eliteteam.speakingcoach.analytics.VocabularyAnalytics? = null,
) {
    val log = LoggerFactory.getLogger("TelegramHandlers")
    val actions = TelegramChatActions()
    val tutorChat = TutorTelegramChat(this, ai.tutor)
    val answeredStreaks = ConcurrentHashMap.newKeySet<String>()
    val progressMessages = ConcurrentHashMap<String, MessageId>()
    val spokenLines = ConcurrentHashMap<String, String>()
    val scenarioMenus = ConcurrentHashMap<String, MessageId>()
    val customScenarioPrompts = ConcurrentHashMap<String, MessageId>()
    val vocabularyAddPrompts = ConcurrentHashMap.newKeySet<String>()

    fun vocabularyFact(message: ChatMessage, kind: String, id: String, lessonId: String = "",
                       itemId: String = "", phase: String = "", category: String = "",
                       turnId: String = "", botMessageId: Long = 0, milliseconds: Long = 0,
                       sourceMessageId: Long = message.messageId.long) {
        val chatId = telegramChatNumber(message.chat.id) ?: return
        vocabularyAnalytics?.record(VocabularyFact(
            id = id, sessionId = "tg-$chatId", at = Instant.now().toEpochMilli() / 1000.0,
            kind = kind, lessonId = lessonId, itemId = itemId, phase = phase,
            category = category, turnId = turnId, messageId = sourceMessageId,
            botMessageId = botMessageId, milliseconds = milliseconds,
        ))
    }

    suspend fun showStatus(chat: Chat, action: BotAction) {
        try {
            sendBotAction(chat, action)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to show Telegram action {} for {}", action.actionName, chat.id, error)
        }
    }

    suspend fun acknowledgeVocabularyTurn(message: ChatMessage, result: VocabularyResponse, sentMessageId: Long) {
        val turnId = result.turnId ?: return
        val lessonId = result.lesson?.id ?: return
        try {
            ai.vocabulary(telegramSessionId(message.chat.id), "delivery", lessonId = lessonId,
                messageId = sentMessageId, turnId = turnId)
        } catch (error: CancellationException) { throw error
        } catch (error: Throwable) { log.warn("Vocabulary delivery acknowledgement failed for lesson {}", lessonId, error) }
    }

    suspend fun sendVocabularyTurn(message: ChatMessage, result: VocabularyResponse) {
        if (result.deliveredMessageId != null) return
        val audio = result.audio
        val lesson = result.lesson
        if (audio != null && lesson != null) {
            showStatus(message.chat, RecordVoiceAction)
            val bytes = Base64.getDecoder().decode(audio)
            val voice = sendVoice(message.chat.id,
                bytes.asMultipartFile("lesson.${voiceExtension(result.audioContentType)}"),
                replyMarkup = vocabularyLessonKeyboard(lesson))
            vocabularyFact(message, "reply_delivered", "delivery:${lesson.id}:${result.turnId ?: voice.messageId.long}",
                lesson.id, turnId = result.turnId.orEmpty(), botMessageId = voice.messageId.long)
            acknowledgeVocabularyTurn(message, result, voice.messageId.long)
            spokenLines[spokenKey(message.chat.id, voice.messageId)] = result.text
            try {
                ai.vocabulary(telegramSessionId(message.chat.id), "subtitle-save",
                    text = result.text, messageId = voice.messageId.long)
            } catch (error: CancellationException) { throw error
            } catch (error: Throwable) { log.warn("Vocabulary subtitle storage failed", error) }
        } else if (result.text.isNotBlank()) {
            val sent = reply(message, result.text, allowSendingWithoutReply = true,
                replyMarkup = lesson?.let { vocabularyLessonKeyboard(it, spoken = false) })
            lesson?.let { vocabularyFact(message, "reply_delivered", "delivery:${it.id}:${result.turnId ?: sent.messageId.long}",
                it.id, turnId = result.turnId.orEmpty(), botMessageId = sent.messageId.long) }
            acknowledgeVocabularyTurn(message, result, sent.messageId.long)
        }
        if (result.summary != null) {
            val summary = sendMessage(message.chat.id, result.summary, replyMarkup = startCallKeyboard())
            lesson?.let { vocabularyFact(message, "summary_delivered", "summary:${it.id}", it.id,
                botMessageId = summary.messageId.long) }
        }
    }
    suspend fun sendVocabularyVoiceTurn(message: ChatMessage, produce: suspend () -> VocabularyResponse): VocabularyResponse {
        return withRecordVoiceAction(message.chat) {
            val result = produce()
            sendVocabularyTurn(message, result)
            result
        }
    }
    suspend fun showVocabularyList(message: ChatMessage, page: Int = 0, edit: Boolean = false) {
        val state = ai.vocabulary(telegramSessionId(message.chat.id), "list")
        val current = state.lesson?.takeIf { it.status in setOf("active", "offer", "cards") }
        if (current != null) {
            if (edit) try {
                editMessageReplyMarkup(message.chat.id, message.messageId, replyMarkup = noInlineKeyboard)
            } catch (error: CancellationException) { throw error
            } catch (error: Throwable) { log.warn("Could not close vocabulary menu", error) }
            sendVocabularyVoiceTurn(message) {
                ai.vocabulary(telegramSessionId(message.chat.id), "resume", lessonId = current.id)
            }
            return
        }
        val text = vocabularyListText(state, page)
        val keyboard = vocabularyListKeyboard(state, page)
        if (edit) try {
            editMessageText(message.chat.id, message.messageId, text, replyMarkup = keyboard)
        } catch (error: CancellationException) { throw error
        } catch (error: Throwable) {
            reply(message, text, allowSendingWithoutReply = true, replyMarkup = keyboard)
        } else reply(message, text, allowSendingWithoutReply = true, replyMarkup = keyboard)
    }
    suspend fun clearScenarioMenu(chat: Chat) {
        val menuId = scenarioMenus.remove(chat.id.toString()) ?: return
        try {
            editMessageReplyMarkup(chat.id, menuId, replyMarkup = noInlineKeyboard)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Could not close scenario menu for {}", chat.id, error)
        }
    }
    suspend fun auditGenerated(message: ChatMessage, text: String, kind: String) {
        val chatId = telegramChatNumber(message.chat.id) ?: return
        try {
            audit?.record(InteractionEvent(
                id = "generated:$chatId:${message.messageId.long}:$kind",
                chatId = chatId, direction = "internal", kind = kind, status = "generated",
                messageId = message.messageId.long,
                receivedAt = audit.receiptTime(chatId, message.messageId.long) ?: Instant.now(),
                content = text,
            ))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Could not record generated Telegram text", error)
        }
    }
    fun callEvent(event: CallEvent) { callEvents?.record(event) }
    fun callChat(message: ChatMessage): Long? = telegramChatNumber(message.chat.id)
    fun callCharacter(chatId: Long, callId: String, character: String?, at: Instant) {
        if (character in setOf("speaky", "kora_h"))
            callEvent(CallEvent("call:$callId:character", "call_character", chatId, at,
                callId = callId, state = character))
    }
    fun callClosed(message: ChatMessage, callId: String?, at: Instant?, reason: String) {
        val chatId = callChat(message) ?: return
        if (callId.isNullOrBlank()) return
        callEvent(CallEvent("call:$callId:closed", "call_closed", chatId, at ?: Instant.now(),
            callId = callId, state = reason))
    }
    fun rollover(message: ChatMessage, callId: String?, unix: Double?) {
        callClosed(message, callId, unix?.let { Instant.ofEpochMilli((it * 1000).toLong()) }, "next_moscow_day")
    }
    suspend fun noteOptionalCardMetric(sessionId: SessionId, outcome: String) {
        val write: suspend () -> Unit = { noteUserAction(ai, log, sessionId, "follow_up_card_$outcome") }
        val buffer = currentCoroutineContext()[AnalyticsWriteBuffer]
        if (buffer != null) buffer.add(write) else write()
    }
    fun recordVoiceAttempt(message: ChatMessage, receivedAt: Instant, eligible: Boolean? = null,
                           outcome: String? = null, stage: String? = null, reason: String? = null,
                           setupMs: Long? = null, queueMs: Long? = null, chatQueueMs: Long? = null,
                           downloadMs: Long? = null,
                           processingMs: Long? = null, deliveryMs: Long? = null, totalMs: Long? = null,
                           jobId: String? = null, timingsMs: Map<String, Long> = emptyMap()) {
        val chatId = telegramChatNumber(message.chat.id) ?: return
        voiceAttempts?.record(VoiceAttempt(chatId, message.messageId.long, receivedAt,
            username = telegramProfile(message.chat).username.orEmpty(), eligible = eligible,
            outcome = outcome, stage = stage, reason = reason,
            jobId = jobId, terminalAt = if (outcome == null) null else Instant.now(), setupMs = setupMs,
            queueMs = queueMs, chatQueueMs = chatQueueMs, downloadMs = downloadMs,
            processingMs = processingMs, deliveryMs = deliveryMs,
            totalMs = totalMs, sttMs = timingsMs["stt"], replyLlmMs = timingsMs["reply"],
            correctionLlmMs = timingsMs["notes"], ttsMs = timingsMs["tts"],
            finalizeMs = timingsMs["finalize"]))
    }
    suspend fun optionalCard(message: ChatMessage, label: String, send: suspend () -> Unit) {
        noteOptionalCardMetric(telegramSessionId(message.chat.id), "attempted")
        try {
            send()
            noteOptionalCardMetric(telegramSessionId(message.chat.id), "succeeded")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Optional {} card failed", label, error)
            noteOptionalCardMetric(telegramSessionId(message.chat.id), "failed")
        }
    }
    suspend fun hideStartCallKeyboard(chat: Chat) {
        try {
            sendMessage(chat.id, "Your turn—send a voice message.", replyMarkup = ReplyKeyboardRemove())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to hide Start call keyboard for {}", chat.id, error)
        }
    }
    suspend fun showStartCallKeyboard(chat: Chat) {
        sendMessage(chat.id, NEXT_CHAT_HINT, replyMarkup = startCallKeyboard())
    }
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
    suspend fun sendStreak(message: ChatMessage, claim: String = "${message.chat.id}:${message.messageId}") {
        val sessionId = telegramSessionId(message.chat.id)
        withRequestLog(sessionId.value, "message:${message.messageId}") {
        if (!answeredStreaks.add(claim)) {
            return@withRequestLog
        }
        log.info("Sending streak")
        try {
            log.info("Reading streak")
            val profile = ai.streakProfile(sessionId)
            sendStreakWeek(message, streakProfileCaption(profile), weekStrip(profile.last7))
        } catch (error: Throwable) {
            answeredStreaks.remove(claim)
            log.error("Failed to send streak for {}", sessionId.value, error)
            reply(message, ERROR_TEXT, allowSendingWithoutReply = true)
        }
        }
    }
    suspend fun sendProfile(message: ChatMessage) {
        log.info("Reading progress")
        val profile = ai.progressProfile(telegramSessionId(message.chat.id))
        log.info("Sending profile")
        reply(
            message, profileMessage((message.chat as? PrivateChat)?.firstName, profile),
            allowSendingWithoutReply = true,
        )
    }
    suspend fun sendVoiceSelector(message: ChatMessage) {
        val character = ai.character(telegramSessionId(message.chat.id))
        reply(message, voiceMessage(character), allowSendingWithoutReply = true, replyMarkup = voiceKeyboard(character))
    }
    suspend fun sendSpeed(message: ChatMessage) {
        log.info("Reading speech speed")
        val speed = ai.speechSpeed(telegramSessionId(message.chat.id))
        log.info("Sending speech speed")
        reply(message, speedMessage(speed), allowSendingWithoutReply = true, replyMarkup = speedKeyboard(speed))
    }
    suspend fun sendRemind(message: ChatMessage) {
        val sessionId = telegramSessionId(message.chat.id)
        log.info("Reading reminder time")
        val current = try {
            ai.reminderTime(sessionId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to read reminder time for {}", sessionId.value, error)
            null
        }
        log.info("Asking reminder time")
        ai.scheduleReminder(sessionId, "remind:${message.messageId}", "ask", REMIND_COMMAND_RUN)
        val saved = current?.takeIf { it.isNotBlank() }
        reply(
            message,
            if (saved == null) REMINDER_TIME_PROMPT else reminderChangePrompt(saved),
            allowSendingWithoutReply = true,
            replyMarkup = saved?.let { reminderStopKeyboard() },
        )
    }
    suspend fun reminderMissing(message: ChatMessage): Boolean = try {
        ai.reminderTime(telegramSessionId(message.chat.id)).isNullOrBlank()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        log.warn("Failed to read reminder time for {}", message.chat.id, error)
        false
    }
    suspend fun showModeFeedback(message: ChatMessage, instanceId: String) {
        val session = telegramSessionId(message.chat.id)
        try {
            val offered = ai.callFeedback(session, "offer", callId = instanceId,
                username = telegramProfile(message.chat).username.orEmpty())
            if (offered.status != "offered") {
                log.info("Feedback not offered for {}: {}", message.chat.id, offered.reason ?: offered.status)
                return
            }
            try {
                reply(message, if (offered.mode == "onboarding") ONBOARDING_FEEDBACK_PROMPT else MODE_FEEDBACK_PROMPT,
                    allowSendingWithoutReply = true, replyMarkup = firstCallFeedbackKeyboard(instanceId))
            } catch (error: Throwable) {
                try { ai.callFeedback(session, "release", callId = instanceId) }
                catch (releaseError: Throwable) { log.warn("Failed to release feedback offer", releaseError) }
                throw error
            }
            ai.callFeedback(session, "confirm", callId = instanceId)
            if (offered.mode != "onboarding") callChat(message)?.let { chatId -> callEvent(CallEvent(
                "call:$instanceId:feedback-offered", "feedback_offered", chatId,
                callId = instanceId, state = offered.character)) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to show feedback for {}", message.chat.id, error)
        }
    }
    suspend fun deliver(message: ChatMessage, result: ClipReply, firstQuestion: Boolean = false,
                        onCorrection: (Boolean) -> Unit = {},
                        onMainDelivered: (Long, String) -> Unit = { _, _ -> }): Boolean {
        val deliveryStarted = TimeSource.Monotonic.markNow()
        val practice = result.call
        val progress: dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup? = null
        if (practice != null) clearProgress(message.chat)
        if (practice?.goalJustCrossed == true) {
            optionalCard(message, "goal") {
                reply(message, callGoalReached(practice.goalSeconds), allowSendingWithoutReply = true)
            }
        }
        if (practice != null) {
            result.corrections.minByOrNull { it.priority }?.let { correction ->
                showStatus(message.chat, TypingAction)
                var sent = false
                optionalCard(message, "correction") {
                    reply(message, correctionCard(correction), allowSendingWithoutReply = true)
                    sent = true
                }
                onCorrection(sent)
            }
        } else if (result.transcript.isNotBlank()) {
            if (result.corrections.isNotEmpty()) showStatus(message.chat, TypingAction)
            optionalCard(message, "coaching") {
                reply(message, coachingEntities(result.transcript, result.corrections), allowSendingWithoutReply = true)
            }
        }
        val beforeVoiceMs = deliveryStarted.elapsedNow().inWholeMilliseconds
        val audio = result.audio
        if (audio != null) {
            showStatus(message.chat, RecordVoiceAction)
            val spoken = result.text.isNotBlank()
            val voice = sendVoice(
                message.chat.id,
                audio.bytes.asMultipartFile(audio.fileName),
                text = null,
                replyMarkup = practice?.let { callKeyboard(it.todaySeconds, it.goalSeconds, spoken, it.callId) }
                    ?: withSpokenText(progress, spoken),
            )
            onMainDelivered(voice.messageId.long, "audio")
            if (spoken) spokenLines[spokenKey(message.chat.id, voice.messageId)] = result.text.trim()
            if (progress != null || practice != null) progressMessages[message.chat.id.toString()] = voice.messageId
        } else if (result.text.isNotBlank()) {
            val sent = reply(message, result.text, allowSendingWithoutReply = true)
            onMainDelivered(sent.messageId.long, "text_fallback")
            if (progress != null) progressMessages[message.chat.id.toString()] = sent.messageId
        }
        log.info(
            "Telegram reply delivery session={} request=message:{} before_voice_ms={} voice_or_text_ms={} total_ms={}",
            telegramSessionId(message.chat.id).value, message.messageId, beforeVoiceMs,
            deliveryStarted.elapsedNow().inWholeMilliseconds - beforeVoiceMs,
            deliveryStarted.elapsedNow().inWholeMilliseconds,
        )
        return audio != null || result.text.isNotBlank()
    }
    suspend fun greet(message: ChatMessage, text: String, force: Boolean = false, trigger: String = "start",
                      receivedAt: Instant = Instant.now()) {
        tutorChat.respond(message, text, receivedAt, start = true)
    }
    suspend fun startCall(message: ChatMessage, receivedAt: Instant, scenarioKind: String? = null,
                          scenarioDescription: String? = null,
                          reminderOpeningQuestion: String? = null) {
        if (ai.vocabulary(telegramSessionId(message.chat.id), "list").lesson?.status in setOf("active", "offer", "cards")) {
            reply(message, "Your vocabulary lesson is active. Finish it before starting a call.")
            return
        }
        val retryHint = if (scenarioKind == null) "🎙 Start call" else PRACTICE_SITUATION_BUTTON
        val sessionId = telegramSessionId(message.chat.id)
        val chatId = callChat(message)
        val startKey = "start:${message.chat.id}:${message.messageId.long}"
        if (chatId != null) callEvent(CallEvent(startKey, "start_pressed", chatId, receivedAt,
            messageId = message.messageId.long, state = "received", scenarioKind = scenarioKind ?: "free"))
        if (chatId != null) callEvent(CallEvent(startKey, "start_pressed", chatId, receivedAt,
            messageId = message.messageId.long, state = "eligible"))
        log.info("Reading call status")
        val callStatus = ai.callStatus(sessionId)
        if (callStatus.active) {
            if (chatId != null) callEvent(CallEvent(startKey, "start_pressed", chatId, receivedAt,
                callId = callStatus.callId, messageId = message.messageId.long, state = "already_active"))
            log.info("Call already active")
            reply(message, CALL_ALREADY_ACTIVE_TEXT, replyMarkup = ReplyKeyboardRemove())
            return
        }
        log.info("Starting call")
        sendMessage(message.chat.id, START_CALL_CONNECTING, replyMarkup = ReplyKeyboardRemove())
        showStatus(message.chat, RecordVoiceAction)
        val opening = try {
            withRecordVoiceAction(message.chat) {
                ai.startCall(sessionId, (message.chat as? PrivateChat)?.firstName,
                    scenarioKind, scenarioDescription, reminderOpeningQuestion)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val opened = try { ai.callStatus(sessionId) } catch (_: Throwable) { null }
            if (chatId != null) callEvent(CallEvent(startKey, "start_pressed", chatId, receivedAt,
                callId = opened?.callId, messageId = message.messageId.long, state = "failed"))
            if (chatId != null && opened?.callId != null) callEvent(CallEvent(
                "call:${opened.callId}:open", "call_open", chatId, receivedAt,
                username = telegramProfile(message.chat).username.orEmpty(),
                callId = opened.callId, messageId = message.messageId.long,
                seconds = opened.goalSeconds, state = "button", scenarioKind = scenarioKind ?: "free"))
            if (chatId != null && opened?.callId != null)
                callCharacter(chatId, opened.callId, opened.character, receivedAt)
            if (chatId != null && opened?.callId != null && opened.startedUnix != null)
                callEvent(CallEvent("call:${opened.callId}:actual-open", "call_actual_open",
                    chatId, Instant.ofEpochMilli((opened.startedUnix * 1000).toLong()),
                    callId = opened.callId))
            log.warn("Failed to prepare call opening for {}", sessionId.value, error)
            reply(message, "I couldn't start the conversation. Tap $retryHint to try again.",
                allowSendingWithoutReply = true, replyMarkup = startCallKeyboard())
            return
        }
        rollover(message, opening.closedPreviousCallId, opening.closedPreviousUnix)
        if (chatId != null) {
            callEvent(CallEvent(startKey, "start_pressed", chatId, receivedAt, callId = opening.callId,
                messageId = message.messageId.long, state = opening.status))
            callEvent(CallEvent("call:${opening.callId}:open", "call_open", chatId, receivedAt,
                username = telegramProfile(message.chat).username.orEmpty(),
                callId = opening.callId, messageId = message.messageId.long,
                seconds = opening.goalSeconds, state = "button", scenarioKind = scenarioKind ?: "free"))
            callCharacter(chatId, opening.callId, opening.character, receivedAt)
            opening.startedUnix?.let { unix -> callEvent(CallEvent(
                "call:${opening.callId}:actual-open", "call_actual_open", chatId,
                Instant.ofEpochMilli((unix * 1000).toLong()), callId = opening.callId)) }
        }
        opening.unseenCallId?.let { callId ->
            reply(message, CALL_YESTERDAY_TEXT, allowSendingWithoutReply = true,
                replyMarkup = callYesterdayKeyboard(callId))
        }
        if (opening.status == "active") {
            reply(message, CALL_ALREADY_ACTIVE_TEXT, replyMarkup = ReplyKeyboardRemove())
            return
        }
        check(opening.status == "ready") { "Unexpected call opening status: ${opening.status}" }
        log.info("Sending call opening")
        val question = requireNotNull(opening.question)
        auditGenerated(message, question, "call_opening")
        val audio = Base64.getDecoder().decode(requireNotNull(opening.audioBase64))
        clearProgress(message.chat)
        val voice = try {
            showStatus(message.chat, RecordVoiceAction)
            sendVoice(message.chat.id, audio.asMultipartFile("call-opening.${voiceExtension(requireNotNull(opening.audioContentType))}"),
                replyMarkup = callKeyboard(opening.todaySeconds, opening.goalSeconds, spoken = true,
                    callId = opening.callId))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (chatId != null) callEvent(CallEvent(startKey, "start_pressed", chatId, receivedAt,
                callId = opening.callId, messageId = message.messageId.long, state = "delivery_failed"))
            log.warn("Failed to send call opening for {}", sessionId.value, error)
            reply(message, "I couldn't start the conversation. Tap $retryHint to try again.",
                allowSendingWithoutReply = true, replyMarkup = startCallKeyboard())
            return
        }
        spokenLines[spokenKey(message.chat.id, voice.messageId)] = question
        if (chatId != null) callEvent(CallEvent(startKey, "start_pressed", chatId, receivedAt,
            callId = opening.callId, messageId = message.messageId.long, state = "delivered"))
        if (chatId != null) callEvent(CallEvent("call:${opening.callId}:starter", "starter_delivered",
            chatId, callId = opening.callId, botMessageId = voice.messageId.long))
        progressMessages[message.chat.id.toString()] = voice.messageId
        log.info("Confirming call starter")
        try {
            ai.markCallStarterDelivered(opening.callId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to confirm call starter delivery for {}", opening.callId, error)
        }
    }
    suspend fun voice(message: ChatMessage, content: VoiceContent, receivedAt: Instant,
                      receivedNs: Long, chatQueueNs: Long, terminalOutcome: AtomicBoolean) {
        val voiceStarted = TimeSource.Monotonic.markNow()
        val sessionId = telegramSessionId(message.chat.id)
        val requestId = "message:${message.messageId}"
        val activeCall = ai.callStatus(sessionId)
        if (!activeCall.active) return
        val practiceCallId = activeCall.callId
        val hideCallKeyboard = false
        log.info("Recording funnel voice")
        try {
            ai.recordFunnelVoice(sessionId, telegramProfile(message.chat))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to record voice for {}", sessionId.value, error)
        }
        log.info("Ensuring session")
        ai.ensureSession(sessionId)
        log.info("Queued voice clip")
        callChat(message)?.let { chatId -> practiceCallId?.let { callId ->
            callEvent(CallEvent("voice:$chatId:${message.messageId.long}:received", "voice_received",
                chatId, receivedAt, callId = callId, messageId = message.messageId.long,
                seconds = content.media.duration?.toDouble()))
        } }
        recordVoiceAttempt(message, receivedAt, eligible = true)
        val setupMs = voiceStarted.elapsedNow().inWholeMilliseconds
        val queueStarted = System.nanoTime()
        val processingStarted = AtomicLong(0)
        val downloadMs = AtomicLong(0)
        val started = System.nanoTime()
        var replyReady = false
        var downloadFailed = false
        try {
        val result = withRecordVoiceAction(message.chat) {
            sessionClipQueue.submit(
                sessionId = sessionId,
                onProcessingStart = { processingStarted.set(System.nanoTime()) },
                source = {
                    log.info("Downloading voice")
                    val downloadStarted = TimeSource.Monotonic.markNow()
                    val bytes = try {
                        if (exceedsTelegramDownloadLimit(content.media.fileSize?.bytes)) {
                            throw VoiceFileTooLargeException()
                        }
                        downloadFile(content.media)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        val failure = when {
                            error is VoiceFileTooLargeException -> error
                            isTelegramFileTooLarge(error) -> VoiceFileTooLargeException(error)
                            else -> error
                        }
                        log.warn("Voice download failed", failure)
                        downloadFailed = true
                        telegramChatNumber(message.chat.id)?.let { chatId ->
                            try { audit?.record(InteractionEvent(
                                id = "audio-unavailable:${voiceAttemptId(chatId, message.messageId.long)}",
                                chatId = chatId, direction = "incoming", kind = "audio", status = "download_failed",
                                receivedAt = receivedAt, messageId = message.messageId.long,
                                attemptId = voiceAttemptId(chatId, message.messageId.long),
                            )) } catch (auditError: Throwable) {
                                log.warn("Could not record voice download failure", auditError)
                            }
                        }
                        throw failure
                    }
                    downloadMs.set(downloadStarted.elapsedNow().inWholeMilliseconds)
                    telegramChatNumber(message.chat.id)?.let { chatId ->
                        try {
                            if (audit?.saveAudio(chatId, message.messageId.long, receivedAt, bytes) == false) {
                                audit.record(InteractionEvent(
                                    id = "audio-unavailable:${voiceAttemptId(chatId, message.messageId.long)}",
                                    chatId = chatId, direction = "incoming", kind = "audio", status = "not_saved",
                                    receivedAt = receivedAt, messageId = message.messageId.long,
                                    attemptId = voiceAttemptId(chatId, message.messageId.long),
                                ))
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            log.warn("Incoming voice archive failed attempt_id={}", voiceAttemptId(chatId, message.messageId.long), error)
                        }
                    }
                    AudioClip(
                        bytes, "audio/ogg", "voice.ogg",
                        requestId = requestId,
                        durationSeconds = (content.media.duration ?: 0L).toDouble(),
                        attemptId = telegramChatNumber(message.chat.id)
                            ?.let { voiceAttemptId(it, message.messageId.long) },
                        receivedAtEpoch = receivedAt.epochSecond.toDouble(),
                    )
                },
            )
        }
        val processedAt = processingStarted.get()
        val queueMs = if (processedAt == 0L) 0L else (processedAt - queueStarted) / 1_000_000
        val processingMs = if (processedAt == 0L) 0L else (System.nanoTime() - processedAt) / 1_000_000
        val deliveryStarted = TimeSource.Monotonic.markNow()
        when (result) {
            ClipSubmitResult.QueueFull -> {
                operationalMetrics?.recordOutcome("queue_full")
                terminalOutcome.set(true)
                log.warn("Voice clip queue is full")
                recordVoiceAttempt(message, receivedAt, eligible = true, outcome = "queue_full", stage = "queue",
                    reason = "internal", setupMs = setupMs, totalMs = voiceStarted.elapsedNow().inWholeMilliseconds)
                callChat(message)?.let { chatId -> practiceCallId?.let { callId ->
                    callEvent(CallEvent("voice:$chatId:${message.messageId.long}:outcome",
                        "voice_outcome", chatId, callId = callId,
                        messageId = message.messageId.long, state = "queue_full"))
                    callEvent(CallEvent("voice:$chatId:${message.messageId.long}:failure",
                        "voice_failure", chatId, callId = callId,
                        messageId = message.messageId.long, state = "queue/internal"))
                } }
                reply(message, QUEUE_FULL_TEXT)
            }
            is ClipSubmitResult.Completed -> {
                replyReady = true
                telegramChatNumber(message.chat.id)?.let { chatId ->
                    val attemptId = voiceAttemptId(chatId, message.messageId.long)
                    try {
                        if (result.reply.transcript.isNotBlank()) audit?.record(InteractionEvent(
                            id = "stt:$attemptId", chatId = chatId, direction = "internal", kind = "transcript",
                            status = "ready", receivedAt = receivedAt, messageId = message.messageId.long,
                            attemptId = attemptId, jobId = result.reply.jobId, content = result.reply.transcript,
                        ))
                        if (result.reply.text.isNotBlank()) audit?.record(InteractionEvent(
                            id = "generated:$attemptId", chatId = chatId, direction = "internal", kind = "reply",
                            status = "generated", receivedAt = receivedAt, messageId = message.messageId.long,
                            attemptId = attemptId, jobId = result.reply.jobId, content = result.reply.text,
                        ))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        log.warn("Voice content audit failed attempt_id={}", attemptId, error)
                    }
                    result.reply.audio?.let { replyAudio ->
                        try {
                            if (audit?.saveReplyAudio(
                                    chatId, message.messageId.long, receivedAt, replyAudio.bytes, replyAudio.contentType,
                                ) == false
                            ) {
                                log.warn("Model voice was not archived attempt_id={}", attemptId)
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            log.warn("Model voice archive failed attempt_id={}", attemptId, error)
                        }
                    }
                }
                val callId = result.reply.call?.callId ?: practiceCallId
                val analyticsChatId = callChat(message)
                if (analyticsChatId != null && callId != null) {
                    callEvent(CallEvent("voice:$analyticsChatId:${message.messageId.long}:processed",
                        "voice_processed", analyticsChatId, callId = callId,
                        messageId = message.messageId.long,
                        seconds = result.reply.call?.recognizedSeconds,
                        state = result.reply.call?.durationSource?.let { source ->
                            if (source == "recording_fallback")
                                "$source:${result.reply.call.durationFallbackReason ?: "unknown"}" else source
                        },
                        amount = result.reply.corrections.size))
                }
                if (result.reply.call != null &&
                    (hideCallKeyboard || progressMessages[message.chat.id.toString()] == null)
                ) {
                    hideStartCallKeyboard(message.chat)
                }
                result.reply.call?.callId?.takeIf { it.isNotBlank() }?.let { callId ->
                    try {
                        val firstReply = ai.noteCallVoice(sessionId, callId, message.messageId.long).firstReplyToStarter
                        if (firstReply) setMessageReaction(message.chat.id, message.messageId, "👍")
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        log.warn("Failed to record or react to call voice for {}", sessionId.value, error)
                    }
                }
                val delivered = try {
                    val sent = deliver(message, result.reply,
                        onCorrection = { deliveredCard ->
                            if (analyticsChatId != null && callId != null) callEvent(CallEvent(
                                "voice:$analyticsChatId:${message.messageId.long}:correction",
                                "correction_card", analyticsChatId, callId = callId,
                                messageId = message.messageId.long, state = if (deliveredCard) "delivered" else "failed"))
                        },
                        onMainDelivered = { botId, kind ->
                            if (analyticsChatId != null && callId != null) callEvent(CallEvent(
                                "voice:$analyticsChatId:${message.messageId.long}:reply",
                                "reply_delivered", analyticsChatId, callId = callId,
                                messageId = message.messageId.long, botMessageId = botId,
                                milliseconds = (System.nanoTime() - receivedNs).coerceAtLeast(0) / 1_000_000,
                                state = kind))
                        })
                    if (sent) operationalMetrics?.recordDelivery(true)
                    sent
                } catch (error: Throwable) {
                    if (error !is CancellationException) operationalMetrics?.recordDelivery(false)
                    throw error
                }
                if (delivered) {
                    operationalMetrics?.recordDelivered(
                        (System.nanoTime() - receivedNs) / 1_000_000_000.0,
                        (chatQueueNs + (processedAt - queueStarted).coerceAtLeast(0)) / 1_000_000_000.0,
                    )
                }
                terminalOutcome.set(true)
                val deliveryOutcome = when {
                    delivered && result.reply.audio != null -> "delivered"
                    delivered && result.reply.text.isNotBlank() -> "text_fallback"
                    else -> "other_failed"
                }
                if (deliveryOutcome == "delivered" && result.reply.transcript.isNotBlank()) {
                    operationalMetrics?.recordCompletedVoiceExchange()
                    val costAttemptId = telegramChatNumber(message.chat.id)?.let {
                        voiceAttemptId(it, message.messageId.long)
                    }
                    if (voiceCost != null && costAttemptId != null) {
                        try {
                            voiceCost.recordExchange(costAttemptId, expectedCalls = result.reply.voiceCallCounts)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            log.error("Could not record voice cost exchange", error)
                        }
                    }
                }
                recordVoiceAttempt(message, receivedAt, eligible = true, outcome = deliveryOutcome,
                    stage = when (deliveryOutcome) {
                        "delivered" -> null
                        "text_fallback" -> "tts"
                        else -> "telegram_delivery"
                    },
                    reason = if (deliveryOutcome == "delivered") null else "internal", setupMs = setupMs,
                    queueMs = queueMs, downloadMs = downloadMs.get(), processingMs = processingMs,
                    deliveryMs = deliveryStarted.elapsedNow().inWholeMilliseconds,
                    totalMs = voiceStarted.elapsedNow().inWholeMilliseconds,
                    jobId = result.reply.jobId, timingsMs = result.reply.timingsMs)
                if (analyticsChatId != null && callId != null && deliveryOutcome != "delivered")
                    callEvent(CallEvent("voice:$analyticsChatId:${message.messageId.long}:outcome",
                        "voice_outcome", analyticsChatId, callId = callId,
                        messageId = message.messageId.long,
                        milliseconds = (System.nanoTime() - receivedNs).coerceAtLeast(0) / 1_000_000,
                        state = deliveryOutcome))
                if (analyticsChatId != null && callId != null && deliveryOutcome != "delivered")
                    callEvent(CallEvent("voice:$analyticsChatId:${message.messageId.long}:failure",
                        "voice_failure", analyticsChatId, callId = callId,
                        messageId = message.messageId.long,
                        state = if (deliveryOutcome == "text_fallback") "tts/internal" else "telegram_delivery/internal"))
            }
        }
        log.info(
            "Telegram voice stages session={} request={} setup_ms={} queue_ms={} download_ms={} processing_ms={} delivery_ms={} total_ms={}",
            sessionId.value, requestId, setupMs, queueMs, downloadMs.get(), processingMs,
            deliveryStarted.elapsedNow().inWholeMilliseconds, voiceStarted.elapsedNow().inWholeMilliseconds,
        )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val failedAt = when {
                replyReady -> "delivery_failed" to "telegram_delivery"
                downloadFailed -> "download_failed" to "telegram_download"
                error is ClipUploadFailure -> "upload_rejected" to "upload"
                error is ClipPollingTimeout -> "ai_timeout" to "other"
                error is ClipJobFailure && error.code == "timeout" -> "ai_timeout" to aiFailureStage(error.stage)
                error is ClipJobFailure -> "ai_failed" to aiFailureStage(error.stage)
                else -> "other_failed" to "other"
            }
            if (!downloadFailed) telegramChatNumber(message.chat.id)?.let { chatId ->
                val attemptId = voiceAttemptId(chatId, message.messageId.long)
                try {
                    val artifacts = ai.auditAttempt(attemptId)
                    artifacts["transcript"]?.takeIf { it.isNotBlank() }?.let { transcript ->
                        audit?.record(InteractionEvent(
                            id = "stt:$attemptId", chatId = chatId, direction = "internal", kind = "transcript",
                            status = "ready", receivedAt = receivedAt, messageId = message.messageId.long,
                            attemptId = attemptId, content = transcript,
                        ))
                    }
                    artifacts["reply"]?.takeIf { it.isNotBlank() }?.let { replyText ->
                        audit?.record(InteractionEvent(
                            id = "generated:$attemptId", chatId = chatId, direction = "internal", kind = "reply",
                            status = "generated", receivedAt = receivedAt, messageId = message.messageId.long,
                            attemptId = attemptId, content = replyText,
                        ))
                    }
                } catch (artifactError: CancellationException) {
                    throw artifactError
                } catch (artifactError: Throwable) {
                    log.warn("Failed to recover AI audit artifacts attempt_id={}", attemptId, artifactError)
                }
            }
            val failureCode = when {
                error is VoiceFileTooLargeException -> "file_too_large"
                error is ClipJobFailure -> error.reason.takeIf { it in setOf("timeout", "rate_limit", "provider_5xx", "provider_4xx", "network", "invalid_input", "internal") }
                    ?: localFailureReason(error)
                error is ClipUploadFailure -> error.reason
                failedAt.first == "ai_timeout" -> "timeout"
                else -> localFailureReason(error)
            }
            recordVoiceAttempt(message, receivedAt, eligible = true, outcome = failedAt.first, stage = failedAt.second,
                reason = failureCode,
                setupMs = setupMs, downloadMs = downloadMs.get(), totalMs = voiceStarted.elapsedNow().inWholeMilliseconds,
                jobId = (error as? ClipJobFailure)?.jobId ?: (error as? ClipPollingTimeout)?.jobId,
                timingsMs = (error as? ClipJobFailure)?.timingsMs.orEmpty())
            callChat(message)?.let { chatId -> practiceCallId?.let { callId ->
                callEvent(CallEvent("voice:$chatId:${message.messageId.long}:outcome",
                    "voice_outcome", chatId, callId = callId,
                    messageId = message.messageId.long,
                    milliseconds = (System.nanoTime() - receivedNs).coerceAtLeast(0) / 1_000_000,
                    state = failedAt.first))
                callEvent(CallEvent("voice:$chatId:${message.messageId.long}:failure",
                    "voice_failure", chatId, callId = callId,
                    messageId = message.messageId.long, state = "${failedAt.second}/$failureCode"))
            } }
            throw error
        }
    }
    suspend fun handle(message: ChatMessage, isVoice: Boolean = false,
                       receivedAt: Instant = Instant.now(), onActionStart: (Long) -> Unit = {},
                       terminalOutcome: AtomicBoolean? = null,
                       block: suspend () -> Unit) {
        withRequestLog(telegramSessionId(message.chat.id).value, "message:${message.messageId}") {
        val handleStarted = TimeSource.Monotonic.markNow()
        if (isVoice) recordVoiceAttempt(message, receivedAt)
        var outcome = "ok"
        val input = (message as? ContentMessage<*>)?.content
        val action = when (input) {
            is VoiceContent -> "voice"
            is TextContent -> userAction(input.text)
            else -> "other"
        }
        log.info("User action {}", action)
        noteUserAction(ai, log, telegramSessionId(message.chat.id), action, "message:${message.messageId.long}")
        try {
            actions.run(
                chatId = message.chat.id.toString(),
                requestId = "message:${message.messageId}",
                voice = isVoice,
                onActionStart = { waitNanos ->
                    onActionStart(waitNanos)
                    if (isVoice) recordVoiceAttempt(message, receivedAt,
                        chatQueueMs = waitNanos.coerceAtLeast(0) / 1_000_000)
                },
                onQueued = { reply(message, QUEUED_TEXT) },
                onFull = {
                    if (isVoice) {
                        operationalMetrics?.recordOutcome("queue_full")
                        terminalOutcome?.set(true)
                        recordVoiceAttempt(message, receivedAt, eligible = true,
                            outcome = "queue_full", stage = "queue", reason = "internal",
                            totalMs = handleStarted.elapsedNow().inWholeMilliseconds)
                    }
                    reply(message, QUEUE_FULL_TEXT)
                },
                action = block,
            )
        } catch (error: CancellationException) {
            outcome = "cancelled"
            throw error
        } catch (error: Throwable) {
            if (isVoice && terminalOutcome?.compareAndSet(false, true) == true) {
                operationalMetrics?.recordOutcome("failed")
            }
            outcome = "error"
            if (isVoice) recordVoiceAttempt(message, receivedAt, eligible = true,
                outcome = if (error is VoiceFileTooLargeException) "download_failed" else "other_failed",
                stage = if (error is VoiceFileTooLargeException) "telegram_download" else "other",
                reason = if (error is VoiceFileTooLargeException) "file_too_large" else localFailureReason(error),
                totalMs = handleStarted.elapsedNow().inWholeMilliseconds)
            log.error("Telegram message failed for {}", message.chat.id, error)
            reply(message, if (error is VoiceFileTooLargeException) VOICE_TOO_LARGE_TEXT else ERROR_TEXT,
                allowSendingWithoutReply = true)
        } finally {
            if (isVoice) {
                log.info(
                    "Telegram voice handled session={} request=message:{} outcome={} elapsed_ms={}",
                    telegramSessionId(message.chat.id).value, message.messageId, outcome,
                    handleStarted.elapsedNow().inWholeMilliseconds,
                )
            } else {
                log.info("Finished handling message outcome={}", outcome)
            }
        }
        }
    }
    suspend fun showCallLevel(message: ChatMessage, callId: String, asNewMessage: Boolean = false) {
        log.info("Reviewing call")
        val chatId = callChat(message)
        if (chatId != null) callEvent(CallEvent("call:$callId:review-request", "review_requested",
            chatId, callId = callId))
        val review = try {
            coroutineScope {
                val typing = if (asNewMessage) launch {
                    while (isActive) {
                        showStatus(message.chat, TypingAction)
                        delay(4_000)
                    }
                } else null
                try {
                    ai.reviewCall(callId)
                } finally {
                    typing?.cancelAndJoin()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to review call {}", callId, error)
            null
        }
        val retry = review == null || review.retry
        val insufficient = !retry && review.reviewKind == "insufficient"
        val generatedEvent = if (insufficient) "review_insufficient" else "review_generated"
        val deliveredEvent = if (insufficient) "review_insufficient_delivered" else "review_delivered"
        if (!retry && chatId != null) callEvent(CallEvent("call:$callId:$generatedEvent",
            generatedEvent, chatId, callId = callId))
        if (retry && chatId != null) callEvent(CallEvent("call:$callId:review-failed",
            "review_failed", chatId, callId = callId))
        val start = if (retry) null else callReviewStart(review)
        val card = start?.first ?: buildEntities { regular(CALL_RETRY_TEXT) }
        val markup = if (retry) callRetryKeyboard(callId)
            else start?.second?.let { callSlideKeyboard(it, callId) } ?: noInlineKeyboard
        if (asNewMessage) {
            try {
                if (insufficient) {
                    reply(message, card, allowSendingWithoutReply = true,
                        replyMarkup = startCallKeyboard())
                } else {
                    reply(message, CALL_REVIEW_DONE_TEXT, allowSendingWithoutReply = true,
                        replyMarkup = startCallKeyboard())
                    sendMessage(message.chat.id, card, replyMarkup = markup)
                }
            } catch (error: CancellationException) { throw error }
              catch (error: Throwable) {
                  if (chatId != null) callEvent(CallEvent("call:$callId:review-delivery-failed",
                      "review_delivery_failed", chatId, callId = callId))
                  throw error
              }
            if (!retry && chatId != null) callEvent(CallEvent("call:$callId:$deliveredEvent",
                deliveredEvent, chatId, callId = callId))
            return
        }
        try {
            editMessageText(message.chat.id, message.messageId, card, replyMarkup = markup)
            if (!retry && chatId != null) callEvent(CallEvent("call:$callId:$deliveredEvent",
                deliveredEvent, chatId, callId = callId))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to edit call level for {}", message.chat.id, error)
            try {
                reply(message, card, allowSendingWithoutReply = true, replyMarkup = markup)
            } catch (sendError: CancellationException) { throw sendError }
              catch (sendError: Throwable) {
                  if (chatId != null) callEvent(CallEvent("call:$callId:review-delivery-failed",
                      "review_delivery_failed", chatId, callId = callId))
                  throw sendError
              }
            if (!retry && chatId != null) callEvent(CallEvent("call:$callId:$deliveredEvent",
                deliveredEvent, chatId, callId = callId))
        }
    }
    suspend fun showCallSlide(message: ChatMessage, callback: CallCallback) {
        log.info("Showing call slide")
        val review = ai.reviewCall(callback.callId)
        if (review.retry) {
            reply(message, CALL_RETRY_TEXT, allowSendingWithoutReply = true, replyMarkup = callRetryKeyboard(callback.callId))
            return
        }
        if (review.reviewKind == "insufficient") return
        val slide = callReviewSlide(review, callback.action)
        val markup = callSlideKeyboard(slide.second, callback.callId)
        try {
            editMessageText(message.chat.id, message.messageId, slide.first, replyMarkup = markup)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to edit call slide for {}", message.chat.id, error)
            reply(message, slide.first, allowSendingWithoutReply = true, replyMarkup = markup)
        }
    }
    suspend fun showCallProgress(message: ChatMessage, callId: String) {
        log.info("Showing call progress")
        val review = ai.reviewCall(callId)
        if (review.retry) {
            reply(message, CALL_RETRY_TEXT, allowSendingWithoutReply = true, replyMarkup = callRetryKeyboard(callId))
            return
        }
        if (review.reviewKind == "insufficient") return
        val progress = if (review.isNewFullReview()) {
            callFinalLevelMessage(review, offerReminder = reminderMissing(message))
        } else {
            callProgressMessage(review, offerReminder = reminderMissing(message))
        }
        try {
            editMessageText(message.chat.id, message.messageId, progress, replyMarkup = noInlineKeyboard)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to edit call progress for {}", message.chat.id, error)
            reply(message, progress, allowSendingWithoutReply = true)
        }
        showModeFeedback(message, callId)
    }

    suspend fun endPracticeCall(message: ChatMessage, callbackId: String, pressedAt: Instant,
                                expectedCallId: String) {
        log.info("Ending call")
        callChat(message)?.let { chatId -> callEvent(CallEvent("end:$callbackId", "end_pressed",
            chatId, pressedAt, callId = expectedCallId.takeIf { it.isNotBlank() })) }
        val ended = ai.endCall(telegramSessionId(message.chat.id))
        val callId = ended.callId
        callChat(message)?.let { chatId -> callEvent(CallEvent("end:$callbackId", "end_pressed",
            chatId, pressedAt, callId = callId)) }
        callClosed(message, callId,
            ended.endedUnix?.let { Instant.ofEpochMilli((it * 1000).toLong()) }, "end_button")
        if (callId.isNullOrBlank()) {
            showStartCallKeyboard(message.chat)
            return
        }
        ended.lastVoiceMessageId?.let { lastVoiceId ->
            try {
                setMessageReaction(message.chat.id, MessageId(lastVoiceId), "❤")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.warn("Failed to react to the last call voice for {}", callId, error)
            }
        }
        clearProgress(message.chat)
        showCallLevel(message, callId, asNewMessage = true)
    }
    onDataCallbackQuery { query ->
        val callbackReceivedAt = Instant.now()
        val callbackMessage = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage
        withRequestLog(
            session = callbackMessage?.let { telegramSessionId(it.chat.id).value },
            request = "callback:${query.id}",
        ) {
        log.info("User action {}", callbackAction(query.data))
        callbackMessage?.let { message ->
            noteUserAction(ai, log, telegramSessionId(message.chat.id), callbackAction(query.data))
        }
        // Stop Telegram's spinner before waiting for synthesis or the per-chat queue.
        try {
            answerCallbackQuery(query)
            callbackMessage?.let { message ->
                telegramChatNumber(message.chat.id)?.let { chatId ->
                    try { audit?.record(InteractionEvent(
                        id = "callback-answer:${query.id}", chatId = chatId, direction = "outgoing",
                        kind = "answerCallbackQuery", status = "accepted", messageId = message.messageId.long,
                    )) } catch (auditError: Throwable) {
                        log.warn("Could not record callback answer", auditError)
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to answer callback", error)
            callbackMessage?.let { message ->
                telegramChatNumber(message.chat.id)?.let { chatId ->
                    try { audit?.record(InteractionEvent(
                        id = "callback-answer:${query.id}", chatId = chatId, direction = "outgoing",
                        kind = "answerCallbackQuery", status = "failed", messageId = message.messageId.long,
                    )) } catch (auditError: Throwable) {
                        log.warn("Could not record callback answer failure", auditError)
                    }
                }
            }
        }
        if (query.data.startsWith("vocab:")) {
            val message = callbackMessage ?: return@withRequestLog
            try {
                actions.run(message.chat.id.toString(), "vocab:${query.id}") {
                    val sessionId = telegramSessionId(message.chat.id)
                    val parts = query.data.split(':')
                    val action = parts.getOrNull(1).orEmpty()
                    val itemId = parts.getOrNull(2).orEmpty()
                    val page = parts.getOrNull(3)?.toIntOrNull() ?: 0
                    if (action == "start") vocabularyFact(message, "start_pressed", "start:${query.id}")
                    if (action == "progress") {
                        val current = ai.vocabulary(sessionId, "list").lesson
                        reply(message, if ((current?.format ?: 3) >= 4)
                            "White means not started, yellow means you understand the word, and green means you used it in your own phrase."
                            else "Green circles show words practised in this round. The count restarts in the next round.",
                            allowSendingWithoutReply = true)
                        return@run
                    }
                    if (action == "subtitle") {
                        val spoken = spokenLines[spokenKey(message.chat.id, message.messageId)]
                            ?: ai.vocabulary(sessionId, "subtitle", messageId = message.messageId.long).text
                        if (spoken.isBlank()) reply(message, "Subtitles are unavailable for this message.",
                            allowSendingWithoutReply = true)
                        else reply(message, spokenQuote(spoken), allowSendingWithoutReply = true)
                        return@run
                    }
                    val currentLesson = ai.vocabulary(sessionId, "list").lesson
                    if (action == "start" && currentLesson?.status in setOf("active", "offer", "cards")) {
                        showVocabularyList(message)
                        return@run
                    }
                    if (((currentLesson?.status == "active" && currentLesson.round == "Recall") ||
                        currentLesson?.status == "cards") &&
                        action !in setOf("hint", "end", "progress", "subtitle", "retry")) {
                        showVocabularyList(message)
                        return@run
                    }
                    if (currentLesson?.status in setOf("active", "offer", "cards") &&
                        action in setOf("add", "choose", "unchoose", "delask", "delete", "confirm", "start")) {
                        showVocabularyList(message)
                        return@run
                    }
                    when (action) {
                        "retry" -> {
                            val sourceMessageId = parts.getOrNull(3)?.toLongOrNull()
                            vocabularyFact(message, "retry_pressed", "retry:${query.id}", itemId,
                                sourceMessageId = sourceMessageId ?: 0)
                            if (sourceMessageId == null || sourceMessageId <= 0 || currentLesson?.id != itemId) {
                                editMessageReplyMarkup(message.chat.id, message.messageId, replyMarkup = noInlineKeyboard)
                                reply(message, "This retry is no longer available.", allowSendingWithoutReply = true)
                            } else try {
                                sendVocabularyVoiceTurn(message) {
                                    ai.vocabulary(sessionId, "retry", lessonId = itemId,
                                        messageId = sourceMessageId)
                                }
                                vocabularyFact(message, "retry_succeeded", "retry-ok:$itemId:$sourceMessageId", itemId,
                                    sourceMessageId = sourceMessageId)
                                editMessageReplyMarkup(message.chat.id, message.messageId, replyMarkup = noInlineKeyboard)
                            } catch (error: CancellationException) { throw error
                            } catch (error: Throwable) {
                                vocabularyFact(message, "retry_failed", "retry-failed:${query.id}", itemId,
                                    category = error.javaClass.simpleName, sourceMessageId = sourceMessageId)
                                log.warn("Vocabulary retry failed for chat={} lesson={} message={}",
                                    message.chat.id, itemId, sourceMessageId, error)
                                reply(message, "Still couldn't finish that response. Try Retry again in a moment.",
                                    allowSendingWithoutReply = true)
                            }
                        }
                        "list" -> showVocabularyList(message, parts.getOrNull(2)?.toIntOrNull() ?: 0, edit = true)
                        "add" -> {
                            vocabularyFact(message, "word_add_pressed", "add:${query.id}")
                            vocabularyAddPrompts.add(message.chat.id.toString())
                            reply(message, "Send one English word or short expression to add.",
                                allowSendingWithoutReply = true)
                        }
                        "choose", "unchoose" -> {
                            val state = ai.vocabulary(sessionId, "list")
                            if (action == "choose" && itemId !in state.selected && state.selected.size >= 5) {
                                reply(message, "Choose up to five words. Remove one before selecting another.",
                                    allowSendingWithoutReply = true)
                            } else {
                                ai.vocabulary(sessionId, "select", itemId = itemId, selected = action == "choose")
                                vocabularyFact(message, "word_selected", "selection:${query.id}", itemId = itemId,
                                    category = if (action == "choose") "selected" else "removed")
                                showVocabularyList(message, page, edit = true)
                            }
                        }
                        "card", "delask" -> {
                            val state = ai.vocabulary(sessionId, "list")
                            val card = state.items.find { it.id == itemId }
                            if (card == null) showVocabularyList(message, page, edit = true)
                            else if (action == "card") editMessageText(message.chat.id, message.messageId,
                                vocabularyCardText(card), replyMarkup = vocabularyCardKeyboard(card, page, card.id in state.selected))
                            else editMessageText(message.chat.id, message.messageId,
                                "Delete ${card.word} from your vocabulary?", replyMarkup = vocabularyDeleteKeyboard(card, page))
                        }
                        "delete" -> {
                            ai.vocabulary(sessionId, "delete", itemId = itemId)
                            showVocabularyList(message, page, edit = true)
                        }
                        "confirm" -> {
                            val saved = ai.vocabulary(sessionId, "confirm")
                            saved.card?.let { vocabularyFact(message, "word_confirmed", "confirm:${query.id}", itemId = it.id) }
                            showVocabularyList(message, 0, edit = false)
                        }
                        "cancel" -> {
                            ai.vocabulary(sessionId, "cancel")
                            showVocabularyList(message, 0, edit = false)
                        }
                        "start" -> {
                            if (ai.callStatus(sessionId).active) {
                                reply(message, "End your current call before starting a vocabulary lesson.",
                                    allowSendingWithoutReply = true)
                            } else {
                                sendMessage(message.chat.id, START_CALL_CONNECTING,
                                    replyMarkup = ReplyKeyboardRemove())
                                showStatus(message.chat, RecordVoiceAction)
                                vocabularyAddPrompts.remove(message.chat.id.toString())
                                val started = sendVocabularyVoiceTurn(message) {
                                    ai.vocabulary(sessionId, "start").also {
                                        editMessageReplyMarkup(message.chat.id, message.messageId,
                                            replyMarkup = noInlineKeyboard)
                                    }
                                }
                                started.lesson?.let { vocabularyFact(message, "opening_delivered", "opening:${it.id}", it.id) }
                            }
                        }
                        "hint" -> {
                            val current = ai.vocabulary(sessionId, "list").lesson
                            if (current?.id != itemId || current.index != page) {
                                reply(message, "That hint button belongs to an earlier exercise.",
                                    allowSendingWithoutReply = true)
                            } else if (current.status != "active" || current.phase !in setOf("Understand", "Use")) {
                                reply(message, "Hints are available during Understand and Use.",
                                    allowSendingWithoutReply = true)
                            } else if (current.hints >= 1) {
                                reply(message, "You've already used the hint for this question.",
                                    allowSendingWithoutReply = true)
                            } else {
                                val hint = ai.vocabulary(sessionId, "hint", lessonId = itemId)
                                vocabularyFact(message, "hint_pressed", "hint:${query.id}", itemId)
                                if (hint.deliveredMessageId == null) {
                                    val sent = reply(message, "💡 ${hint.text}", allowSendingWithoutReply = true)
                                    acknowledgeVocabularyTurn(message, hint, sent.messageId.long)
                                }
                            }
                        }
                        "play" -> {
                            editMessageReplyMarkup(message.chat.id, message.messageId, replyMarkup = noInlineKeyboard)
                            sendVocabularyVoiceTurn(message) {
                                ai.vocabulary(sessionId, "play", lessonId = itemId)
                            }
                        }
                        "skip" -> {
                            val result = ai.vocabulary(sessionId, "skip", lessonId = itemId)
                            editMessageReplyMarkup(message.chat.id, message.messageId, replyMarkup = noInlineKeyboard)
                            result.summary?.let { sendMessage(message.chat.id, it, replyMarkup = startCallKeyboard()) }
                        }
                        "end" -> {
                            vocabularyFact(message, "end_pressed", "end:${query.id}", itemId)
                            val result = ai.vocabulary(sessionId, "end", lessonId = itemId)
                            editMessageReplyMarkup(message.chat.id, message.messageId, replyMarkup = noInlineKeyboard)
                            if (result.summary != null) {
                                val sent = sendMessage(message.chat.id, result.summary,
                                    replyMarkup = startCallKeyboard())
                                vocabularyFact(message, "summary_delivered", "summary:$itemId", itemId,
                                    botMessageId = sent.messageId.long)
                            }
                            else reply(message, "That lesson has already ended.", allowSendingWithoutReply = true,
                                replyMarkup = startCallKeyboard())
                        }
                        else -> showVocabularyList(message, 0)
                    }
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Throwable) {
                vocabularyFact(message, "telegram_error", "callback-error:${query.id}",
                    phase = query.data.split(':').getOrNull(1).orEmpty(),
                    category = error.javaClass.simpleName)
                log.warn("Vocabulary action failed for {}", message.chat.id, error)
                reply(message, "That action is no longer available. Open Vocabulary Builder to see the current state.",
                    allowSendingWithoutReply = true)
            }
            return@withRequestLog
        }
        if (callbackMessage != null && reminderButtons != null) {
            val chatId = telegramChatNumber(callbackMessage.chat.id)
            if (chatId != null && reminderButtons.isStale(chatId, callbackMessage.messageId.long)) {
                reply(callbackMessage, "This reminder has been replaced. Please use the latest one above.",
                    allowSendingWithoutReply = true)
                return@withRequestLog
            }
            if (chatId != null) try {
                reminderButtons.markClicked(chatId, callbackMessage.messageId.long, callbackReceivedAt)
            } catch (error: CancellationException) { throw error
            } catch (error: Throwable) {
                log.warn("Could not record reminder button click", error)
            }
        }
        if (query.data.startsWith("fu:practice:")) {
            val message = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@withRequestLog
            val chatId = telegramChatNumber(message.chat.id)
            val started = if (chatId != null)
                handleReminderPracticeButton(query.data, chatId, message.messageId.long, reminderButtons) { question ->
                    actions.run(message.chat.id.toString(), "followup:${query.id}") {
                        startCall(message, callbackReceivedAt, reminderOpeningQuestion = question)
                    }
                }
            else false
            if (!started) {
                reply(message, "This reminder is no longer available. Please use the latest one.",
                    allowSendingWithoutReply = true)
            }
            return@withRequestLog
        }
        if (query.data == "fu:practice") {
            val message = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@withRequestLog
            actions.run(message.chat.id.toString(), "followup:${query.id}") {
                startCall(message, callbackReceivedAt)
            }
            return@withRequestLog
        }

        if (query.data == VOICE_MENU_CALLBACK) {
            val message = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@withRequestLog
            callChat(message)?.let { chatId -> callEvent(CallEvent(
                "voice-menu:${query.id}", "voice_menu_clicked", chatId, callbackReceivedAt,
                botMessageId = message.messageId.long, state = "received")) }
            try {
                actions.run(message.chat.id.toString(), "voice-menu:${query.id}") {
                    sendVoiceSelector(message)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.error("Voice menu failed for {}", message.chat.id, error)
                reply(message, "Please send /voice to choose a partner.", allowSendingWithoutReply = true)
            }
            return@withRequestLog
        }
        if (query.data == "voice:kora") {
            val message = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@withRequestLog
            reply(message, "Kora is no longer available. Use /voice to choose Speaky or Kora H.",
                allowSendingWithoutReply = true)
            return@withRequestLog
        }
        val selectedCharacter = parseVoiceCallback(query.data)
        if (selectedCharacter != null) {
            val message = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@withRequestLog
            callChat(message)?.let { chatId -> callEvent(CallEvent(
                "voice-choice:${query.id}", "voice_character_tapped", chatId, callbackReceivedAt,
                botMessageId = message.messageId.long, state = selectedCharacter)) }
            try {
                actions.run(message.chat.id.toString(), "voice:${query.id}") {
                    val character = ai.setCharacter(telegramSessionId(message.chat.id), selectedCharacter)
                    val notice = voiceSelectionNotice(character)
                    if (notice != null) {
                        reply(message, notice, allowSendingWithoutReply = true)
                    } else {
                        val text = voiceMessage(character)
                        val shownText = ((message as? ContentMessage<*>)?.content as? TextContent)?.text
                        if (shownText != text.joinToString("") { it.source }) {
                            try {
                                editMessageText(message.chat.id, message.messageId, text, replyMarkup = voiceKeyboard(character))
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Throwable) {
                                reply(message, text, allowSendingWithoutReply = true, replyMarkup = voiceKeyboard(character))
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.error("Character selection failed for {}", message.chat.id, error)
                reply(message, ERROR_TEXT, allowSendingWithoutReply = true)
            }
            return@withRequestLog
        }
        val selectedSpeed = parseSpeedCallback(query.data)
        if (selectedSpeed != null) {
            log.info("Updating speech speed")
            val message = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@withRequestLog
            try {
                actions.run(message.chat.id.toString(), "speed:${query.id}") {
                    val speed = ai.setSpeechSpeed(telegramSessionId(message.chat.id), selectedSpeed)
                    val shownText = ((message as? ContentMessage<*>)?.content as? TextContent)?.text
                    if (shownText != speedMessage(speed)) {
                        try {
                            editMessageText(
                                message.chat.id, message.messageId, speedMessage(speed),
                                replyMarkup = speedKeyboard(speed),
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Throwable) {
                            reply(message, speedMessage(speed), allowSendingWithoutReply = true, replyMarkup = speedKeyboard(speed))
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.error("Speech speed update failed for {}", message.chat.id, error)
                reply(message, ERROR_TEXT, allowSendingWithoutReply = true)
            }
            return@withRequestLog
        }
        if (query.data == REMINDER_STOP_CALLBACK) {
            log.info("Stopping reminder")
            val message = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@withRequestLog
            try {
                actions.run(message.chat.id.toString(), "remind:${query.id}") {
                    ai.scheduleReminder(telegramSessionId(message.chat.id), "stop:${query.id}", "clear")
                    clearMessageMarkup(message)
                    reply(message, REMINDER_STOPPED, allowSendingWithoutReply = true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.error("Reminder stop failed for {}", message.chat.id, error)
                reply(message, ERROR_TEXT, allowSendingWithoutReply = true)
            }
            return@withRequestLog
        }
        val feedbackCallback = parseCallFeedbackCallback(query.data)
        if (feedbackCallback != null) {
            val message = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@withRequestLog
            try {
                actions.run(message.chat.id.toString(), "callfb:${query.id}") {
                    val result = ai.callFeedback(telegramSessionId(message.chat.id), feedbackCallback.action,
                        callId = feedbackCallback.callId, choice = feedbackCallback.choice)
                    if (result.status == "rated") {
                        callChat(message)?.let { chatId -> callEvent(CallEvent(
                            "call:${feedbackCallback.callId}:feedback-rated", "feedback_rated", chatId,
                            callbackReceivedAt, callId = feedbackCallback.callId,
                            state = feedbackCallback.choice)) }
                        val question = if (feedbackCallback.choice == "liked")
                            FIRST_CALL_FEEDBACK_LIKED_QUESTION else FIRST_CALL_FEEDBACK_IMPROVE_QUESTION
                        try {
                            editMessageText(message.chat.id, message.messageId, question,
                                replyMarkup = firstCallFeedbackSkipKeyboard(feedbackCallback.callId))
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            log.warn("Failed to edit first-call feedback question for {}", message.chat.id, error)
                            reply(message, question, allowSendingWithoutReply = true,
                                replyMarkup = firstCallFeedbackSkipKeyboard(feedbackCallback.callId))
                        }
                    } else if (feedbackCallback.action == "skip") {
                        try {
                            editMessageText(message.chat.id, message.messageId, FIRST_CALL_FEEDBACK_THANKS,
                                replyMarkup = noInlineKeyboard)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            log.warn("Failed to edit skipped first-call feedback for {}", message.chat.id, error)
                            reply(message, FIRST_CALL_FEEDBACK_THANKS, allowSendingWithoutReply = true)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.error("First-call feedback failed for {}", message.chat.id, error)
                reply(message, ERROR_TEXT, allowSendingWithoutReply = true)
            }
            return@withRequestLog
        }
        val callCallback = parseCallCallback(query.data)
        if (callCallback != null) {
            log.info("Handling call callback {}", callCallback.action)
            val message = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@withRequestLog
            try {
                actions.run(message.chat.id.toString(), "call:${query.id}") {
                    when (callCallback.action) {
                        "clock" -> reply(message, CALL_CLOCK_HINT, allowSendingWithoutReply = true)
                        "profile" -> sendProfile(message)
                        "bye" -> reply(message, SEE_YOU_TOMORROW, allowSendingWithoutReply = true,
                            replyMarkup = startCallKeyboard())
                        "end" -> endPracticeCall(message, query.id.toString(), callbackReceivedAt,
                            callCallback.callId)
                        "progress", "level" -> showCallProgress(message, callCallback.callId)
                        "grammar", "vocab", "fluency" -> showCallSlide(message, callCallback)
                        else -> showCallLevel(message, callCallback.callId)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.error("Call callback failed for {}", message.chat.id, error)
                reply(message, ERROR_TEXT, allowSendingWithoutReply = true)
            }
            return@withRequestLog
        }
        if (query.data == SPOKEN_TEXT_CALLBACK) {
            log.info("Showing spoken text")
            val voiceMessage = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@withRequestLog
            val chatId = callChat(voiceMessage)
            val spoken = spokenLines[spokenKey(voiceMessage.chat.id, voiceMessage.messageId)]
            var shown = false
            try {
                if (spoken != null) {
                    reply(voiceMessage, spokenQuote(spoken), allowSendingWithoutReply = true)
                    shown = true
                }
            } finally {
                val callId = if (chatId != null) try {
                    withTimeoutOrNull(200) { callEvents?.callIdForBot(chatId, voiceMessage.messageId.long) }
                } catch (error: CancellationException) { throw error }
                  catch (error: Throwable) { log.warn("Call subtitle lookup failed", error); null }
                  else null
                if (chatId != null) callEvent(CallEvent("subtitle:${query.id}", "subtitle_click",
                    chatId, callbackReceivedAt, callId = callId,
                    botMessageId = voiceMessage.messageId.long,
                    state = if (shown) "shown" else "unavailable"))
            }
            return@withRequestLog
        }
        if (query.data == PROFILE_STREAK_CALLBACK) {
            log.info("Showing profile streak")
            val message = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@withRequestLog
            sendStreak(message, "profile:${query.id}")
            return@withRequestLog
        }
        }
    }
    onCommand("start", requireOnlyCommandInMessage = false) { message ->
        if (isStartCommand(message.content.text)) {
            val receivedAt = telegramChatNumber(message.chat.id)?.let { audit?.receiptTime(it, message.messageId.long) }
                ?: Instant.now()
            handle(message, receivedAt = receivedAt) { greet(message, message.content.text, receivedAt = receivedAt) }
        }
    }
    onCommand("onboarding", requireOnlyCommandInMessage = false) { message ->
        if (isOnboardingCommand(message.content.text)) handle(message) { greet(message, message.content.text, force = true, trigger = "onboarding_command") }
    }
    onCommand("profile", requireOnlyCommandInMessage = false) { message ->
        if (isProfileCommand(message.content.text)) handle(message) { sendProfile(message) }
    }
    onCommand("streak", requireOnlyCommandInMessage = false) { message ->
        if (isStreakCommand(message.content.text)) handle(message) { sendStreak(message) }
    }
    onCommand("remind", requireOnlyCommandInMessage = false) { message ->
        if (isRemindCommand(message.content.text)) handle(message) { sendRemind(message) }
    }
    onCommand("voice", requireOnlyCommandInMessage = false) { message ->
        if (isVoiceCommand(message.content.text)) handle(message) { sendVoiceSelector(message) }
    }
    onCommand("speed", requireOnlyCommandInMessage = false) { message ->
        if (isSpeedCommand(message.content.text)) handle(message) { sendSpeed(message) }
    }
    onCommand("words", requireOnlyCommandInMessage = false) { message ->
        if (wordsCommand.matches(message.content.text)) handle(message) { showVocabularyList(message) }
    }
    onContentMessage { message ->
        val receivedAt = telegramChatNumber(message.chat.id)?.let { audit?.receiptTime(it, message.messageId.long) }
            ?: Instant.now()
        val receivedNs = System.nanoTime()
        val chatQueueNs = AtomicLong(0)
        val terminalOutcome = AtomicBoolean(false)
        handle(message, isVoice = message.content is VoiceContent, receivedAt = receivedAt,
            onActionStart = { chatQueueNs.set(it) }, terminalOutcome = terminalOutcome) {
            when (val content = message.content) {
                is VoiceContent -> {
                    customScenarioPrompts.remove(message.chat.id.toString())
                    clearScenarioMenu(message.chat)
                    val lesson = ai.vocabulary(telegramSessionId(message.chat.id), "list").lesson
                    if (lesson?.status == "active" || lesson?.status == "cards") {
                        vocabularyFact(message, "answer_received", "answer:${message.chat.id}:${message.messageId}",
                            lesson.id, phase = lesson.phase, category = "voice")
                        if (exceedsTelegramDownloadLimit(content.media.fileSize?.bytes)) {
                            reply(message, "This recording is too large. Please send a shorter one; your attempt is safe.")
                        } else try {
                            val started = System.nanoTime()
                            sendVocabularyVoiceTurn(message) {
                                val bytes = downloadFile(content.media)
                                ai.vocabularyVoice(telegramSessionId(message.chat.id), lesson.id,
                                    message.messageId.long, bytes)
                            }
                            vocabularyFact(message, "answer_delivered", "answer-delivered:${lesson.id}:${message.messageId}",
                                lesson.id, phase = lesson.phase, category = "voice",
                                milliseconds = (System.nanoTime() - started) / 1_000_000)
                        } catch (error: CancellationException) { throw error
                        } catch (error: Throwable) {
                            vocabularyFact(message, "telegram_error", "voice-error:${lesson.id}:${message.messageId}",
                                lesson.id, phase = lesson.phase, category = error.javaClass.simpleName)
                            log.warn("Vocabulary voice failed for {}", message.chat.id, error)
                            reply(message, VOCABULARY_RETRY_TEXT,
                                replyMarkup = vocabularyRetryKeyboard(lesson.id, message.messageId.long))
                        }
                    } else if (lesson?.status == "offer") {
                        reply(message, "Tap Play or Skip under the last lesson message.")
                    } else voice(message, content, receivedAt, receivedNs, chatQueueNs.get(), terminalOutcome)
                }
                is TextContent -> {
                    val vocabularyMenu = content.text == VOCABULARY_BUTTON || wordsCommand.matches(content.text)
                    val lesson = if (vocabularyMenu) null
                        else ai.vocabulary(telegramSessionId(message.chat.id), "list").lesson
                    when {
                    vocabularyMenu -> {
                        vocabularyAddPrompts.remove(message.chat.id.toString())
                        showVocabularyList(message)
                        vocabularyFact(message, "menu_delivered", "menu:${message.chat.id}:${message.messageId}")
                    }
                    lesson?.status in setOf("active", "offer", "cards") -> {
                        val currentLesson = checkNotNull(lesson)
                        if ((currentLesson.status != "active" &&
                             !(currentLesson.status == "cards" && currentLesson.format >= 4)) ||
                            content.text.startsWith("/")) {
                            reply(message, "Continue with the last lesson message, or tap End lesson.")
                        } else try {
                            vocabularyFact(message, "answer_received", "answer:${message.chat.id}:${message.messageId}",
                                currentLesson.id, phase = currentLesson.phase, category = "text")
                            val started = System.nanoTime()
                            sendVocabularyVoiceTurn(message) {
                                ai.vocabulary(telegramSessionId(message.chat.id), "answer",
                                    lessonId = currentLesson.id, text = content.text, messageId = message.messageId.long)
                            }
                            vocabularyFact(message, "answer_delivered", "answer-delivered:${currentLesson.id}:${message.messageId}",
                                currentLesson.id, phase = currentLesson.phase, category = "text",
                                milliseconds = (System.nanoTime() - started) / 1_000_000)
                        } catch (error: CancellationException) { throw error
                        } catch (error: Throwable) {
                            vocabularyFact(message, "telegram_error", "text-error:${currentLesson.id}:${message.messageId}",
                                currentLesson.id, phase = currentLesson.phase, category = error.javaClass.simpleName)
                            log.warn("Vocabulary text failed for {}", message.chat.id, error)
                            reply(message, VOCABULARY_RETRY_TEXT,
                                replyMarkup = vocabularyRetryKeyboard(currentLesson.id, message.messageId.long))
                        }
                    }
                    vocabularyAddPrompts.remove(message.chat.id.toString()) && !content.text.startsWith("/") -> {
                        vocabularyFact(message, "word_add_attempt", "add-attempt:${message.chat.id}:${message.messageId}")
                        try {
                            val proposed = ai.vocabulary(telegramSessionId(message.chat.id), "propose", text = content.text)
                            val card = proposed.card
                            if (card == null) reply(message, "I couldn't prepare that word. Try another expression.")
                            else reply(message, vocabularyCardText(card), allowSendingWithoutReply = true,
                                replyMarkup = vocabularyDraftKeyboard())
                            if (card != null) vocabularyFact(message, "word_proposed", "proposed:${message.chat.id}:${message.messageId}")
                        } catch (error: CancellationException) { throw error
                        } catch (error: Throwable) {
                            vocabularyFact(message, "telegram_error", "proposal-error:${message.chat.id}:${message.messageId}",
                                phase = "propose", category = error.javaClass.simpleName)
                            log.warn("Vocabulary word proposal failed", error)
                            vocabularyAddPrompts.add(message.chat.id.toString())
                            reply(message, "I couldn't identify that word. Please send one English word or short expression.")
                        }
                    }
                    isStartCommand(content.text) -> greet(message, content.text, receivedAt = receivedAt)
                    isOnboardingCommand(content.text) -> greet(message, content.text, force = true, trigger = "onboarding_command")
                    isStreakCommand(content.text) -> sendStreak(message)
                    isProfileCommand(content.text) -> sendProfile(message)
                    isRemindCommand(content.text) -> sendRemind(message)
                    isVoiceCommand(content.text) -> sendVoiceSelector(message)
                    isSpeedCommand(content.text) -> sendSpeed(message)
                    isStartCallButton(content.text) -> {
                        if (ai.vocabulary(telegramSessionId(message.chat.id), "list").lesson?.status in setOf("active", "offer", "cards")) {
                            reply(message, "Your vocabulary lesson is active. Finish it before starting a call.")
                        } else {
                            customScenarioPrompts.remove(message.chat.id.toString())
                            clearScenarioMenu(message.chat)
                            startCall(message, receivedAt)
                        }
                    }
                    content.text == PRACTICE_SITUATION_BUTTON -> {
                        val key = message.chat.id.toString()
                        customScenarioPrompts.remove(key)
                        clearScenarioMenu(message.chat)
                        val menu = reply(message, "Choose a situation to practise:",
                            allowSendingWithoutReply = true, replyMarkup = scenarioKeyboard())
                        scenarioMenus[key] = menu.messageId
                        callChat(message)?.let { chatId -> callEvent(CallEvent(
                            "scenario:menu:$chatId:${message.messageId.long}", "scenario_menu_opened",
                            chatId, receivedAt, messageId = message.messageId.long,
                            botMessageId = menu.messageId.long,
                        )) }
                    }
                    content.text.startsWith("/") -> log.info("Ignoring command")
                    customScenarioPrompts.containsKey(message.chat.id.toString()) -> {
                        val key = message.chat.id.toString()
                        val description = content.text.trim()
                        callChat(message)?.let { chatId -> callEvent(CallEvent(
                            "scenario:description:$chatId:${message.messageId.long}",
                            if (description.isEmpty() || description.length > 500) "custom_description_invalid"
                            else "custom_description_submitted",
                            chatId, receivedAt, messageId = message.messageId.long,
                            botMessageId = customScenarioPrompts[key]?.long,
                        )) }
                        if (description.isEmpty() || description.length > 500) {
                            reply(message, "Please describe the situation in up to 500 characters.",
                                allowSendingWithoutReply = true)
                        } else {
                            customScenarioPrompts.remove(key)
                            scenarioMenus.remove(key)?.let { menuId ->
                                try {
                                    editMessageText(message.chat.id, menuId, "✍️ Custom Scenario",
                                        replyMarkup = noInlineKeyboard)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Throwable) {
                                    log.warn("Could not close custom scenario prompt for {}", key, error)
                                }
                            }
                            startCall(message, receivedAt, "custom", description)
                        }
                    }
                    else -> {
                        val scheduled = ai.scheduleReminder(telegramSessionId(message.chat.id),
                            "text:${message.messageId}", "submit", text = content.text)
                        when (scheduled.status) {
                            "saved" -> reply(message, reminderSaved(scheduled.time.orEmpty()), allowSendingWithoutReply = true)
                            "invalid" -> reply(message, REMINDER_TIME_PROMPT, allowSendingWithoutReply = true)
                            else -> tutorChat.respond(message, content.text, receivedAt)
                        }
                    }
                }
                }
                else -> log.info("Ignoring message")
            }
        }
    }
}

private suspend fun BehaviourContext.clearMessageMarkup(message: ChatMessage) {
    try {
        editMessageReplyMarkup(message.chat.id, message.messageId, replyMarkup = noInlineKeyboard)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
    }
}

private fun processingMillis(started: Long): Int =
    ((System.nanoTime() - started) / 1_000_000L).toInt().coerceAtLeast(0)

private fun analyticsFailure(error: Throwable): String {
    return if (error is ClipJobFailure && error.code == "onboarding_stt_failed") "stt_failure" else "processing_failure"
}
