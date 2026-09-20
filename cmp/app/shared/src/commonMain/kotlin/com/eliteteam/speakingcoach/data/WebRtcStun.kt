package com.eliteteam.speakingcoach.data

internal object WebRtcStun {
    val urls = listOf(
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302",
    )
}

internal class IceFailedException(state: String) : IllegalStateException("ice $state")

internal expect fun startCallAudio()

internal expect fun stopCallAudio()

internal enum class CallAudioSink {
    BleHeadset,
    BluetoothSco,
    UsbHeadset,
    WiredHeadset,
    WiredHeadphones,
    Speaker,
}

internal fun pickCallAudioSink(available: Set<CallAudioSink>): CallAudioSink {
    val order = listOf(
        CallAudioSink.BleHeadset,
        CallAudioSink.BluetoothSco,
        CallAudioSink.UsbHeadset,
        CallAudioSink.WiredHeadset,
        CallAudioSink.WiredHeadphones,
        CallAudioSink.Speaker,
    )
    return order.firstOrNull { it in available } ?: CallAudioSink.Speaker
}
