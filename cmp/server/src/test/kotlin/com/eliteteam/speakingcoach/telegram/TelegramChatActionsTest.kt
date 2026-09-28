package com.eliteteam.speakingcoach.telegram

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
}
