package com.eliteteam.speakingcoach.telegram

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LessonGateTest {
    @Test
    fun endWaitsForTheCurrentVoiceAndTheNextVoiceStartsAfter() = runTest {
        val gate = LessonGate()
        val events = mutableListOf<String>()
        val voiceStarted = CompletableDeferred<Unit>()
        val releaseVoice = CompletableDeferred<Unit>()

        val voice = async {
            gate.enter("chat")
            try {
                events += "voice"
                voiceStarted.complete(Unit)
                releaseVoice.await()
                events += "voice-sent"
                assertTrue(gate.isClosing("chat"))
            } finally {
                gate.leave("chat")
            }
        }
        voiceStarted.await()
        val ending = async {
            val owned = gate.close("chat") {
                events += "seal"
            }
            assertTrue(owned)
        }
        yield()
        assertTrue(gate.isClosing("chat"))
        val next = async {
            gate.enter("chat")
            try {
                events += "next"
            } finally {
                gate.leave("chat")
            }
        }
        yield()
        releaseVoice.complete(Unit)
        voice.await()
        ending.await()
        next.await()
        assertEquals(listOf("voice", "voice-sent", "seal", "next"), events)
    }

    @Test
    fun aSecondEndWhileClosingDoesNotSealAgain() = runTest {
        val gate = LessonGate()
        val events = mutableListOf<String>()
        val inside = CompletableDeferred<Unit>()
        val releaseSeal = CompletableDeferred<Unit>()

        val first = async {
            gate.close("chat") {
                events += "seal"
                inside.complete(Unit)
                releaseSeal.await()
            }
        }
        inside.await()
        val second = async {
            gate.close("chat") {
                events += "second"
            }
        }
        yield()
        releaseSeal.complete(Unit)
        assertTrue(first.await())
        assertFalse(second.await())
        assertEquals(listOf("seal"), events)
    }
}
