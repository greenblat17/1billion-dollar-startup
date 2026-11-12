package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.RuntimeDiagnostics

import com.eliteteam.speakingcoach.ai.TutorClient
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviour
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onContentMessage
import dev.inmo.tgbotapi.types.chat.PrivateChat
import dev.inmo.tgbotapi.types.message.content.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import java.time.Instant

/** Only the persistent text tutor is registered; legacy commands/callbacks/voice are not installed. */
internal suspend fun buildSpeakyTelegram(token: String, client: TutorClient, scope: CoroutineScope): BehaviourContext {
    return speakingCoachTelegramBot(token).buildBehaviour(scope = scope, defaultExceptionsHandler = { error ->
        RuntimeDiagnostics.record("speaky_telegram_handler_failed", "handler", error)
    }) {
        val tutor = TutorTelegramChat(this, client)
        onContentMessage { message ->
            if (message.chat !is PrivateChat) return@onContentMessage
            val text = (message.content as? TextContent)?.text ?: return@onContentMessage
            try {
                tutor.respond(message, text, Instant.now(), start = isStartCommand(text))
                telegramChatNumber(message.chat.id)?.let { chat ->
                    client.foundation?.completeIntake(chat, message.messageId.long)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Durable intake retries the same request. Never acknowledge a failed turn as complete.
                RuntimeDiagnostics.record("speaky_tutor_turn_failed", "turn", error)
            }
        }
    }
}
