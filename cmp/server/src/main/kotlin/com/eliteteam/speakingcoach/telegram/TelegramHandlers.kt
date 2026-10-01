package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.ChatProfile
import com.eliteteam.speakingcoach.ai.HttpClipClient
import com.eliteteam.speakingcoach.ai.ClipJobFailure
import com.eliteteam.speakingcoach.ai.OnboardingReview
import com.eliteteam.speakingcoach.analytics.AttemptMark
import com.eliteteam.speakingcoach.analytics.OnboardingAnalytics
import com.eliteteam.speakingcoach.analytics.OnboardingVoiceFacts
import com.eliteteam.speakingcoach.analytics.safely
import com.eliteteam.speakingcoach.speaking.ClipReply
import com.eliteteam.speakingcoach.speaking.AudioClip
import com.eliteteam.speakingcoach.speaking.ClipSubmitResult
import com.eliteteam.speakingcoach.speaking.SessionClipQueue
import com.eliteteam.speakingcoach.speaking.SessionId
import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
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
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal const val TELEGRAM_WEBHOOK_SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token"
private const val CLARIFY_TEXT = "I didn't catch that. Could you say it again?"

internal fun telegramSessionId(chatId: Any): SessionId = SessionId("tg-$chatId")

private fun spokenKey(chatId: Any, messageId: MessageId) = "$chatId:${messageId.long}"

private val startSourcePattern = Regex("^[A-Za-z0-9_-]{1,64}$")

internal fun isStartCommand(text: String): Boolean = isCommand(text, "/start")

internal fun isOnboardingCommand(text: String): Boolean = isCommand(text, "/onboarding")

internal fun isProfileCommand(text: String): Boolean = isCommand(text, "/profile")

internal fun isStreakCommand(text: String): Boolean = isCommand(text, "/streak")

