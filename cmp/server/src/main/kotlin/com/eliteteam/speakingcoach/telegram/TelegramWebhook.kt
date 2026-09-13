package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.speaking.SessionClipQueue
import dev.inmo.tgbotapi.extensions.api.webhook.setWebhookInfo
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviour
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import dev.inmo.tgbotapi.updateshandlers.FlowsUpdatesFilter
import kotlinx.coroutines.CoroutineScope
import java.io.File

internal suspend fun startTelegramWebhook(
    token: String,
    webhookUrl: String,
    webhookSecret: String,
    certificateFile: File,
    sessionClipQueue: SessionClipQueue,
    updates: FlowsUpdatesFilter,
    scope: CoroutineScope,
) {
    val bot = speakingCoachTelegramBot(token)
    bot.buildBehaviour(flowUpdatesFilter = updates, scope = scope) {
        installSpeakingCoachHandlers(sessionClipQueue)
    }
    val certificate = certificateFile.readBytes().asMultipartFile(certificateFile.name)
    bot.setWebhookInfo(
        url = webhookUrl,
        certificate = certificate,
        secretToken = webhookSecret,
    )
}
