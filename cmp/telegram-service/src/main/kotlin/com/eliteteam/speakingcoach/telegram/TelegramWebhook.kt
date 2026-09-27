package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.HttpClipClient
import com.eliteteam.speakingcoach.speaking.SessionClipQueue
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.webhook.setWebhookInfo
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
import io.ktor.server.routing.Route
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.Executors

private val log = LoggerFactory.getLogger("TelegramWebhook")
private val telegramUpdateJson = Json { ignoreUnknownKeys = true }

internal fun newTelegramWebhookScope(): CoroutineScope =
    CoroutineScope(Executors.newFixedThreadPool(4).asCoroutineDispatcher())

internal suspend fun buildTelegramWebhookBehaviour(
    token: String,
    ai: HttpClipClient,
    sessionClipQueue: SessionClipQueue,
    scope: CoroutineScope,
): BehaviourContext {
    val bot = speakingCoachTelegramBot(token)
    return bot.buildBehaviour(
        scope = scope,
        defaultExceptionsHandler = { error ->
            log.error("Telegram behaviour failed", error)
        },
    ) {
        installSpeakingCoachHandlers(ai, sessionClipQueue)
    }
}

@OptIn(ExperimentalKtorApi::class)
internal fun Route.installSpeakingCoachWebhook(
    secret: String,
    behaviourContext: BehaviourContext,
    webhookScope: CoroutineScope,
) {
    val transformer = webhookScope.updateHandlerWithMediaGroupsAdaptation(
        behaviourContext.asUpdateReceiver,
    )
    post {
        if (call.request.header(TELEGRAM_WEBHOOK_SECRET_HEADER) != secret) {
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        try {
            val update = telegramUpdateJson.decodeFromString(
                UpdateDeserializationStrategy,
                call.receiveText(),
            )
            log.info("Telegram update {}", update.updateId)
            transformer(update)
            call.respond(HttpStatusCode.OK)
        } catch (error: Throwable) {
            log.error("Failed to handle Telegram webhook", error)
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
    val certificate = certificateFile.readBytes().asMultipartFile(certificateFile.name)
    bot.setWebhookInfo(
        url = webhookUrl,
        certificate = certificate,
        secretToken = webhookSecret,
    )
}
