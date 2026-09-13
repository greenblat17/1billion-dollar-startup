package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.speaking.SessionClipQueue
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.webhook.setWebhookInfo
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviour
import dev.inmo.tgbotapi.extensions.utils.updates.retrieving.includeWebhookHandlingInRoute
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("TelegramWebhook")

internal suspend fun buildTelegramWebhookBehaviour(
    token: String,
    sessionClipQueue: SessionClipQueue,
): BehaviourContext {
    val bot = speakingCoachTelegramBot(token)
    return bot.buildBehaviour(
        defaultExceptionsHandler = { error ->
            log.error("Telegram behaviour failed", error)
        },
    ) {
        installSpeakingCoachHandlers(sessionClipQueue)
    }
}

internal fun Route.installTelegramWebhookRoute(
    secret: String,
    behaviourContext: BehaviourContext?,
) {
    (this as ApplicationCallPipeline).intercept(ApplicationCallPipeline.Plugins) {
        if (call.request.header(TELEGRAM_WEBHOOK_SECRET_HEADER) != secret) {
            call.respond(HttpStatusCode.Forbidden)
            finish()
        }
    }
    if (behaviourContext != null) {
        includeWebhookHandlingInRoute(
            behaviourContext,
            exceptionsHandler = { error ->
                log.error("Failed to handle Telegram webhook", error)
            },
            block = behaviourContext.asUpdateReceiver,
        )
    } else {
        post {
            call.respond(HttpStatusCode.ServiceUnavailable)
        }
    }
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
