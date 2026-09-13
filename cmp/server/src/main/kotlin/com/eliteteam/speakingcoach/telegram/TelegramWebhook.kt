package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.speaking.SessionClipQueue
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.webhook.setWebhookInfo
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviour
import dev.inmo.tgbotapi.extensions.utils.updates.retrieving.includeWebhookHandlingInRoute
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import io.ktor.server.routing.Route
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

internal fun Route.includeSpeakingCoachWebhook(behaviourContext: BehaviourContext) {
    includeWebhookHandlingInRoute(
        behaviourContext,
        block = behaviourContext.asUpdateReceiver,
    )
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
