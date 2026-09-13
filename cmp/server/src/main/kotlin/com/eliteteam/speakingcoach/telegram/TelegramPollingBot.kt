package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.speaking.SessionClipQueue
import dev.inmo.tgbotapi.extensions.api.webhook.deleteWebhook
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling

class TelegramPollingBot(
    private val token: String,
    private val sessionClipQueue: SessionClipQueue,
) {
    suspend fun startPolling() {
        val bot = speakingCoachTelegramBot(token)
        bot.deleteWebhook()
        bot.buildBehaviourWithLongPolling {
            installSpeakingCoachHandlers(sessionClipQueue)
        }.join()
    }
}
