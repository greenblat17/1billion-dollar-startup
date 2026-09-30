package com.eliteteam.speakingcoach.telegram

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Orders bot actions through delivery, with the same three-voice admission limit as the clip queue. */
internal class TelegramChatActions {
    private val chats = ConcurrentHashMap<String, Chat>()

    suspend fun run(
        chatId: String,
        requestId: String,
        voice: Boolean = false,
        onQueued: suspend () -> Unit = {},
        onFull: suspend () -> Unit = {},
        action: suspend () -> Unit,
    ) {
        val chat = chats.getOrPut(chatId) { Chat() }
        val admission = chat.guard.withLock {
            when {
                requestId in chat.accepted -> -1
                voice && chat.voices >= 3 -> -2
                else -> {
                    val behind = chat.voices
                    if (voice) chat.voices++
                    chat.accepted.add(requestId)
                    behind
                }
            }
        }
        if (admission == -1) return
        if (admission == -2) {
            onFull()
            return
        }
        try {
            chat.actions.withLock {
                if (voice && admission > 0) onQueued()
                action()
            }
        } catch (error: Throwable) {
            chat.guard.withLock { chat.accepted.remove(requestId) }
            throw error
        } finally {
            chat.guard.withLock {
                if (voice) chat.voices--
            }
        }
    }

    private class Chat {
        val guard = Mutex()
        val actions = Mutex()
        val accepted = mutableSetOf<String>()
        var voices = 0
    }
}
