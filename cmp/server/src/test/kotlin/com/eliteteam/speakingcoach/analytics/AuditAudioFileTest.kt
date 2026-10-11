package com.eliteteam.speakingcoach.analytics

import kotlin.test.Test
import kotlin.test.assertEquals

class AuditAudioFileTest {
    @Test
    fun replyAudioIsASeparateFileFromTheIncomingClip() {
        val attemptId = "11111111-1111-4111-8111-111111111111"
        assertEquals("$attemptId.ogg", auditAudioFileName(attemptId, reply = false))
        assertEquals("$attemptId-reply.ogg", auditAudioFileName(attemptId, reply = true, contentType = "audio/ogg"))
        assertEquals("$attemptId-reply.mp3", auditAudioFileName(attemptId, reply = true, contentType = "audio/mpeg"))
        assertEquals("audio/ogg", auditAudioContentType("$attemptId.ogg"))
        assertEquals("audio/mpeg", auditAudioContentType("$attemptId-reply.mp3"))
    }
}
