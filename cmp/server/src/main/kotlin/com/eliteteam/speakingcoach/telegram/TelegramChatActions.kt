package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.analytics.AnalyticsWriteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
        var voiceReleased = false
        try {
            val analyticsWrites = AnalyticsWriteBuffer()
            var previousAnalytics: CompletableDeferred<Unit>? = null
            val analyticsDone = CompletableDeferred<Unit>()
            var actionStarted = false
            try {
                chat.actions.withLock {
                    actionStarted = true
                    try {
                        withContext(analyticsWrites) {
                            if (voice && admission > 0) onQueued()
                            action()
                        }
                    } finally {
                        previousAnalytics = chat.analyticsTail
                        chat.analyticsTail = analyticsDone
                    }
                }
            } finally {
                if (voice) {
                    withContext(NonCancellable) { chat.guard.withLock { chat.voices-- } }
                    voiceReleased = true
                }
                if (actionStarted) {
                    try {
                        withContext(NonCancellable) {
                            previousAnalytics?.await()
                            analyticsWrites.flush()
                        }
                    } finally {
                        analyticsDone.complete(Unit)
                    }
                }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) { chat.guard.withLock { chat.accepted.remove(requestId) } }
            throw error
        } finally {
            // Also release the slot if acquiring the action lock was cancelled.
            if (voice && !voiceReleased) withContext(NonCancellable) { chat.guard.withLock { chat.voices-- } }
        }
    }

    private class Chat {
        val guard = Mutex()
        val actions = Mutex()
        var analyticsTail: CompletableDeferred<Unit>? = null
        val accepted = mutableSetOf<String>()
        var voices = 0
    }
}
