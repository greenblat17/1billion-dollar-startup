package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.speaking.AudioClip
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramVoiceTest {

    @Test
    fun leavesOggUntouched() = runTest {
        val ogg = "OggS-voice".encodeToByteArray()
        val voice = AudioClip(ogg, "audio/ogg", "reply.ogg").toTelegramVoice { _, _ ->
            error("ogg must not be transcoded")
        }
        assertEquals(ogg.toList(), voice.bytes.toList())
        assertEquals("audio/ogg", voice.contentType)
        assertEquals("reply.ogg", voice.fileName)
    }

    @Test
    fun wrapsWavAsOpusVoice() = runTest {
        var suffix = ""
        val voice = AudioClip(byteArrayOf(1, 2, 3), "audio/wav", "reply.wav").toTelegramVoice { _, seen ->
            suffix = seen
            "OggS-encoded".encodeToByteArray()
        }
        assertEquals(".wav", suffix)
        assertEquals("audio/ogg", voice.contentType)
        assertEquals("reply.ogg", voice.fileName)
        assertTrue(voice.bytes.copyOfRange(0, 4).contentEquals("OggS".encodeToByteArray()))
    }
}
