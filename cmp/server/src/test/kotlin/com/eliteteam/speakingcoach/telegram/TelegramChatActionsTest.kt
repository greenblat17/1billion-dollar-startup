package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.analytics.AnalyticsWriteBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TelegramChatActionsTest {
    @Test
    fun serializesVoiceDeliveryAndResetWhileKeepingThreeVoiceLimit() = runTest {
        val actions = TelegramChatActions()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = async {
            actions.run("chat", "voice1", voice = true) {
                events += "processing"
                release.await()
                events += "sent"
            }
        }
        yield()
        val second = async { actions.run("chat", "voice2", voice = true) { events += "second" } }
        yield()
        val third = async { actions.run("chat", "voice3", voice = true) { events += "third" } }
        yield()
        actions.run("chat", "voice4", voice = true, onFull = { events += "full" }) { error("must reject") }
        val reset = async { actions.run("chat", "reset") { events += "reset" } }
        yield()
        // A different chat remains responsive while this chat waits.
        actions.run("other", "start") { events += "other" }
        release.complete(Unit)
        first.await()
        second.await()
        third.await()
        reset.await()
        assertEquals(listOf("processing", "full", "other", "sent", "second", "third", "reset"), events)
    }

    @Test
    fun endConversationWaitsForTheVoiceAlreadyInFlight() = runTest {
        val actions = TelegramChatActions()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val voice = async {
            actions.run("chat", "voice", voice = true) {
                events += "voice"
                release.await()
                events += "voice-done"
            }
        }
        yield()
        val end = async { actions.run("chat", "end") { events += "end" } }
        yield()
        release.complete(Unit)
        voice.await()
        end.await()
        assertEquals(listOf("voice", "voice-done", "end"), events)
    }

    @Test
    fun recordsWaitUntilActionActuallyStarts() = runTest {
        val actions = TelegramChatActions()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val first = async {
            actions.run("chat", "first", voice = true) {
                started.complete(Unit)
                release.await()
            }
        }
        started.await()
        var waitNanos = -1L
        val second = async {
            actions.run("chat", "second", voice = true, onActionStart = { waitNanos = it }) {}
        }
        yield()
        assertEquals(-1L, waitNanos)
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(true, waitNanos >= 0)
    }

    @Test
    fun duplicateDeliveryDoesNotRunTwiceAndFailureAllowsRetry() = runTest {
        val actions = TelegramChatActions()
        var calls = 0
        actions.run("chat", "start") { calls++ }
        actions.run("chat", "start") { calls++ }
        assertEquals(1, calls)
        assertFailsWith<IllegalStateException> {
            actions.run("chat", "callback") { error("network") }
        }
        actions.run("chat", "callback") { calls++ }
        assertEquals(2, calls)
    }

    @Test
    fun newGoalCardCanAcceptAChoiceAfterEarlierCardWasUsed() = runTest {
        val actions = TelegramChatActions()
        val runId = "a".repeat(32)
        val choices = mutableListOf<Int>()
        actions.run("chat", onboardingCallbackRequestId("m5", runId, "q1", 10)) { choices += 5 }
        actions.run("chat", onboardingCallbackRequestId("skip", runId, "q2", 10)) { choices += 0 }
        actions.run("chat", onboardingCallbackRequestId("m15", runId, "q3", 11)) { choices += 15 }
        assertEquals(listOf(5, 15), choices)
    }

    @Test
    fun analyticsWriteDoesNotHoldUpTheNextDeliveredReply() = runTest {
        val actions = TelegramChatActions()
        val startedWriting = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = async {
            actions.run("chat", "one") {
                events += "reply one"
                currentCoroutineContext()[AnalyticsWriteBuffer]!!.add {
                    startedWriting.complete(Unit)
                    releaseWrite.await()
                    events += "write one"
                }
            }
        }
        startedWriting.await()
        val second = async {
            actions.run("chat", "two") {
                events += "reply two"
                currentCoroutineContext()[AnalyticsWriteBuffer]!!.add { events += "write two" }
            }
        }
        yield()
        assertEquals(listOf("reply one", "reply two"), events)
        releaseWrite.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("reply one", "reply two", "write one", "write two"), events)
    }

    @Test
    fun slowAnalyticsDoesNotOccupyVoiceSlotsOrBlockLaterReplies() = runTest {
        val actions = TelegramChatActions()
        val startedWriting = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val replies = mutableListOf<String>()
        val first = async {
            actions.run("chat", "one", voice = true) {
                replies += "one"
                currentCoroutineContext()[AnalyticsWriteBuffer]!!.add {
                    startedWriting.complete(Unit)
                    releaseWrite.await()
                }
            }
        }
        startedWriting.await()
        val later = (2..4).map { index ->
            async { actions.run("chat", "$index", voice = true) { replies += "$index" } }
        }
        yield()
        assertEquals(listOf("one", "2", "3", "4"), replies)
        releaseWrite.complete(Unit)
        first.await()
        later.forEach { it.await() }
    }
}
