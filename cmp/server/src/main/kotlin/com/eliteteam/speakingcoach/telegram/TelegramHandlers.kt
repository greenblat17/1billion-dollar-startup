package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.ChatProfile
import com.eliteteam.speakingcoach.withRequestLog
import com.eliteteam.speakingcoach.ai.HttpClipClient
import com.eliteteam.speakingcoach.ai.ClipJobFailure
import com.eliteteam.speakingcoach.ai.ClipUploadFailure
import com.eliteteam.speakingcoach.ai.ClipPollingTimeout
import com.eliteteam.speakingcoach.ai.OnboardingReview
import com.eliteteam.speakingcoach.ai.voiceExtension
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
import com.eliteteam.speakingcoach.analytics.voiceAttemptId
import com.eliteteam.speakingcoach.analytics.safely
import com.eliteteam.speakingcoach.speaking.ClipReply
import com.eliteteam.speakingcoach.speaking.AudioClip
import com.eliteteam.speakingcoach.speaking.ClipSubmitResult
import com.eliteteam.speakingcoach.speaking.SessionClipQueue
import com.eliteteam.speakingcoach.TelegramOperationalMetrics
import com.eliteteam.speakingcoach.speaking.SessionId
import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.api.send.sendBotAction
import dev.inmo.tgbotapi.extensions.api.send.withRecordVoiceAction
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.buttons.ReplyKeyboardRemove
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onDataCallbackQuery
import dev.inmo.tgbotapi.types.queries.callback.AbstractMessageCallbackQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
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

private fun onboardingActionStage(action: String): String = when (action) {
    "begin" -> "first_question"
    "level", "see", "results", "vocab", "fluency", "finish", "profile", "bye" -> "result_card"
    "skip", "m5", "m10", "m15" -> "goal"
    "remind", "later" -> "reminder"
    "retry" -> "result_delivery"
    else -> "other"
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
    isSpeedCommand(text) -> "speed"
    isStartCallButton(text) -> "start-call"
    text == PRACTICE_SITUATION_BUTTON -> "practice-situation"
    text.startsWith("/") -> "command"
    else -> "text"
}

private fun callbackAction(data: String): String = when {
    parseCallFeedbackCallback(data) != null -> "callback:call-feedback"
    data == LEGACY_ONBOARDING_CALLBACK -> "callback:legacy-onboarding"
    parseSpeedCallback(data) != null -> "callback:speed"
    data == REMINDER_STOP_CALLBACK -> "callback:reminder-stop"
    data == ONBOARDING_PROGRESS_CALLBACK -> "callback:onboarding-progress"
    data == SPOKEN_TEXT_CALLBACK -> "callback:spoken-text"
    data == PROFILE_STREAK_CALLBACK -> "callback:streak"
    data.startsWith("scenario:") -> "callback:scenario"
    else -> parseCallCallback(data)?.let { "callback:call:${it.action}" }
        ?: parseOnboardingCallback(data)?.let { "callback:${it.action}" }
        ?: "callback"
}

private val startSourcePattern = Regex("^[A-Za-z0-9_-]{1,64}$")

internal fun isStartCommand(text: String): Boolean = isCommand(text, "/start")

internal fun isOnboardingCommand(text: String): Boolean = isCommand(text, "/onboarding")

internal fun isProfileCommand(text: String): Boolean = isCommand(text, "/profile")

internal fun isStreakCommand(text: String): Boolean = isCommand(text, "/streak")

internal fun isRemindCommand(text: String): Boolean = isCommand(text, "/remind")

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

internal fun speakingCoachTelegramBot(token: String, audit: InteractionAudit? = null) =
    telegramBot(token) { logger = RedactingKSLog(DefaultKTgBotAPIKSLog, token) }
        .let { if (audit == null) it else AuditedTelegramBot(it, audit) }

