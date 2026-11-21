package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.HttpClipClient
import com.eliteteam.speakingcoach.analytics.VoiceAttemptRecorder
import com.eliteteam.speakingcoach.analytics.InteractionAudit
import com.eliteteam.speakingcoach.TelegramOperationalMetrics
import com.eliteteam.speakingcoach.RuntimeDiagnostics
import com.eliteteam.speakingcoach.RuntimeDiagnostics.Event
import com.eliteteam.speakingcoach.RuntimeDiagnostics.Outcome
import com.eliteteam.speakingcoach.speaking.SessionClipQueue
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.bot.setMyCommands
import dev.inmo.tgbotapi.extensions.api.webhook.setWebhookInfo
import dev.inmo.tgbotapi.types.BotCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviour
import dev.inmo.tgbotapi.extensions.utils.updates.retrieving.updateHandlerWithMediaGroupsAdaptation
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import dev.inmo.tgbotapi.types.update.abstracts.UpdateDeserializationStrategy
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("TelegramWebhook")
private val telegramUpdateJson = Json { ignoreUnknownKeys = true }

internal fun newTelegramWebhookScope(): CoroutineScope =
    CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO.limitedParallelism(4))

internal suspend fun buildTelegramWebhookBehaviour(
    token: String,
    ai: HttpClipClient,
    sessionClipQueue: SessionClipQueue,
    scope: CoroutineScope,
    analytics: com.eliteteam.speakingcoach.analytics.OnboardingAnalytics? = null,
    voiceAttempts: VoiceAttemptRecorder? = null,
    callEvents: com.eliteteam.speakingcoach.analytics.CallEventRecorder? = null,
    voiceCost: com.eliteteam.speakingcoach.analytics.VoiceCostStore? = null,
    operationalMetrics: TelegramOperationalMetrics? = null,
    audit: InteractionAudit? = null,
    userIdHashKey: String = "",
    followups: FollowupStore? = null,
    reminderButtons: ReminderButtonStore? = null,
    vocabularyAnalytics: com.eliteteam.speakingcoach.analytics.VocabularyAnalytics? = null,
): BehaviourContext {
    val bot = speakingCoachTelegramBot(token, audit, operationalMetrics, userIdHashKey)
    return bot.buildBehaviour(
        scope = scope,
        defaultExceptionsHandler = { error ->
            log.error("Telegram behaviour failed", error)
        },
    ) {
        installSpeakingCoachHandlers(ai, sessionClipQueue, analytics, voiceAttempts, operationalMetrics, audit, callEvents,
            voiceCost, followups, reminderButtons, vocabularyAnalytics)
    }
}

@OptIn(ExperimentalKtorApi::class)
internal fun Route.installSpeakingCoachWebhook(
    secret: String,
    behaviourContext: BehaviourContext,
    webhookScope: CoroutineScope,
    audit: InteractionAudit? = null,
    operationalMetrics: TelegramOperationalMetrics,
    textIntake: com.eliteteam.speakingcoach.foundation.TelegramTextIntake? = null,
    paymentHandler: (suspend (kotlinx.serialization.json.JsonObject) -> Boolean)? = null,
) {
    require(secret.isNotBlank()) { "Telegram webhook secret required" }
    val transformer = webhookScope.updateHandlerWithMediaGroupsAdaptation(
        behaviourContext.asUpdateReceiver,
    )
    textIntake?.launch(webhookScope) { envelope ->
        transformer(telegramUpdateJson.decodeFromString(UpdateDeserializationStrategy, envelope.toString()))
    }
    post {
        val traceId = RuntimeDiagnostics.newId()
        val fields = mapOf("trace_id" to traceId, "request_id" to traceId, "operation" to "telegram_webhook")
        call.response.header("X-Diagnostic-Trace", traceId)
        if (!java.security.MessageDigest.isEqual(call.request.header(TELEGRAM_WEBHOOK_SECRET_HEADER).orEmpty().toByteArray(), secret.toByteArray())) {
            RuntimeDiagnostics.record("telegram_webhook", "authorize", extra = fields + mapOf(
                "outcome" to "rejected", "reason_code" to "unauthorized", "http_status" to "403"))
                ?.let { call.response.header("X-Error-ID", it) }
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        operationalMetrics.recordWebhookRequest()
        var stage = "decode"
        try {
            val raw = call.receiveText()
            val update = telegramUpdateJson.decodeFromString(
                UpdateDeserializationStrategy,
                raw,
            )
            val envelope = telegramUpdateJson.parseToJsonElement(raw).jsonObject
            stage = "dispatch"
            if (paymentHandler?.invoke(envelope) == true) {
                call.respond(HttpStatusCode.OK)
                return@post
            }
            val durableText = textIntake?.accept(envelope) == true
            val freshAudit = audit?.recordInbound(envelope) != false
            if (durableText || !freshAudit) {
                call.respond(HttpStatusCode.OK)
                return@post
            }
            transformer(update)
            call.respond(HttpStatusCode.OK)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val invalid = stage == "decode" && error is IllegalArgumentException
            val reason = when {
                invalid -> "invalid_update"
                stage == "decode" -> "update_unreadable"
                else -> "update_dispatch_failed"
            }
            val message = when (reason) {
                "invalid_update" -> "Ktor could not decode the Telegram update."
                "update_unreadable" -> "Ktor could not read the Telegram update."
                else -> "Ktor could not dispatch the Telegram update."
            }
            RuntimeDiagnostics.record(Event("telegram_webhook_failed", "telegram_webhook", stage,
                if (invalid) Outcome.REJECTED else Outcome.FAILED, message, reason),
                fields + ("http_status" to "500"), error)
                ?.let { call.response.header("X-Error-ID", it) }
            call.respond(HttpStatusCode.InternalServerError)
        }
    }.hide()
}

internal suspend fun registerTelegramWebhook(
    bot: TelegramBot,
    webhookUrl: String,
    webhookSecret: String,
    certificateFile: File,
) {
    var stage = "certificate"
    try {
        val certificate = certificateFile.readBytes().asMultipartFile(certificateFile.name)
        stage = "register"
        bot.setWebhookInfo(url = webhookUrl, certificate = certificate, secretToken = webhookSecret)
        RuntimeDiagnostics.record(Event("telegram_webhook_registration", "telegram.webhook", stage,
            Outcome.COMPLETED, "Telegram returned a successful response for the webhook registration."),
            mapOf("provider" to "telegram"))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val reason = if (stage == "certificate") "certificate_unreadable" else "telegram_registration_failed"
        val message = if (stage == "certificate") "Ktor could not read the webhook certificate."
            else "Ktor could not register its Telegram webhook."
        RuntimeDiagnostics.record(Event("telegram_webhook_registration", "telegram.webhook", stage,
            Outcome.FAILED, message, reason), mapOf("provider" to "telegram"), error)
        throw error
    }
}

internal suspend fun registerBotCommands(bot: TelegramBot) {
    bot.setMyCommands(
        BotCommand("start", "Start a conversation"),
        BotCommand("profile", "Show your English progress"),
        BotCommand("streak", "Show your streak"),
        BotCommand("onboarding", "Get to know Speaky"),
        BotCommand("remind", "Set or change your daily reminder"),
        BotCommand("voice", "Choose your conversation partner"),
        BotCommand("speed", "Set Speaky's voice speed"),
        BotCommand("words", "Open your vocabulary"),
    )
}
