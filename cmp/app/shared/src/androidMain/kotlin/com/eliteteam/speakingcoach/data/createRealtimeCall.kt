package com.eliteteam.speakingcoach.data

import co.touchlab.kermit.Logger
import com.shepeliev.webrtckmp.DataChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.webrtc.DataChannel as AndroidDataChannel

actual fun createRealtimeCall(logger: Logger): RealtimeCall =
    ShepelievRealtimeCall(logger, ::androidDataChannelPayloads)

private fun androidDataChannelPayloads(channel: DataChannel): Flow<ByteArray> = callbackFlow {
    val native = channel.android
    native.registerObserver(
        object : AndroidDataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                if (native.state() == AndroidDataChannel.State.CLOSED) {
                    runCatching {
                        native.unregisterObserver()
                        native.dispose()
                    }
                    close()
                }
            }

            override fun onMessage(buffer: AndroidDataChannel.Buffer) {
                val data = buffer.data ?: return
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                trySend(bytes)
            }
        },
    )
    awaitClose { }
}
