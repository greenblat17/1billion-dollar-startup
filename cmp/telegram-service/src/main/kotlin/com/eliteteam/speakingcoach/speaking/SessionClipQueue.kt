package com.eliteteam.speakingcoach.speaking

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class SessionClipQueue(
    private val processor: ClipProcessor,
    private val scope: CoroutineScope,
    private val maxQueuedPerUser: Int = 3,
) {
    private val sessions = ConcurrentHashMap<String, SessionState>()

    suspend fun submit(
        sessionId: SessionId,
        source: ClipSource,
        onQueuedBehind: suspend () -> Unit = {},
    ): ClipSubmitResult {
        val state = sessions.getOrPut(sessionId.value) { SessionState() }
        val deferred = CompletableDeferred<ClipSubmitResult>()
        val queuedBehind = state.mutex.withLock {
            val inflight = state.queue.size + if (state.processing) 1 else 0
            if (inflight >= maxQueuedPerUser) {
                deferred.complete(ClipSubmitResult.QueueFull)
                return@withLock null
            }
            val behind = state.processing || state.queue.isNotEmpty()
            state.queue.addLast(QueuedTurn(source, deferred))
            if (!state.processing) {
                state.processing = true
                scope.launch { processLoop(sessionId, state) }
            }
            behind
        }
        if (queuedBehind == true) {
            onQueuedBehind()
        }
        return deferred.await()
    }

    private suspend fun processLoop(sessionId: SessionId, state: SessionState) {
        while (true) {
            val turn = state.mutex.withLock {
                val next = state.queue.removeFirstOrNull()
                if (next == null) {
                    state.processing = false
                }
                next
            } ?: return
            try {
                val clip = turn.source.load()
                val reply = processor.process(sessionId, clip)
                turn.result.complete(ClipSubmitResult.Completed(reply))
            } catch (error: Throwable) {
                turn.result.completeExceptionally(error)
            }
        }
    }

    private class SessionState {
        val mutex = Mutex()
        val queue = ArrayDeque<QueuedTurn>()
        var processing: Boolean = false
    }

    private class QueuedTurn(
        val source: ClipSource,
        val result: CompletableDeferred<ClipSubmitResult>,
    )
}
