package com.eliteteam.speakingcoach.data

import kotlin.test.Test
import kotlin.test.assertEquals

class CallAudioSinkTest {
    @Test
    fun prefersHeadsetOverSpeaker() {
        assertEquals(
            CallAudioSink.WiredHeadset,
            pickCallAudioSink(
                setOf(CallAudioSink.Speaker, CallAudioSink.WiredHeadset),
            ),
        )
    }

    @Test
    fun prefersBluetoothOverWired() {
        assertEquals(
            CallAudioSink.BluetoothSco,
            pickCallAudioSink(
                setOf(
                    CallAudioSink.BluetoothSco,
                    CallAudioSink.WiredHeadphones,
                    CallAudioSink.Speaker,
                ),
            ),
        )
    }

    @Test
    fun fallsBackToSpeakerWhenNoHeadset() {
        assertEquals(CallAudioSink.Speaker, pickCallAudioSink(emptySet()))
        assertEquals(CallAudioSink.Speaker, pickCallAudioSink(setOf(CallAudioSink.Speaker)))
    }
}
