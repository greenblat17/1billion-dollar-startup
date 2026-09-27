package com.eliteteam.speakingcoach.telegram

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

internal class LessonGate {
    private val chats = ConcurrentHashMap<String, ChatState>()

    suspend fun enter(chatId: String) {
        val state = state(chatId)
        while (true) {
            val blocked = state.lock.withLock {
                if (state.closing) {
                    state.opened ?: CompletableDeferred<Unit>().also { state.opened = it }
                } else {
                    state.inflight += 1
                    null
                }
            }
            if (blocked == null) {
                return
            }
            blocked.await()
        }
    }

    suspend fun leave(chatId: String) {
        val state = chats[chatId] ?: return
        val idle = state.lock.withLock {
            if (state.inflight == 0) {
                return@withLock null
            }
            state.inflight -= 1
            if (state.inflight == 0) {
                val pending = state.idle
                state.idle = null
                pending
            } else {
                null
            }
        }
        idle?.complete(Unit)
    }

    suspend fun isClosing(chatId: String): Boolean {
        val state = chats[chatId] ?: return false
        return state.lock.withLock { state.closing }
    }

    suspend fun close(chatId: String, block: suspend () -> Unit): Boolean {
        val state = state(chatId)
        val decision = state.lock.withLock {
            if (state.closing) {
                CloseDecision.Wait(state.opened ?: CompletableDeferred<Unit>().also { state.opened = it })
            } else {
                state.closing = true
                val idle = if (state.inflight == 0) {
                    null
                } else {
                    state.idle ?: CompletableDeferred<Unit>().also { state.idle = it }
                }
                CloseDecision.Proceed(idle)
            }
        }
        when (decision) {
            is CloseDecision.Wait -> {
                decision.gate.await()
                return false
            }
            is CloseDecision.Proceed -> {
                decision.idle?.await()
                try {
                    block()
                } finally {
                    val opened = state.lock.withLock {
                        state.closing = false
                        state.idle = null
                        val signal = state.opened
                        state.opened = null
                        signal
                    }
                    opened?.complete(Unit)
                }
                return true
            }
        }
    }

    private fun state(chatId: String): ChatState = chats.getOrPut(chatId) { ChatState() }

    private class ChatState {
        val lock = Mutex()
        var closing: Boolean = false
        var inflight: Int = 0
        var idle: CompletableDeferred<Unit>? = null
        var opened: CompletableDeferred<Unit>? = null
    }

    private sealed interface CloseDecision {
        data class Wait(val gate: CompletableDeferred<Unit>) : CloseDecision
        data class Proceed(val idle: CompletableDeferred<Unit>?) : CloseDecision
    }
}
