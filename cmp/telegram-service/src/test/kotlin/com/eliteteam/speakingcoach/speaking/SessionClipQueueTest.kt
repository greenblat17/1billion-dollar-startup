package com.eliteteam.speakingcoach.speaking

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class SessionClipQueueTest {

    @Test
    fun processesClipsOneByOneForTheSameUser() = runTest {
        val order = mutableListOf<String>()
        val queue = SessionClipQueue(
            processor = { _, clip ->
                order += String(clip.bytes)
                delay(50.milliseconds)
                ClipReply(emptyList(), clip)
            },
            scope = this,
            maxQueuedPerUser = 3,
        )
        val session = SessionId("user-1")
        val first = async { queue.submit(session, clip("a")) }
        val second = async { queue.submit(session, clip("b")) }

        assertIs<ClipSubmitResult.Completed>(first.await())
        assertIs<ClipSubmitResult.Completed>(second.await())
        assertEquals(listOf("a", "b"), order)
    }

    @Test
    fun rejectsWhenPerUserQueueIsFull() = runTest {
        val queue = SessionClipQueue(
            processor = { _, clip ->
                delay(200.milliseconds)
                ClipReply(emptyList(), clip)
            },
            scope = this,
            maxQueuedPerUser = 1,
        )
        val session = SessionId("user-2")
        val first = async { queue.submit(session, clip("a")) }
        delay(20.milliseconds)
        val overflow = queue.submit(session, clip("b"))

        assertEquals(ClipSubmitResult.QueueFull, overflow)
        assertIs<ClipSubmitResult.Completed>(first.await())
    }

    @Test
    fun notifiesWhenQueuedBehindAnotherTurn() = runTest {
        var notified = false
        val queue = SessionClipQueue(
            processor = { _, clip ->
                delay(80.milliseconds)
                ClipReply(emptyList(), clip)
            },
            scope = this,
            maxQueuedPerUser = 3,
        )
        val session = SessionId("user-3")
        val first = async { queue.submit(session, clip("a")) }
        delay(20.milliseconds)
        val second = async {
            queue.submit(session, clip("b"), onQueuedBehind = { notified = true })
        }

        first.await()
        second.await()
        assertTrue(notified)
    }

    private fun clip(label: String) = ClipSource {
        AudioClip(label.toByteArray(), "audio/ogg", "$label.ogg")
    }
}
