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
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.api.send.sendBotAction
import dev.inmo.tgbotapi.extensions.api.send.withRecordVoiceAction
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.buttons.ReplyKeyboardRemove
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
import dev.inmo.tgbotapi.types.actions.BotAction
import dev.inmo.tgbotapi.types.actions.RecordVoiceAction
import dev.inmo.tgbotapi.types.actions.TypingAction
import dev.inmo.tgbotapi.types.message.abstracts.ChatMessage
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.message.content.VoiceContent
import dev.inmo.tgbotapi.utils.DefaultKTgBotAPIKSLog
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.Base64
import kotlin.time.TimeSource

internal const val TELEGRAM_WEBHOOK_SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token"

internal fun telegramSessionId(chatId: Any): SessionId = SessionId("tg-$chatId")

private fun spokenKey(chatId: Any, messageId: MessageId) = "$chatId:${messageId.long}"

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
    val founderNotes = ConcurrentHashMap.newKeySet<String>()
    val progressMessages = ConcurrentHashMap<String, MessageId>()
    val spokenLines = ConcurrentHashMap<String, String>()
    suspend fun showStatus(chat: Chat, action: BotAction) {
        try {
            sendBotAction(chat, action)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to show Telegram action {} for {}", action.actionName, chat.id, error)
        }
    }
    suspend fun hideStartCallKeyboard(chat: Chat) {
        try {
            sendMessage(chat.id, CALL_STARTED_TEXT, replyMarkup = ReplyKeyboardRemove())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to hide Start call keyboard for {}", chat.id, error)
        }
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
    suspend fun sendSpeed(message: ChatMessage) {
        val speed = ai.speechSpeed(telegramSessionId(message.chat.id))
        reply(message, speedMessage(speed), allowSendingWithoutReply = true, replyMarkup = speedKeyboard(speed))
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
        val deliveryStarted = TimeSource.Monotonic.markNow()
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
                showStatus(message.chat, TypingAction)
                reply(message, onboardingCorrection(correction), allowSendingWithoutReply = true)
            }
        } else if (practice != null) {
            result.corrections.minByOrNull { it.priority }?.let { correction ->
                showStatus(message.chat, TypingAction)
                reply(message, onboardingCorrection(correction), allowSendingWithoutReply = true)
            }
        } else if (result.transcript.isNotBlank()) {
            if (result.corrections.isNotEmpty()) showStatus(message.chat, TypingAction)
            reply(message, coachingEntities(result.transcript, result.corrections), allowSendingWithoutReply = true)
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
        log.info(
            "Telegram reply delivery session={} request=message:{} before_voice_ms={} voice_or_text_ms={} total_ms={}",
            telegramSessionId(message.chat.id).value, message.messageId, beforeVoiceMs,
            deliveryStarted.elapsedNow().inWholeMilliseconds - beforeVoiceMs,
            deliveryStarted.elapsedNow().inWholeMilliseconds,
        )
    }
    suspend fun greet(message: ChatMessage, text: String, force: Boolean = false) {
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
        try {
            ai.recordFunnelStart(sessionId, startSource(text), telegramProfile(message.chat))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to record start for {}", sessionId.value, error)
        }
        if (state.status == "waiting") {
            clearProgress(message.chat)
            if (force) sendMessage(message.chat.id, "Let's start again.", replyMarkup = ReplyKeyboardRemove())
            reply(
                message,
                onboardingInvitation((message.chat as? PrivateChat)?.firstName),
                allowSendingWithoutReply = true,
                replyMarkup = onboardingKeyboard("begin", state.runId),
            )
        } else {
            val greeting = ai.startSession(sessionId)
            val profile = ai.progressProfile(sessionId)
            val eligible = callGate(state.status, profile.assessment?.overallScore, profile.dailyMinutes) == "open"
            val activeCall = eligible && ai.callStatus(sessionId).active
            reply(
                message, startTextMessage((message.chat as? PrivateChat)?.firstName),
                allowSendingWithoutReply = true,
                replyMarkup = when {
                    activeCall -> ReplyKeyboardRemove()
                    eligible -> startCallKeyboard()
                    else -> null
                },
            )
            sendVoice(message.chat.id, greeting.audio.bytes.asMultipartFile(greeting.audio.fileName))
        }
    }
    suspend fun startCall(message: ChatMessage) {
        val sessionId = telegramSessionId(message.chat.id)
        val state = ai.onboardingState(sessionId, "message:${message.messageId}")
        val profile = ai.progressProfile(sessionId)
        when (callGate(state.status, profile.assessment?.overallScore, profile.dailyMinutes)) {
            "onboarding" -> {
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
                greet(message, "/onboarding", force = true)
                return
            }
            "need-goal" -> {
                val assessment = profile.assessment
                reply(message, practiceAsk(assessment?.cefr, assessment?.overallScore,
                    assessment?.nextBand, assessment?.pointsToNext),
                    allowSendingWithoutReply = true, replyMarkup = practiceMinutesKeyboard(state.runId))
                return
            }
        }
        showStatus(message.chat, RecordVoiceAction)
        val opening = ai.startCall(sessionId)
        opening.unseenCallId?.let { callId ->
            reply(message, CALL_YESTERDAY_TEXT, allowSendingWithoutReply = true,
                replyMarkup = callYesterdayKeyboard(callId))
        }
        if (opening.status == "active") {
            reply(message, CALL_ALREADY_ACTIVE_TEXT, replyMarkup = ReplyKeyboardRemove())
            return
        }
        check(opening.status == "ready") { "Unexpected call opening status: ${opening.status}" }
        val question = requireNotNull(opening.question)
        val audio = Base64.getDecoder().decode(requireNotNull(opening.audioBase64))
        clearProgress(message.chat)
        hideStartCallKeyboard(message.chat)
        val voice = try {
            sendVoice(message.chat.id, audio.asMultipartFile("call-opening.ogg"),
                replyMarkup = callKeyboard(opening.todaySeconds, opening.goalSeconds, spoken = true))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to send call opening for {}", sessionId.value, error)
            reply(message, "I couldn't start the conversation. Tap 🎙 Start call to try again.",
                allowSendingWithoutReply = true, replyMarkup = startCallKeyboard())
            return
        }
        spokenLines[spokenKey(message.chat.id, voice.messageId)] = question
        progressMessages[message.chat.id.toString()] = voice.messageId
        try {
            ai.markCallStarterDelivered(opening.callId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to confirm call starter delivery for {}", opening.callId, error)
        }
    }
    suspend fun voice(message: ChatMessage, content: VoiceContent) {
        val voiceStarted = TimeSource.Monotonic.markNow()
        val sessionId = telegramSessionId(message.chat.id)
        val requestId = "message:${message.messageId}"
        val state = ai.onboardingState(sessionId, requestId)
        var hideCallKeyboard = false
        if (state.status == "waiting") {
            reply(message, ONBOARDING_BEGIN_HINT, replyMarkup = onboardingKeyboard("begin", state.runId))
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
                    greet(message, "/onboarding", force = true)
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
        try {
            ai.recordFunnelVoice(sessionId, telegramProfile(message.chat))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Failed to record voice for {}", sessionId.value, error)
        }
        ai.ensureSession(sessionId)
        val setupMs = voiceStarted.elapsedNow().inWholeMilliseconds
        val queueStarted = System.nanoTime()
        val processingStarted = AtomicLong(0)
        val downloadMs = AtomicLong(0)
        val result = withRecordVoiceAction(message.chat) {
            sessionClipQueue.submit(
                sessionId = sessionId,
                onProcessingStart = { processingStarted.set(System.nanoTime()) },
                source = {
                    val downloadStarted = TimeSource.Monotonic.markNow()
                    val bytes = downloadFile(content.media)
                    downloadMs.set(downloadStarted.elapsedNow().inWholeMilliseconds)
                    AudioClip(
                        bytes, "audio/ogg", "voice.ogg",
                        onboardingRunId = state.runId.takeIf { state.status == "active" || state.status == "completed" },
                        requestId = requestId,
                        durationSeconds = (content.media.duration ?: 0L).toDouble(),
                    )
                },
            )
        }
        val processedAt = processingStarted.get()
        val queueMs = if (processedAt == 0L) 0L else (processedAt - queueStarted) / 1_000_000
        val processingMs = if (processedAt == 0L) 0L else (System.nanoTime() - processedAt) / 1_000_000
        val deliveryStarted = TimeSource.Monotonic.markNow()
        when (result) {
            ClipSubmitResult.QueueFull -> reply(message, QUEUE_FULL_TEXT)
            is ClipSubmitResult.Completed -> {
                if (hideCallKeyboard && result.reply.call != null) hideStartCallKeyboard(message.chat)
                deliver(message, result.reply)
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
    }
    suspend fun handle(message: ChatMessage, isVoice: Boolean = false, block: suspend () -> Unit) {
        val receivedAt = TimeSource.Monotonic.markNow()
        var outcome = "ok"
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
            outcome = "cancelled"
            throw error
        } catch (error: Throwable) {
            outcome = "error"
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
                    receivedAt.elapsedNow().inWholeMilliseconds,
                )
            }
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
            reply(message, START_CALL_PROMPT, allowSendingWithoutReply = true, replyMarkup = startCallKeyboard())
            return
        }
        clearProgress(message.chat)
        sendMessage(message.chat.id, START_CALL_PROMPT, replyMarkup = startCallKeyboard())
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
        if (query.data == LEGACY_ONBOARDING_CALLBACK) {
            val message = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@onDataCallbackQuery
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
            return@onDataCallbackQuery
        }
        val selectedSpeed = parseSpeedCallback(query.data)
        if (selectedSpeed != null) {
            val message = (query as? AbstractMessageCallbackQuery)?.message as? ChatMessage ?: return@onDataCallbackQuery
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
            return@onDataCallbackQuery
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
                        }
                    }
                    "results", "vocab", "fluency" -> showReviewSlide(ai, log, message, callback)
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
                        sendMessage(message.chat.id, START_CALL_PROMPT, replyMarkup = startCallKeyboard())
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
                    }
                    "profile" -> {
                        sendProfile(message)
                        if (founderNotes.add(callback.runId)) sendMessage(message.chat.id, FOUNDER_NOTE)
                    }
                    "bye" -> {
                        reply(message, SEE_YOU_TOMORROW, allowSendingWithoutReply = true)
                        if (founderNotes.add(callback.runId)) sendMessage(message.chat.id, FOUNDER_NOTE)
                    }
                    else -> {
                        val result = ai.onboardingAction(
                            telegramSessionId(message.chat.id),
                            "callback:${query.id}",
                            callback.runId,
                            if (callback.action == "talk") "continue" else callback.action,
                        )
                        deliver(message, result, firstQuestion = callback.action == "begin")
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
        if (isStartCommand(message.content.text)) handle(message) { greet(message, message.content.text) }
    }
    onCommand("onboarding", requireOnlyCommandInMessage = false) { message ->
        if (isOnboardingCommand(message.content.text)) handle(message) { greet(message, message.content.text, force = true) }
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
    onCommand("speed", requireOnlyCommandInMessage = false) { message ->
        if (isSpeedCommand(message.content.text)) handle(message) { sendSpeed(message) }
    }
    onContentMessage { message ->
        handle(message, isVoice = message.content is VoiceContent) {
            when (val content = message.content) {
                is VoiceContent -> voice(message, content)
                is TextContent -> when {
                    isStartCommand(content.text) -> greet(message, content.text)
                    isOnboardingCommand(content.text) -> greet(message, content.text, force = true)
                    isStreakCommand(content.text) -> sendStreak(message)
                    isProfileCommand(content.text) -> sendProfile(message)
                    isRemindCommand(content.text) -> sendRemind(message)
                    isSpeedCommand(content.text) -> sendSpeed(message)
                    isStartCallButton(content.text) -> startCall(message)
                    content.text.startsWith("/") -> Unit
                    else -> {
                        val scheduled = ai.scheduleReminder(
                            telegramSessionId(message.chat.id),
                            "text:${message.messageId}",
                            "submit",
                            text = content.text,
                        )
                        when (scheduled.status) {
                            "saved" -> reply(
                                message,
                                reminderSaved(scheduled.time.orEmpty()),
                                allowSendingWithoutReply = true,
                                replyMarkup = scheduled.runId.takeIf { Regex("[a-f0-9]{32}").matches(it) }
                                    ?.let { practiceDealKeyboard(it) },
                            )
                            "invalid" -> reply(message, REMINDER_TIME_PROMPT, allowSendingWithoutReply = true)
                            else -> reply(message, SEND_VOICE_HINT)
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

private suspend fun BehaviourContext.showReviewSlide(
    ai: HttpClipClient,
    log: org.slf4j.Logger,
    message: ChatMessage,
    callback: OnboardingCallback,
) {
    val state = ai.onboardingState(
        telegramSessionId(message.chat.id),
        "slide:${message.messageId.long}:${callback.action}",
    )
    val review = state.review
    if (state.runId != callback.runId || review == null) return
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
}