internal fun isRemindCommand(text: String): Boolean = isCommand(text, "/remind")

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
    analytics: OnboardingAnalytics? = null,
) {
    val log = LoggerFactory.getLogger("TelegramHandlers")
    val actions = TelegramChatActions()
    val answeredStreaks = ConcurrentHashMap.newKeySet<String>()
    val founderNotes = ConcurrentHashMap.newKeySet<String>()
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
    suspend fun sendStreak(message: ChatMessage, claim: String = "${message.chat.id}:${message.messageId}") {
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
    suspend fun sendProfile(message: ChatMessage) {
        val profile = ai.progressProfile(telegramSessionId(message.chat.id))
        reply(
            message, profileMessage((message.chat as? PrivateChat)?.firstName, profile),
            allowSendingWithoutReply = true, replyMarkup = profileKeyboard(),
        )
    }
    suspend fun sendRemind(message: ChatMessage) {
        val sessionId = telegramSessionId(message.chat.id)
        val current = try {
            ai.reminderTime(sessionId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to read reminder time for {}", sessionId.value, error)
            null
        }
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
    suspend fun deliver(message: ChatMessage, result: ClipReply, firstQuestion: Boolean = false) {
        if (result.onboarding?.status == "ignored") return
        val onboarding = result.onboarding?.takeIf { it.status in setOf("active", "pending", "completed") }
        val finished = onboarding?.takeIf { it.status == "completed" && it.review != null }
        val practice = result.call?.takeIf { onboarding == null }
        val progress = when {
            finished != null -> onboardingKeyboard("level", finished.runId)
            onboarding != null -> onboardingProgressKeyboard(onboarding.seconds)
            else -> null
        }
        if (progress != null || practice != null) clearProgress(message.chat)
        if (practice?.goalJustCrossed == true) {
            reply(message, callGoalReached(practice.goalSeconds), allowSendingWithoutReply = true)
        }
        if (onboarding != null) {
            result.corrections.minByOrNull { it.priority }?.let { correction ->
                reply(message, onboardingCorrection(correction), allowSendingWithoutReply = true)
            }
        } else if (practice != null) {
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
                text = if (firstQuestion) ONBOARDING_VOICE_HINT else null,
                replyMarkup = practice?.let { callKeyboard(it.todaySeconds, it.goalSeconds, spoken) }
                    ?: withSpokenText(progress, spoken),
            )
            if (spoken) spokenLines[spokenKey(message.chat.id, voice.messageId)] = result.text.trim()
            if (progress != null || practice != null) progressMessages[message.chat.id.toString()] = voice.messageId
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
    suspend fun greet(message: ChatMessage, text: String, force: Boolean = false, trigger: String = "start",
                      receivedAt: Instant = Instant.now()) {
        val sessionId = telegramSessionId(message.chat.id)
        val requestId = "message:${message.messageId}"
        if (force) {
            try {
                ai.endCall(sessionId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.warn("Failed to seal a call before onboarding for {}", sessionId.value, error)
            }
        }
        // Resolve eligibility before today's funnel event makes a new user look existing.
        val state = ai.onboardingState(sessionId, requestId, if (force) "force" else "start")
        val source = startSource(text)
        if (trigger == "start" && state.status != "waiting") analytics.safely {
            recordEntry(sessionId.value, requestId, receivedAt, false, trigger, "existing_user", source, null)
        }
        try {
            ai.recordFunnelStart(sessionId, source, telegramProfile(message.chat))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to record start for {}", sessionId.value, error)
        }
        if (state.status == "waiting") {
            var invitedAt: Instant? = null
            try {
                clearProgress(message.chat)
                reply(
                    message,
                    onboardingInvitation((message.chat as? PrivateChat)?.firstName),
                    allowSendingWithoutReply = true,
                    replyMarkup = onboardingKeyboard("begin", state.runId),
                )
                invitedAt = Instant.now()
            } finally {
                if (trigger == "start") analytics.safely {
                    recordEntry(sessionId.value, requestId, receivedAt, true, trigger, null, source, state.runId, invitedAt)
                }
            }
            analytics.safely { startAttempt(sessionId.value, state.runId, trigger, Instant.now(), source) }
        } else {
            val greeting = ai.startSession(sessionId)
            reply(message, startTextMessage((message.chat as? PrivateChat)?.firstName), allowSendingWithoutReply = true)
            sendVoice(message.chat.id, greeting.audio.bytes.asMultipartFile(greeting.audio.fileName))
        }
    }
    suspend fun voice(message: ChatMessage, content: VoiceContent, receivedAt: Instant) {
        val sessionId = telegramSessionId(message.chat.id)
        val requestId = "message:${message.messageId}"
        val state = ai.onboardingState(sessionId, requestId)
        if (state.status == "waiting") {
            reply(message, ONBOARDING_BEGIN_HINT, replyMarkup = onboardingKeyboard("begin", state.runId))
            analytics.safely { event(state.runId, requestId, "pre_begin_voice_hint", Instant.now()) }
            return
        }
        if (state.status == "pending") {
            reply(message, "I couldn't prepare your result. Please try again.", replyMarkup = onboardingKeyboard("retry", state.runId))
            return
        }
        if (state.status != "active") {
            val profile = ai.progressProfile(sessionId)
            when (callGate(state.status, profile.assessment?.overallScore, profile.dailyMinutes)) {
                "need-onboarding" -> {
                    greet(message, "/onboarding", force = true, trigger = "automatic_restart")
                    return
                }
                "need-goal" -> {
                    val assessment = profile.assessment
                    reply(
                        message,
                        practiceAsk(assessment?.cefr, assessment?.overallScore, assessment?.nextBand, assessment?.pointsToNext),
                        allowSendingWithoutReply = true,
                        replyMarkup = practiceMinutesKeyboard(state.runId),
                    )
                    return
                }
                else -> {
                    val opened = ai.openCall(sessionId)
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
        try {
            ai.recordFunnelVoice(sessionId, telegramProfile(message.chat))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to record voice for {}", sessionId.value, error)
        }
        ai.ensureSession(sessionId)
        val started = System.nanoTime()
        var replyReady = false
        var completedFacts: OnboardingVoiceFacts? = null
        try {
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
                ClipSubmitResult.QueueFull -> {
                    reply(message, QUEUE_FULL_TEXT)
                    if (state.status == "active") analytics.safely {
                        recordVoice(state.runId, sessionId.value, requestId,
                            OnboardingVoiceFacts(failureReason = "queue_full"), 0, Instant.now(), receivedAt)
                    }
                }
                is ClipSubmitResult.Completed -> {
                    replyReady = true
                    completedFacts = result.reply.onboarding?.analytics?.withReview(result.reply.onboarding.review)
                    deliver(message, result.reply)
                    val elapsed = processingMillis(started)
                    val facts = completedFacts
                    val now = Instant.now()
                    if (state.status == "active" && facts != null) {
                        analytics.safely { recordVoice(state.runId, sessionId.value, requestId, facts, elapsed, now, receivedAt) }
                        if (facts.completedNow) analytics.safely { mark(state.runId, AttemptMark.RESULT_DELIVERED, now) }
                        if (facts.assessmentFailed) analytics.safely { event(state.runId, "$requestId:failed", "result_build_failed", now) }
                    } else if (
                        state.status != "waiting" && state.status != "pending" && state.status != "active" &&
                        result.reply.transcript.isNotBlank() && result.reply.text != CLARIFY_TEXT
                    ) {
                        analytics.safely { recordReturn(sessionId.value, now) }
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
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (state.status == "active") {
                analytics.safely {
                    recordVoice(
                        state.runId,
                        sessionId.value,
                        requestId,
                        if (replyReady) {
                            (completedFacts ?: OnboardingVoiceFacts()).copy(
                                outcome = "delivery_failure", failureReason = "delivery_failure",
                                grammarExamples = null, vocabularyExamples = null, fluencyMetricsAvailable = null,
                            )
                        } else {
                            OnboardingVoiceFacts(failureReason = analyticsFailure(error))
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
    suspend fun handle(message: ChatMessage, isVoice: Boolean = false, receivedAt: Instant = Instant.now(), block: suspend () -> Unit) {
        try {
            actions.run(
                chatId = message.chat.id.toString(),
                requestId = "message:${message.messageId}",
                voice = isVoice,
                onQueued = { reply(message, QUEUED_TEXT) },
                onFull = {
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
    suspend fun showCallLevel(message: ChatMessage, callId: String) {
        val review = ai.reviewCall(callId)
        if (review.retry) {
            reply(message, CALL_RETRY_TEXT, allowSendingWithoutReply = true, replyMarkup = callRetryKeyboard(callId))
            return
        }
        reply(
            message,
            levelSlide(review.cefr, review.levelText, review.overallScore, review.nextBand, review.pointsToNext),
            allowSendingWithoutReply = true,
            replyMarkup = callSlideKeyboard("grammar", callId),
        )
    }
    suspend fun showCallSlide(message: ChatMessage, callback: CallCallback) {
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
        val review = ai.reviewCall(callId)
        if (review.retry) {
            reply(message, CALL_RETRY_TEXT, allowSendingWithoutReply = true, replyMarkup = callRetryKeyboard(callId))
            return
        }
        try {
            editMessageReplyMarkup(message.chat.id, message.messageId, replyMarkup = noInlineKeyboard)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
        }
        reply(
            message,
            callProgressMessage(review, offerReminder = reminderMissing(message)),
            allowSendingWithoutReply = true,
            replyMarkup = callReturnKeyboard(),
        )
    }
    suspend fun endPracticeCall(message: ChatMessage) {
        val callId = ai.endCall(telegramSessionId(message.chat.id)).callId
        if (callId.isNullOrBlank()) {
            reply(message, SEND_VOICE_HINT, allowSendingWithoutReply = true)
            return
        }
        clearProgress(message.chat)
        showCallLevel(message, callId)
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
        if (query.data == REMINDER_STOP_CALLBACK) {
            val message = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@onDataCallbackQuery
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
            return@onDataCallbackQuery
        }
        val callCallback = parseCallCallback(query.data)
        if (callCallback != null) {
            val message = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@onDataCallbackQuery
            try {
                actions.run(message.chat.id.toString(), "call:${query.id}") {
                    when (callCallback.action) {
                        "clock" -> reply(message, CALL_CLOCK_HINT, allowSendingWithoutReply = true)
                        "profile" -> sendProfile(message)
                        "bye" -> reply(message, SEE_YOU_TOMORROW, allowSendingWithoutReply = true)
                        "end" -> endPracticeCall(message)
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
            return@onDataCallbackQuery
        }
        if (query.data == ONBOARDING_PROGRESS_CALLBACK) {
            val voiceMessage = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@onDataCallbackQuery
            reply(voiceMessage, ONBOARDING_PROGRESS_HINT, allowSendingWithoutReply = true)
            return@onDataCallbackQuery
        }
        if (query.data == SPOKEN_TEXT_CALLBACK) {
            val voiceMessage = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@onDataCallbackQuery
            val spoken = spokenLines[spokenKey(voiceMessage.chat.id, voiceMessage.messageId)] ?: return@onDataCallbackQuery
            reply(voiceMessage, spokenQuote(spoken), allowSendingWithoutReply = true)
            return@onDataCallbackQuery
        }
        if (query.data == PROFILE_STREAK_CALLBACK) {
            val message = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@onDataCallbackQuery
            sendStreak(message, "profile:${query.id}")
            return@onDataCallbackQuery
        }
        val callback = parseOnboardingCallback(query.data) ?: return@onDataCallbackQuery
        val message = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@onDataCallbackQuery
        val requestId = onboardingCallbackRequestId(callback.action, callback.runId, query.id.toString())
        try {
            actions.run(message.chat.id.toString(), requestId) {
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
                        clearOnboardingMarkup(message)
                        val state = ai.onboardingState(
                            telegramSessionId(message.chat.id),
                            "finish:${query.id}",
                        )
                        val ask = if (state.runId == callback.runId) {
                            practiceAsk(state.cefr, state.overallScore, state.nextBand, state.pointsToNext)
                        } else {
                            practiceAsk(null, null, null)
                        }
                        reply(message, ask, allowSendingWithoutReply = true, replyMarkup = practiceMinutesKeyboard(callback.runId))
                        if (state.runId == callback.runId) {
                            analytics.safely { mark(callback.runId, AttemptMark.PRACTICE, Instant.now()) }
                        }
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
                        clearOnboardingMarkup(message)
                        reply(
                            message,
                            practiceDeal(minutes, currentStreak),
                            allowSendingWithoutReply = true,
                            replyMarkup = reminderAskKeyboard(callback.runId),
                        )
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
                        clearOnboardingMarkup(message)
                        reply(message, REMINDER_TIME_PROMPT, allowSendingWithoutReply = true)
                        analytics.safely { markReminderDecision(callback.runId, "set_reminder", Instant.now()) }
                    }
                    "later" -> {
                        ai.scheduleReminder(
                            telegramSessionId(message.chat.id),
                            "later:${query.id}",
                            "decline",
                            callback.runId,
                        )
                        clearOnboardingMarkup(message)
                        reply(
                            message,
                            reminderSkipped(),
                            allowSendingWithoutReply = true,
                            replyMarkup = practiceDealKeyboard(callback.runId),
                        )
                        analytics.safely { markReminderDecision(callback.runId, "not_now", Instant.now()) }
                    }
                    "profile" -> {
                        sendProfile(message)
                        analytics.safely { mark(callback.runId, AttemptMark.PROFILE_OPENED, Instant.now()) }
                        if (founderNotes.add(callback.runId)) sendMessage(message.chat.id, FOUNDER_NOTE)
                    }
                    "bye" -> {
                        reply(message, SEE_YOU_TOMORROW, allowSendingWithoutReply = true)
                        analytics.safely { mark(callback.runId, AttemptMark.BYE, Instant.now()) }
                        if (founderNotes.add(callback.runId)) sendMessage(message.chat.id, FOUNDER_NOTE)
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
            log.error("Onboarding callback failed for {}", message.chat.id, error)
            sendMessage(
                message.chat.id, "Something went wrong. Please try again.",
                replyMarkup = onboardingKeyboard(callback.action, callback.runId),
            )
        }
    }
    onCommand("start", requireOnlyCommandInMessage = false) { message ->
        if (isStartCommand(message.content.text)) {
            val receivedAt = Instant.now()
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
        if (isStreakCommand(message.content.text)) sendStreak(message)
    }
    onCommand("remind", requireOnlyCommandInMessage = false) { message ->
        if (isRemindCommand(message.content.text)) handle(message) { sendRemind(message) }
    }
    onContentMessage { message ->
        val receivedAt = Instant.now()
        handle(message, isVoice = message.content is VoiceContent, receivedAt = receivedAt) {
            when (val content = message.content) {
                is VoiceContent -> voice(message, content, receivedAt)
                is TextContent -> when {
                    isStartCommand(content.text) -> greet(message, content.text, receivedAt = receivedAt)
                    isOnboardingCommand(content.text) -> greet(message, content.text, force = true, trigger = "onboarding_command")
                    isStreakCommand(content.text) -> sendStreak(message)
                    isProfileCommand(content.text) -> sendProfile(message)
                    isRemindCommand(content.text) -> sendRemind(message)
                    content.text.startsWith("/") -> Unit
                    else -> {
                        val scheduled = ai.scheduleReminder(
                            telegramSessionId(message.chat.id),
                            "text:${message.messageId}",
                            "submit",
                            text = content.text,
                        )
                        when (scheduled.status) {
                            "saved" -> {
                                reply(
                                    message,
                                    reminderSaved(scheduled.time.orEmpty()),
                                    allowSendingWithoutReply = true,
                                    replyMarkup = scheduled.runId.takeIf { Regex("[a-f0-9]{32}").matches(it) }
                                        ?.let { practiceDealKeyboard(it) },
                                )
                                scheduled.runId.takeIf { Regex("[a-f0-9]{32}").matches(it) }?.let { runId ->
                                    analytics.safely { mark(runId, AttemptMark.REMINDER_SET, Instant.now()) }
                                }
                            }
                            "invalid" -> reply(message, REMINDER_TIME_PROMPT, allowSendingWithoutReply = true)
                            else -> reply(message, SEND_VOICE_HINT)
                        }
                        if (scheduled.status !in setOf("saved", "invalid")) {
                            val state = ai.onboardingState(telegramSessionId(message.chat.id), "analytics:text:${message.messageId}")
                            if (state.status == "waiting" || state.status == "active") analytics.safely {
                                event(state.runId, "message:${message.messageId}", "text_hint", Instant.now())
                            }
                        }
                    }
                }
                else -> Unit
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
