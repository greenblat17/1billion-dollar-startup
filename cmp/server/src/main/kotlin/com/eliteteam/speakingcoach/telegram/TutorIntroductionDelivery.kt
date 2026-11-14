package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.RuntimeDiagnostics
import com.eliteteam.speakingcoach.foundation.TELEGRAM_INTRO_TEXTS
import com.eliteteam.speakingcoach.foundation.TelegramIntroduction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Recheck durable cancellation during pauses and immediately before each send claim. */
internal suspend fun deliverIntroduction(
    introduction: TelegramIntroduction,
    chat: Long,
    request: String,
    typing: suspend () -> Unit,
    send: suspend (String) -> Long,
    pause: suspend (Long) -> Unit = { delay(it) },
) {
    var typingPart: Int? = null
    while (true) {
        val part = introduction.next(chat, request) ?: return
        if (part.waitMillis > 0) {
            if (typingPart != part.index) {
                try { typing() }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { RuntimeDiagnostics.record("telegram_typing_failed", "introduction", error) }
                typingPart = part.index
            }
            pause(minOf(part.waitMillis, 100))
            continue
        }
        if (!introduction.claim(chat, request, part.index)) continue
        val message = try { send(TELEGRAM_INTRO_TEXTS[part.index]) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            // Telegram cannot reconcile a timed-out send. Stop the remainder, do not resend or call the model.
            introduction.uncertain(chat, request)
            RuntimeDiagnostics.record("telegram_introduction_delivery_unknown", "send", error)
            return
        }
        introduction.sent(chat, request, part.index, message)
    }
}
