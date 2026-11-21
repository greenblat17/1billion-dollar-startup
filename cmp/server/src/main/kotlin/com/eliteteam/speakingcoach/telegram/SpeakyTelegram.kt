package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.RuntimeDiagnostics
import com.eliteteam.speakingcoach.RuntimeDiagnostics.Event
import com.eliteteam.speakingcoach.RuntimeDiagnostics.Outcome

import com.eliteteam.speakingcoach.ai.TutorClient
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviour
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onContentMessage
import dev.inmo.tgbotapi.types.chat.PrivateChat
import dev.inmo.tgbotapi.types.message.content.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant

/** Only the persistent text tutor is registered; legacy commands/callbacks/voice are not installed. */
internal suspend fun buildSpeakyTelegram(token: String, client: TutorClient, scope: CoroutineScope): BehaviourContext {
    return speakingCoachTelegramBot(token).buildBehaviour(scope = scope, defaultExceptionsHandler = { error ->
        RuntimeDiagnostics.record("speaky_telegram_handler_failed", "handler", error)
    }) {
        val tutor = TutorTelegramChat(this, client)
        onContentMessage { message ->
            val text = (message.content as? TextContent)?.text
            val reason = when {
                message.chat !is PrivateChat -> "non_private_chat"
                text == null -> "non_text_message"
                else -> null
            }
            if (reason != null) {
                val description = if (reason == "non_private_chat") "the chat was not private" else "the message had no text"
                RuntimeDiagnostics.record(Event("telegram_update_skipped", "telegram_webhook", "route", Outcome.SKIPPED,
                    "Speaky skipped a Telegram message because $description.", reason, coalesce = true))
                return@onContentMessage
            }
            checkNotNull(text)
            val start = isStartCommand(text)
            val handle: suspend () -> Unit = {
                try {
                    tutor.respond(message, text, Instant.now(), start)
                    telegramChatNumber(message.chat.id)?.let { chat ->
                        client.foundation?.completeIntake(chat, message.messageId.long)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // A failed turn remains eligible for durable intake replay.
                    RuntimeDiagnostics.record("speaky_tutor_turn_failed", "turn", error)
                }
            }
            // Keep the durable lease until delivery ends. New intake cancels the remaining greeting
            // and the delivery loop releases the chat promptly on its next cancellation check.
            if (start && client.foundation != null) scope.launch { handle() } else handle()
        }
    }
}