internal fun BehaviourContext.installSpeakingCoachHandlers(
    ai: HttpClipClient,
    sessionClipQueue: SessionClipQueue,
    analytics: OnboardingAnalytics? = null,
    voiceAttempts: VoiceAttemptRecorder? = null,
    operationalMetrics: TelegramOperationalMetrics? = null,
    audit: InteractionAudit? = null,
    callEvents: CallEventRecorder? = null,
) {
    val log = LoggerFactory.getLogger("TelegramHandlers")
    val actions = TelegramChatActions()
    val answeredStreaks = ConcurrentHashMap.newKeySet<String>()
    val founderNotes = ConcurrentHashMap.newKeySet<String>()
    val progressMessages = ConcurrentHashMap<String, MessageId>()
    val onboardingReminderCards = ConcurrentHashMap<String, Pair<String, MessageId>>()
    val spokenLines = ConcurrentHashMap<String, String>()
    val scenarioMenus = ConcurrentHashMap<String, MessageId>()
    val customScenarioPrompts = ConcurrentHashMap<String, MessageId>()
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
    suspend fun showStatus(chat: Chat, action: BotAction) {
        try {
            sendBotAction(chat, action)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to show Telegram action {} for {}", action.actionName, chat.id, error)
        }
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
    suspend fun canStartCall(chat: Chat): Boolean = try {
        val sessionId = telegramSessionId(chat.id)
        val profile = ai.progressProfile(sessionId)
        profile.assessment?.overallScore != null && profile.dailyMinutes != null && !ai.callStatus(sessionId).active
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        log.warn("Failed to check call availability for {}", chat.id, error)
        false
    }
    suspend fun sendFounderNote(chat: Chat, runId: String) {
        if (runId in founderNotes) return
        val keyboard = if (canStartCall(chat)) startCallKeyboard() else null
        sendMessage(chat.id, FOUNDER_NOTE, replyMarkup = keyboard)
        founderNotes.add(runId)
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
    suspend fun deliver(message: ChatMessage, result: ClipReply, firstQuestion: Boolean = false,
                        onCorrection: (Boolean) -> Unit = {},
                        onMainDelivered: (Long, String) -> Unit = { _, _ -> }): Boolean {
        if (result.onboarding?.status == "ignored") return false
        log.info("Sending Telegram reply")
        val deliveryStarted = TimeSource.Monotonic.markNow()
        val onboarding = result.onboarding?.takeIf { it.status in setOf("active", "pending", "completed") }
        val finished = onboarding?.takeIf { it.status == "completed" && it.review != null }
        val practice = result.call?.takeIf { onboarding == null }
        val progress = when {
            finished != null -> onboardingKeyboard("level", finished.runId)
            onboarding?.status == "pending" -> null
            onboarding != null -> onboardingProgressKeyboard(onboarding.seconds)
            else -> null
        }
        if (onboarding != null || practice != null) clearProgress(message.chat)
        if (practice?.goalJustCrossed == true) {
            optionalCard(message, "goal") {
                reply(message, callGoalReached(practice.goalSeconds), allowSendingWithoutReply = true)
            }
        }
        if (onboarding != null) {
            result.corrections.minByOrNull { it.priority }?.let { correction ->
                showStatus(message.chat, TypingAction)
                optionalCard(message, "correction") {
                    reply(message, onboardingCorrection(correction), allowSendingWithoutReply = true)
                }
            }
        } else if (practice != null) {
            result.corrections.minByOrNull { it.priority }?.let { correction ->
                showStatus(message.chat, TypingAction)
                var sent = false
                optionalCard(message, "correction") {
                    reply(message, onboardingCorrection(correction), allowSendingWithoutReply = true)
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
                text = if (firstQuestion) ONBOARDING_VOICE_HINT else null,
                replyMarkup = practice?.let { callKeyboard(it.todaySeconds, it.goalSeconds, spoken, it.callId) }
                    ?: withSpokenText(progress, spoken),
            )
            onMainDelivered(voice.messageId.long, "audio")
            if (spoken) spokenLines[spokenKey(message.chat.id, voice.messageId)] = result.text.trim()
            if (progress != null || practice != null) progressMessages[message.chat.id.toString()] = voice.messageId
            if (onboarding?.status == "pending") {
                optionalCard(message, "retry") {
                    reply(message, "I couldn't prepare your result. Please try again.",
                        allowSendingWithoutReply = true, replyMarkup = onboardingKeyboard("retry", onboarding.runId))
                }
            }
        } else if (result.text.isNotBlank()) {
            val state = result.onboarding
            val action = state?.takeIf { it.status == "pending" }
                ?.let { onboardingKeyboard("retry", it.runId) }
            val sent = reply(
                message,
                result.text,
                allowSendingWithoutReply = true,
                replyMarkup = when {
                    progress != null && action != null -> progress + action
                    else -> progress ?: action
                },
            )
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
        customScenarioPrompts.remove(message.chat.id.toString())
        clearScenarioMenu(message.chat)
        val sessionId = telegramSessionId(message.chat.id)
        val requestId = if (force) "force:${message.messageId}" else "message:${message.messageId}"
        if (force) {
            log.info("Sealing open call")
            try {
                val ended = ai.endCall(sessionId, "onboarding_reset")
                callClosed(message, ended.callId,
                    ended.endedUnix?.let { Instant.ofEpochMilli((it * 1000).toLong()) }, "onboarding_reset")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.warn("Failed to seal a call before onboarding for {}", sessionId.value, error)
            }
        }
        // Resolve eligibility before today's funnel event makes a new user look existing.
        log.info("Reading onboarding state")
        val state = ai.onboardingState(sessionId, requestId, if (force) "force" else "start")
        val source = startSource(text)
        if (trigger == "start" && state.status != "waiting") analytics.safely {
            recordEntry(sessionId.value, requestId, receivedAt, false, trigger, "existing_user", source, null)
        }
        log.info("Recording funnel start")
        try {
            ai.recordFunnelStart(sessionId, source, telegramProfile(message.chat))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to record start for {}", sessionId.value, error)
        }
        if (state.status == "waiting") {
            log.info("Sending onboarding invitation")
            var invitedAt: Instant? = null
            var invitationError: String? = null
            try {
                clearProgress(message.chat)
                if (force) sendMessage(message.chat.id, "Let's start again.", replyMarkup = ReplyKeyboardRemove())
                reply(
                    message,
                    onboardingInvitation((message.chat as? PrivateChat)?.firstName),
                    allowSendingWithoutReply = true,
                    replyMarkup = onboardingKeyboard("begin", state.runId),
                )
                invitedAt = Instant.now()
            } catch (error: Throwable) {
                invitationError = localFailureReason(error)
                throw error
            } finally {
                analytics.safely {
                    recordEntry(sessionId.value, requestId, receivedAt, true, trigger, null, source, state.runId, invitedAt,
                        telegramChatNumber(message.chat.id), telegramProfile(message.chat).username, invitationError)
                }
                analytics.safely { startAttempt(sessionId.value, state.runId, trigger, receivedAt, source,
                    telegramChatNumber(message.chat.id), telegramProfile(message.chat).username, invitedAt, invitationError) }
            }
        } else {
            log.info("Starting session")
            val greeting = ai.startSession(sessionId)
            log.info("Reading progress")
            val profile = ai.progressProfile(sessionId)
            val eligible = callGate(state.status, profile.assessment?.overallScore, profile.dailyMinutes, state.legacyUser) == "open"
            if (eligible) log.info("Reading call status")
            val activeCall = eligible && ai.callStatus(sessionId).active
            log.info("Sending start reply")
            reply(
                message, startTextMessage((message.chat as? PrivateChat)?.firstName),
                allowSendingWithoutReply = true,
                replyMarkup = when {
                    activeCall -> ReplyKeyboardRemove()
                    eligible -> startCallKeyboard()
                    else -> null
                },
            )
            log.info("Sending greeting voice")
            auditGenerated(message, greeting.text, "greeting")
            sendVoice(message.chat.id, greeting.audio.bytes.asMultipartFile(greeting.audio.fileName))
        }
    }
    suspend fun startCall(message: ChatMessage, receivedAt: Instant, scenarioKind: String? = null,
                          scenarioDescription: String? = null) {
        val retryHint = if (scenarioKind == null) "🎙 Start call" else PRACTICE_SITUATION_BUTTON
        val sessionId = telegramSessionId(message.chat.id)
        val chatId = callChat(message)
        val startKey = "start:${message.chat.id}:${message.messageId.long}"
        if (chatId != null) callEvent(CallEvent(startKey, "start_pressed", chatId, receivedAt,
            messageId = message.messageId.long, state = "received", scenarioKind = scenarioKind ?: "free"))
        log.info("Reading onboarding state")
        val state = ai.onboardingState(sessionId, "message:${message.messageId}")
        log.info("Reading progress")
        val profile = ai.progressProfile(sessionId)
        when (callGate(state.status, profile.assessment?.overallScore, profile.dailyMinutes, state.legacyUser)) {
            "legacy" -> {
                if (chatId != null) callEvent(CallEvent(startKey, "start_pressed", chatId, receivedAt,
                    messageId = message.messageId.long, state = "legacy"))
                log.info("Sending legacy invitation")
                reply(message, legacyCampaignMessage(), allowSendingWithoutReply = true,
                    replyMarkup = legacyCampaignKeyboard())
                return
            }
            "onboarding" -> {
                if (chatId != null) callEvent(CallEvent(startKey, "start_pressed", chatId, receivedAt,
                    messageId = message.messageId.long, state = "onboarding"))
                log.info("Sending onboarding prompt")
                when (state.status) {
                    "pending" -> reply(message, "I couldn't prepare your result. Please try again.",
                        replyMarkup = onboardingKeyboard("retry", state.runId))
                    "waiting" -> reply(message, ONBOARDING_BEGIN_HINT,
                        replyMarkup = onboardingKeyboard("begin", state.runId))
                    else -> reply(message, "Send a voice message to continue your introduction.")
                }
                return
            }
            "need-onboarding" -> {
                if (chatId != null) callEvent(CallEvent(startKey, "start_pressed", chatId, receivedAt,
                    messageId = message.messageId.long, state = "need_onboarding"))
                greet(message, "/onboarding", force = true)
                return
            }
            "need-goal" -> {
                if (chatId != null) callEvent(CallEvent(startKey, "start_pressed", chatId, receivedAt,
                    messageId = message.messageId.long, state = "need_goal"))
                log.info("Asking practice goal")
                val assessment = profile.assessment
                reply(message, practiceAsk(assessment?.cefr, assessment?.overallScore,
                    assessment?.nextBand, assessment?.pointsToNext),
                    allowSendingWithoutReply = true, replyMarkup = practiceMinutesKeyboard(state.runId))
                return
            }
        }
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
            ai.startCall(sessionId, (message.chat as? PrivateChat)?.firstName,
                scenarioKind, scenarioDescription)
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
        log.info("Reading onboarding state")
        val state = ai.onboardingState(sessionId, requestId)
        var hideCallKeyboard = false
        var practiceCallId: String? = null
        var legacyConversation = false
        if (state.status == "waiting") {
            log.info("Voice waiting for onboarding")
            reply(message, ONBOARDING_BEGIN_HINT, replyMarkup = onboardingKeyboard("begin", state.runId))
            analytics.safely { event(state.runId, requestId, "pre_begin_voice_hint", Instant.now()) }
            recordVoiceAttempt(message, receivedAt, eligible = false, outcome = "not_eligible", reason = "invalid_input")
            return
        }
        if (state.status == "pending") {
            log.info("Voice waiting for onboarding result")
            reply(message, "I couldn't prepare your result. Please try again.", replyMarkup = onboardingKeyboard("retry", state.runId))
            recordVoiceAttempt(message, receivedAt, eligible = false, outcome = "not_eligible", reason = "invalid_input")
            return
        }
        if (state.status != "active") {
            log.info("Reading progress")
            val profile = ai.progressProfile(sessionId)
            when (callGate(state.status, profile.assessment?.overallScore, profile.dailyMinutes, state.legacyUser)) {
                "legacy" -> legacyConversation = true
                "need-onboarding" -> {
                    greet(message, "/onboarding", force = true, trigger = "automatic_restart")
                    recordVoiceAttempt(message, receivedAt, eligible = false, outcome = "not_eligible", reason = "invalid_input")
                    return
                }
                "need-goal" -> {
                    log.info("Asking practice goal")
                    val assessment = profile.assessment
                    reply(
                        message,
                        practiceAsk(assessment?.cefr, assessment?.overallScore, assessment?.nextBand, assessment?.pointsToNext),
                        allowSendingWithoutReply = true,
                        replyMarkup = practiceMinutesKeyboard(state.runId),
                    )
                    recordVoiceAttempt(message, receivedAt, eligible = false, outcome = "not_eligible", reason = "invalid_input")
                    return
                }
                else -> {
                    log.info("Opening call")
                    val opened = ai.openCall(sessionId)
                    practiceCallId = opened.callId
                    rollover(message, opened.closedPreviousCallId, opened.closedPreviousUnix)
                    callChat(message)?.let { chatId ->
                        callEvent(CallEvent("call:${opened.callId}:open", "call_open", chatId, receivedAt,
                            username = telegramProfile(message.chat).username.orEmpty(),
                            callId = opened.callId, messageId = message.messageId.long,
                            seconds = opened.goalSeconds, state = "voice", scenarioKind = "free"))
                        opened.startedUnix?.let { unix -> callEvent(CallEvent(
                            "call:${opened.callId}:actual-open", "call_actual_open", chatId,
                            Instant.ofEpochMilli((unix * 1000).toLong()), callId = opened.callId)) }
                    }
                    hideCallKeyboard = !opened.alreadyActive
                    opened.unseenCallId?.let { callId ->
                        reply(
                            message,
                            CALL_YESTERDAY_TEXT,
                            allowSendingWithoutReply = true,
                            replyMarkup = callYesterdayKeyboard(callId),
                        )
                    }
                }
            }
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
        var completedFacts: OnboardingVoiceFacts? = null
        try {
        val result = withRecordVoiceAction(message.chat) {
            sessionClipQueue.submit(
                sessionId = sessionId,
                onProcessingStart = { processingStarted.set(System.nanoTime()) },
                source = {
                    log.info("Downloading voice")
                    val downloadStarted = TimeSource.Monotonic.markNow()
                    val bytes = try {
                        downloadFile(content.media)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        log.warn("Voice download failed", error)
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
                        throw error
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
                        onboardingRunId = state.runId.takeIf { state.status == "active" || state.status == "completed" },
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
                if (state.status == "active") analytics.safely {
                    recordVoice(state.runId, sessionId.value, requestId,
                        OnboardingVoiceFacts(failureReason = "queue_full", failureStage = "queue", failureCode = "queue_full"),
                        0, Instant.now(), receivedAt)
                }
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
                completedFacts = result.reply.onboarding?.analytics?.withReview(result.reply.onboarding.review)
                val callId = result.reply.call?.callId ?: practiceCallId
                val analyticsChatId = callChat(message)
                if (analyticsChatId != null && callId != null) {
                    callEvent(CallEvent("voice:$analyticsChatId:${message.messageId.long}:processed",
                        "voice_processed", analyticsChatId, callId = callId,
                        messageId = message.messageId.long,
                        seconds = result.reply.call?.recognizedSeconds,
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
                val now = Instant.now()
                val facts = completedFacts
                if (state.status == "active" && facts != null) {
                    analytics.safely { recordVoice(state.runId, sessionId.value, requestId, facts,
                        processingMillis(started), now, receivedAt) }
                    if (facts.completedNow) analytics.safely { mark(state.runId, AttemptMark.RESULT_DELIVERED, now) }
                    if (facts.assessmentFailed) analytics.safely {
                        event(state.runId, "$requestId:failed", "result_build_failed", now, "result_build", "internal")
                    }
                } else if (state.status != "active" && result.reply.transcript.isNotBlank() &&
                    result.reply.text != CLARIFY_TEXT) {
                    analytics.safely { recordReturn(sessionId.value, now) }
                }
                if (legacyConversation && result.reply.transcript.isNotBlank() && result.reply.text.isNotBlank()) {
                    noteOptionalCardMetric(sessionId, "attempted")
                    try {
                        if (ai.legacyInvitation(sessionId, "claim")) {
                            try {
                                sendMessage(message.chat.id, legacyCampaignMessage(afterVoice = true),
                                    replyMarkup = legacyCampaignKeyboard())
                                noteOptionalCardMetric(sessionId, "succeeded")
                            } catch (error: Throwable) {
                                try {
                                    ai.legacyInvitation(sessionId, "release")
                                } catch (releaseError: Throwable) {
                                    log.warn("Failed to release legacy invitation for {}", sessionId.value, releaseError)
                                }
                                throw error
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        noteOptionalCardMetric(sessionId, "failed")
                        log.warn("Failed to send legacy invitation for {}", sessionId.value, error)
                    }
                }
                if (result.reply.onboarding?.status == "completed") {
                    try {
                        setMessageReaction(message.chat.id, message.messageId, "🔥")
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        log.warn("Failed to react to the last onboarding voice for {}", sessionId.value, error)
                    }
                }
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
            telegramChatNumber(message.chat.id)?.let { chatId ->
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
            val failureCode = (error as? ClipJobFailure)?.reason?.takeIf { it in setOf("timeout", "rate_limit", "provider_5xx", "provider_4xx", "network", "invalid_input", "internal") }
                    ?: (error as? ClipUploadFailure)?.reason
                    ?: if (failedAt.first == "ai_timeout") "timeout" else localFailureReason(error)
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
            if (state.status == "active") {
                analytics.safely {
                    recordVoice(
                        state.runId,
                        sessionId.value,
                        requestId,
                        if (replyReady) {
                            (completedFacts ?: OnboardingVoiceFacts()).copy(
                                outcome = "delivery_failure", failureReason = "delivery_failure",
                                failureStage = "telegram_delivery", failureCode = failureCode,
                                grammarExamples = null, vocabularyExamples = null, fluencyMetricsAvailable = null,
                            )
                        } else {
                            OnboardingVoiceFacts(failureReason = analyticsFailure(error),
                                failureStage = when (failedAt.second) {
                                    "reply_llm" -> "llm"
                                    "state" -> "result_build"
                                    else -> failedAt.second
                                }, failureCode = failureCode)
                        },
                        processingMillis(started),
                        Instant.now(),
                        receivedAt,
                    )
                }
            }
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
                    if (isVoice) {
                        try {
                            val sessionId = telegramSessionId(message.chat.id)
                            val state = ai.onboardingState(sessionId, "queue-full:${message.messageId}")
                            if (state.status == "active") analytics.safely {
                                recordVoice(state.runId, sessionId.value, "message:${message.messageId}",
                                    OnboardingVoiceFacts(failureReason = "queue_full"), 0, Instant.now(), receivedAt)
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            log.warn("Could not classify full onboarding queue for {}", message.chat.id, error)
                        }
                    }
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
                outcome = "other_failed", stage = "other", reason = localFailureReason(error),
                totalMs = handleStarted.elapsedNow().inWholeMilliseconds)
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
            ai.reviewCall(callId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to review call {}", callId, error)
            null
        }
        val retry = review == null || review.retry
        if (!retry && chatId != null) callEvent(CallEvent("call:$callId:review-generated",
            "review_generated", chatId, callId = callId))
        if (retry && chatId != null) callEvent(CallEvent("call:$callId:review-failed",
            "review_failed", chatId, callId = callId))
        val card = if (retry) buildEntities { regular(CALL_RETRY_TEXT) } else {
            levelSlide(review.cefr, review.levelText, review.overallScore, review.nextBand, review.pointsToNext)
        }
        val markup = if (retry) callRetryKeyboard(callId) else callSlideKeyboard("grammar", callId)
        if (asNewMessage) {
            try {
                sendMessage(message.chat.id, card, replyMarkup = markup)
            } catch (error: CancellationException) { throw error }
              catch (error: Throwable) {
                  if (chatId != null) callEvent(CallEvent("call:$callId:review-delivery-failed",
                      "review_delivery_failed", chatId, callId = callId))
                  throw error
              }
            if (!retry && chatId != null) callEvent(CallEvent("call:$callId:review-delivered",
                "review_delivered", chatId, callId = callId))
            return
        }
        try {
            editMessageText(message.chat.id, message.messageId, card, replyMarkup = markup)
            if (!retry && chatId != null) callEvent(CallEvent("call:$callId:review-delivered",
                "review_delivered", chatId, callId = callId))
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
            if (!retry && chatId != null) callEvent(CallEvent("call:$callId:review-delivered",
                "review_delivered", chatId, callId = callId))
        }
    }
    suspend fun showCallSlide(message: ChatMessage, callback: CallCallback) {
        log.info("Showing call slide")
        val review = ai.reviewCall(callback.callId)
        if (review.retry) {
            reply(message, CALL_RETRY_TEXT, allowSendingWithoutReply = true, replyMarkup = callRetryKeyboard(callback.callId))
            return
        }
        val wrapped = review.asOnboardingReview()
        val slide = when (callback.action) {
            "grammar" -> grammarSlide(wrapped) to "vocab"
            "vocab" -> vocabularySlide(wrapped) to "fluency"
            else -> fluencySlide(wrapped) to "progress"
        }
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
        val progress = callProgressMessage(review, offerReminder = reminderMissing(message))
        try {
            editMessageText(message.chat.id, message.messageId, progress, replyMarkup = noInlineKeyboard)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to edit call progress for {}", message.chat.id, error)
            reply(message, progress, allowSendingWithoutReply = true)
        }
        try {
            val offered = ai.callFeedback(telegramSessionId(message.chat.id), "offer", callId = callId,
                username = telegramProfile(message.chat).username.orEmpty())
            if (offered.status == "offered") {
                reply(message, FIRST_CALL_FEEDBACK_PROMPT, allowSendingWithoutReply = true,
                    replyMarkup = firstCallFeedbackKeyboard(callId))
            } else {
                log.info("First-call feedback not offered for {}: {}", message.chat.id, offered.reason ?: offered.status)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to show first-call feedback for {}", message.chat.id, error)
        }
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
        reply(message, CALL_REVIEW_WAIT_TEXT, allowSendingWithoutReply = true,
            replyMarkup = startCallKeyboard())
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
        if (query.data == LEGACY_ONBOARDING_CALLBACK) {
            log.info("Starting legacy onboarding")
            val message = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@withRequestLog
            try {
                actions.run(message.chat.id.toString(), "legacy-onboarding:${message.messageId}") {
                    greet(message, "/onboarding", force = true)
                    clearOnboardingMarkup(message)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.error("Legacy onboarding callback failed for {}", message.chat.id, error)
                reply(message, "Please send /onboarding to try again.", allowSendingWithoutReply = true)
            }
            return@withRequestLog
        }
        if (query.data in setOf("scenario:job", "scenario:manager", "scenario:custom", "scenario:back")) {
            val message = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@withRequestLog
            try {
                actions.run(message.chat.id.toString(), "scenario:${query.id}") {
                    val key = message.chat.id.toString()
                    if (scenarioMenus[key] != message.messageId) {
                        editMessageReplyMarkup(message.chat.id, message.messageId, replyMarkup = noInlineKeyboard)
                        return@run
                    }
                    callChat(message)?.let { chatId -> callEvent(CallEvent(
                        "scenario:choice:${query.id}",
                        if (query.data == "scenario:back") "scenario_back" else "scenario_selected",
                        chatId, callbackReceivedAt, botMessageId = message.messageId.long,
                        state = query.data.removePrefix("scenario:"),
                    )) }
                    when (query.data) {
                        "scenario:back" -> {
                            customScenarioPrompts.remove(key)
                            scenarioMenus.remove(key)
                            editMessageText(message.chat.id, message.messageId,
                                "Choose how you'd like to practise.", replyMarkup = noInlineKeyboard)
                        }
                        "scenario:custom" -> {
                            customScenarioPrompts[key] = message.messageId
                            editMessageText(message.chat.id, message.messageId, SCENARIO_PROMPT,
                                replyMarkup = scenarioBackKeyboard())
                        }
                        else -> {
                            scenarioMenus.remove(key)
                            customScenarioPrompts.remove(key)
                            editMessageText(message.chat.id, message.messageId,
                                if (query.data == "scenario:job") "🎯 Job Interview" else "💬 Talk to Your Manager",
                                replyMarkup = noInlineKeyboard)
                            startCall(message, callbackReceivedAt, query.data.removePrefix("scenario:"))
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.error("Scenario selection failed for {}", message.chat.id, error)
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
                    clearOnboardingMarkup(message)
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
                        "progress" -> showCallProgress(message, callCallback.callId)
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
        if (query.data == ONBOARDING_PROGRESS_CALLBACK) {
            log.info("Showing onboarding progress hint")
            val voiceMessage = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@withRequestLog
            reply(voiceMessage, ONBOARDING_PROGRESS_HINT, allowSendingWithoutReply = true)
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
        val callback = parseOnboardingCallback(query.data) ?: return@withRequestLog
        log.info("Handling onboarding callback {}", callback.action)
        val message = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@withRequestLog
        val requestId = onboardingCallbackRequestId(callback.action, callback.runId, query.id.toString())
        try {
            actions.run(message.chat.id.toString(), requestId) {
                analytics.safely { event(callback.runId, "callback:${query.id}:attempt", "action_attempt", Instant.now(),
                    onboardingActionStage(callback.action)) }
                when (callback.action) {
                    "level" -> {
                        val state = ai.onboardingState(
                            telegramSessionId(message.chat.id),
                            "level:${query.id}",
                        )
                        val review = state.review
                        if (state.runId == callback.runId && review != null) {
                            reply(
                                message,
                                levelSlide(
                                    state.cefr,
                                    review.levelText,
                                    state.overallScore,
                                    state.nextBand,
                                    state.pointsToNext,
                                ),
                                allowSendingWithoutReply = true,
                                replyMarkup = onboardingKeyboard("results", callback.runId),
                            )
                            analytics.safely { mark(callback.runId, AttemptMark.RESULTS, Instant.now()) }
                        }
                    }
                    "results", "vocab", "fluency" -> {
                        if (showReviewSlide(ai, log, message, callback)) {
                            val step = when (callback.action) {
                                "results" -> AttemptMark.GRAMMAR
                                "vocab" -> AttemptMark.VOCABULARY
                                else -> AttemptMark.FLUENCY
                            }
                            analytics.safely { mark(callback.runId, step, Instant.now()) }
                        }
                    }
                    "finish" -> {
                        val state = ai.onboardingState(
                            telegramSessionId(message.chat.id),
                            "finish:${query.id}",
                        )
                        val ask = if (state.runId == callback.runId) {
                            practiceAsk(state.cefr, state.overallScore, state.nextBand, state.pointsToNext)
                        } else {
                            practiceAsk(null, null, null)
                        }
                        val markup = practiceMinutesKeyboard(callback.runId)
                        editMessageText(message.chat.id, message.messageId, ask, replyMarkup = markup)
                        if (state.runId == callback.runId) analytics.safely {
                            mark(callback.runId, AttemptMark.PRACTICE, Instant.now())
                        }
                    }
                    "skip" -> {
                        ai.savePracticeGoal(telegramSessionId(message.chat.id), "goal:${query.id}", 0)
                        editMessageReplyMarkup(message.chat.id, message.messageId, replyMarkup = noInlineKeyboard)
                        sendMessage(message.chat.id, practiceSkipped(askReminder = false), replyMarkup = startCallKeyboard())
                        sendMessage(message.chat.id, REMINDER_QUESTION, replyMarkup = reminderAskKeyboard(callback.runId))
                        analytics.safely { markGoal(callback.runId, 0, Instant.now()) }
                        analytics.safely { mark(callback.runId, AttemptMark.REMINDER_OFFERED, Instant.now()) }
                    }
                    "m5", "m10", "m15" -> {
                        val minutes = when (callback.action) {
                            "m5" -> 5
                            "m10" -> 10
                            else -> 15
                        }
                        ai.savePracticeGoal(telegramSessionId(message.chat.id), "goal:${query.id}", minutes)
                        val currentStreak = try {
                            ai.streakProfile(telegramSessionId(message.chat.id)).current
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            log.warn("Failed to load streak after saving daily goal", error)
                            null
                        }
                        val deal = practiceDeal(minutes, currentStreak, askReminder = false)
                        editMessageReplyMarkup(message.chat.id, message.messageId, replyMarkup = noInlineKeyboard)
                        sendMessage(message.chat.id, deal, replyMarkup = startCallKeyboard())
                        sendMessage(message.chat.id, REMINDER_QUESTION, replyMarkup = reminderAskKeyboard(callback.runId))
                        analytics.safely { markGoal(callback.runId, minutes, Instant.now()) }
                        analytics.safely { mark(callback.runId, AttemptMark.REMINDER_OFFERED, Instant.now()) }
                    }
                    "remind" -> {
                        ai.scheduleReminder(
                            telegramSessionId(message.chat.id),
                            "remind:${query.id}",
                            "ask",
                            callback.runId,
                        )
                        editMessageText(message.chat.id, message.messageId, REMINDER_TIME_PROMPT, replyMarkup = noInlineKeyboard)
                        onboardingReminderCards[message.chat.id.toString()] = callback.runId to message.messageId
                        analytics.safely { markReminderDecision(callback.runId, "set_reminder", Instant.now()) }
                    }
                    "later" -> {
                        ai.scheduleReminder(
                            telegramSessionId(message.chat.id),
                            "later:${query.id}",
                            "decline",
                            callback.runId,
                        )
                        onboardingReminderCards.remove(message.chat.id.toString())
                        try {
                            editMessageText(message.chat.id, message.messageId, REMINDER_SKIPPED, replyMarkup = noInlineKeyboard)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            log.warn("Failed to edit reminder choice for {}", message.chat.id, error)
                        }
                        sendFounderNote(message.chat, callback.runId)
                        analytics.safely { markReminderDecision(callback.runId, "not_now", Instant.now()) }
                    }
                    "profile" -> {
                        sendProfile(message)
                        analytics.safely { mark(callback.runId, AttemptMark.PROFILE_OPENED, Instant.now()) }
                        sendFounderNote(message.chat, callback.runId)
                    }
                    "bye" -> {
                        reply(message, SEE_YOU_TOMORROW, allowSendingWithoutReply = true)
                        analytics.safely { mark(callback.runId, AttemptMark.BYE, Instant.now()) }
                        sendFounderNote(message.chat, callback.runId)
                    }
                    else -> {
                        val result = ai.onboardingAction(
                            telegramSessionId(message.chat.id),
                            "callback:${query.id}",
                            callback.runId,
                            if (callback.action == "talk") "continue" else callback.action,
                        )
                        if (callback.action == "begin" && result.onboarding?.status == "active") {
                            analytics.safely { mark(callback.runId, AttemptMark.BEGIN_PRESSED, Instant.now()) }
                        }
                        deliver(message, result, firstQuestion = callback.action == "begin")
                        if (callback.action == "begin" && result.onboarding?.status == "active" &&
                            (result.audio != null || result.text.isNotBlank())) {
                            analytics.safely { mark(callback.runId, AttemptMark.FIRST_QUESTION_DELIVERED, Instant.now()) }
                        }
                        val completed = result.onboarding?.status == "completed"
                        val facts = result.onboarding?.analytics?.withReview(result.onboarding.review)
                        if (facts != null) {
                            analytics.safely { recordOutcome(callback.runId, facts, Instant.now()) }
                        }
                        if (callback.action == "retry") {
                            analytics.safely { event(callback.runId, "retry:${query.id}", "retry_requested", Instant.now()) }
                            if (facts?.completedNow == true) analytics.safely {
                                event(callback.runId, "retry:recovered:${query.id}", "retry_recovered", Instant.now())
                            }
                        }
                        if (completed) analytics.safely {
                            mark(callback.runId, AttemptMark.RESULT_DELIVERED, Instant.now())
                        }
                        if (completed && result.onboarding.review != null) {
                            val details = OnboardingVoiceFacts(
                                completedNow = true,
                                cefr = result.onboarding.cefr,
                                overallScore = result.onboarding.overallScore,
                                scoreAvailable = result.onboarding.overallScore != null,
                            ).withReview(result.onboarding.review)
                            analytics.safely { recordOutcome(callback.runId, details, Instant.now()) }
                        }
                        if (callback.action == "begin" && result.onboarding?.status == "active") {
                            analytics.safely { mark(callback.runId, AttemptMark.LETS_CHAT, Instant.now()) }
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val voiceForbidden = isVoiceMessagesForbidden(error)
            if (voiceForbidden) {
                log.warn("Voice messages are forbidden for {}", message.chat.id)
            } else {
                log.error("Onboarding callback failed for {}", message.chat.id, error)
            }
            analytics.safely { event(callback.runId, "callback:${query.id}:error", "stage_error", Instant.now(),
                onboardingActionStage(callback.action),
                if (voiceForbidden) "voice_messages_forbidden" else localFailureReason(error)) }
            if (voiceForbidden) {
                sendMessage(
                    message.chat.id,
                    voiceMessagesForbiddenText(),
                    replyMarkup = onboardingKeyboard(callback.action, callback.runId),
                )
            } else {
                sendMessage(
                    message.chat.id,
                    "Something went wrong. Please try again.",
                    replyMarkup = onboardingKeyboard(callback.action, callback.runId),
                )
            }
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
    onCommand("speed", requireOnlyCommandInMessage = false) { message ->
        if (isSpeedCommand(message.content.text)) handle(message) { sendSpeed(message) }
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
                    voice(message, content, receivedAt, receivedNs, chatQueueNs.get(), terminalOutcome)
                }
                is TextContent -> when {
                    isStartCommand(content.text) -> greet(message, content.text, receivedAt = receivedAt)
                    isOnboardingCommand(content.text) -> greet(message, content.text, force = true, trigger = "onboarding_command")
                    isStreakCommand(content.text) -> sendStreak(message)
                    isProfileCommand(content.text) -> sendProfile(message)
                    isRemindCommand(content.text) -> sendRemind(message)
                    isSpeedCommand(content.text) -> sendSpeed(message)
                    isStartCallButton(content.text) -> {
                        customScenarioPrompts.remove(message.chat.id.toString())
                        clearScenarioMenu(message.chat)
                        startCall(message, receivedAt)
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
                        log.info("Submitting reminder time")
                        val scheduled = ai.scheduleReminder(
                            telegramSessionId(message.chat.id),
                            "text:${message.messageId}",
                            "submit",
                            text = content.text,
                        )
                        log.info("Reminder time {}", scheduled.status)
                        when (scheduled.status) {
                            "saved" -> {
                                val key = message.chat.id.toString()
                                val card = onboardingReminderCards[key]?.takeIf { it.first == scheduled.runId }
                                val onboardingRun = scheduled.runId.takeIf { Regex("[a-f0-9]{32}").matches(it) }
                                val confirmation: TextSourcesList = if (onboardingRun != null) {
                                    onboardingReminderSaved(scheduled.time.orEmpty())
                                } else {
                                    buildEntities { regular(reminderSaved(scheduled.time.orEmpty())) }
                                }
                                if (card == null) {
                                    reply(message, confirmation, allowSendingWithoutReply = true)
                                } else {
                                    try {
                                        editMessageText(message.chat.id, card.second, confirmation, replyMarkup = noInlineKeyboard)
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Throwable) {
                                        log.warn("Failed to edit saved reminder card for {}", message.chat.id, error)
                                        reply(message, confirmation, allowSendingWithoutReply = true)
                                    } finally {
                                        onboardingReminderCards.remove(key, card)
                                    }
                                }
                                if (onboardingRun != null) sendFounderNote(message.chat, onboardingRun)
                                if (onboardingRun != null) analytics.safely {
                                    mark(onboardingRun, AttemptMark.REMINDER_SET, Instant.now())
                                }
                            }
                            "invalid" -> {
                                val card = onboardingReminderCards[message.chat.id.toString()]
                                if (card == null) {
                                    reply(message, REMINDER_TIME_PROMPT, allowSendingWithoutReply = true)
                                } else {
                                    try {
                                        editMessageText(message.chat.id, card.second, REMINDER_INVALID_TIME_PROMPT, replyMarkup = noInlineKeyboard)
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Throwable) {
                                        log.warn("Failed to edit invalid reminder-time prompt for {}", message.chat.id, error)
                                    }
                                }
                            }
                            else -> {
                                val feedback = ai.callFeedback(telegramSessionId(message.chat.id), "answer", text = content.text)
                                when (feedback.status) {
                                    "saved" -> reply(message, FIRST_CALL_FEEDBACK_THANKS, allowSendingWithoutReply = true)
                                    "too_long" -> reply(message, "Пожалуйста, напиши до 500 символов.", allowSendingWithoutReply = true)
                                    else -> reply(message, SEND_VOICE_HINT)
                                }
                            }
                        }
                        if (scheduled.status !in setOf("saved", "invalid")) {
                            val state = ai.onboardingState(telegramSessionId(message.chat.id), "analytics:text:${message.messageId}")
                            if (state.status == "waiting" || state.status == "active") analytics.safely {
                                event(state.runId, "message:${message.messageId}", "text_hint", Instant.now())
                            }
                        }
                    }
                }
                else -> log.info("Ignoring message")
            }
        }
    }
}

private suspend fun BehaviourContext.clearOnboardingMarkup(message: ChatMessage) {
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

private fun OnboardingVoiceFacts.withReview(review: OnboardingReview?): OnboardingVoiceFacts {
    if (review == null) return this
    val fluency = review.fluency
    return copy(
        grammarExamples = review.grammar.examples.size,
        vocabularyExamples = review.vocabulary.examples.size,
        fluencyMetricsAvailable = fluency.paceWpm != null || fluency.longPauses != null ||
            fluency.fillers != null || fluency.longestStretchSec != null,
    )
}

private suspend fun BehaviourContext.showReviewSlide(
    ai: HttpClipClient,
    log: org.slf4j.Logger,
    message: ChatMessage,
    callback: OnboardingCallback,
): Boolean {
    log.info("Showing onboarding slide")
    val state = ai.onboardingState(
        telegramSessionId(message.chat.id),
        "slide:${message.messageId.long}:${callback.action}",
    )
    val review = state.review
    if (state.runId != callback.runId || review == null) return false
    val slide = when (callback.action) {
        "results" -> grammarSlide(review) to "vocab"
        "vocab" -> vocabularySlide(review) to "fluency"
        else -> fluencySlide(review) to "finish"
    }
    val markup = onboardingKeyboard(slide.second, callback.runId)
    try {
        editMessageText(message.chat.id, message.messageId, slide.first, replyMarkup = markup)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        log.warn("Failed to edit onboarding slide for {}", message.chat.id, error)
        reply(message, slide.first, allowSendingWithoutReply = true, replyMarkup = markup)
    }
    return true
}
