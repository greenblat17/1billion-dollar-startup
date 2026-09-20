package com.eliteteam.speakingcoach.data

import co.touchlab.kermit.Logger
import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelInit
import dev.onvoid.webrtc.RTCDataChannelObserver
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceConnectionState
import dev.onvoid.webrtc.RTCIceGatheringState
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.audio.AudioTrack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

internal class JvmRealtimeCall(
    private val logger: Logger,
) : RealtimeCall {
    private val factory = PeerConnectionFactory()
    private val iceComplete = CompletableDeferred<Unit>()
    private val iceUp = CompletableDeferred<RTCIceConnectionState>()
    private val collector = OpenAiTranscriptCollector()
    private val _captions = MutableSharedFlow<String>(extraBufferCapacity = 32)
    private var audioTrack: AudioTrack? = null
    private var remoteAudio: AudioTrack? = null
    private var peer: RTCPeerConnection? = null
    private var dataChannel: RTCDataChannel? = null

    override val captions = _captions.asSharedFlow()

    override suspend fun createOffer(): String {
        val config = RTCConfiguration()
        val stun = RTCIceServer()
        stun.urls.addAll(WebRtcStun.urls)
        config.iceServers.add(stun)
        val connection = factory.createPeerConnection(
            config,
            object : PeerConnectionObserver {
                override fun onIceCandidate(candidate: RTCIceCandidate) = Unit
                override fun onIceGatheringChange(state: RTCIceGatheringState) {
                    logger.i { "iceGathering=$state" }
                    if (state == RTCIceGatheringState.COMPLETE && !iceComplete.isCompleted) {
                        iceComplete.complete(Unit)
                    }
                }

                override fun onIceConnectionChange(state: RTCIceConnectionState) {
                    logger.i { "iceConnection=$state" }
                    if (state.isTerminalIce() && !iceUp.isCompleted) {
                        iceUp.complete(state)
                    }
                }

                override fun onStandardizedIceConnectionChange(state: RTCIceConnectionState) {
                    onIceConnectionChange(state)
                }

                override fun onTrack(transceiver: RTCRtpTransceiver) {
                    val track = transceiver.receiver.track
                    logger.i { "onTrack $track" }
                    if (track is AudioTrack) {
                        track.isEnabled = true
                        remoteAudio = track
                    }
                }
            },
        )
        peer = connection
        val options = AudioOptions().apply {
            echoCancellation = true
            autoGainControl = true
            noiseSuppression = true
        }
        val source = factory.createAudioSource(options)
        val track = factory.createAudioTrack("audio0", source)
        audioTrack = track
        connection.addTrack(track, listOf("stream0"))
        val channel = connection.createDataChannel("oai-events", RTCDataChannelInit())
        dataChannel = channel
        channel.registerObserver(
            object : RTCDataChannelObserver {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit
                override fun onStateChange() = Unit
                override fun onMessage(buffer: RTCDataChannelBuffer) {
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    val text = bytes.decodeToString()
                    val caption = collector.onMessage(text)
                    if (caption != null) {
                        _captions.tryEmit(caption)
                    }
                }
            },
        )
        val offer = createLocalOffer(connection)
        setLocal(connection, offer)
        withTimeout(15.seconds) { iceComplete.await() }
        return connection.localDescription?.sdp ?: offer.sdp
    }

    override suspend fun setRemoteAnswer(sdp: String) {
        val connection = peer ?: error("peer")
        setRemote(connection, RTCSessionDescription(RTCSdpType.ANSWER, sdp))
        val state = withTimeout(20.seconds) { iceUp.await() }
        if (state != RTCIceConnectionState.CONNECTED && state != RTCIceConnectionState.COMPLETED) {
            throw IceFailedException(state.name)
        }
    }

    override fun setMuted(muted: Boolean) {
        audioTrack?.isEnabled = !muted
    }

    override fun snapshotTurns(): List<TranscriptTurn> = collector.snapshot()

    override fun close() {
        runCatching { dataChannel?.unregisterObserver() }
        runCatching { dataChannel?.close() }
        runCatching { peer?.close() }
        runCatching { factory.dispose() }
    }

    private suspend fun createLocalOffer(connection: RTCPeerConnection): RTCSessionDescription =
        suspendCancellableCoroutine { continuation ->
            connection.createOffer(
                RTCOfferOptions(),
                object : CreateSessionDescriptionObserver {
                    override fun onSuccess(description: RTCSessionDescription) {
                        continuation.resume(description)
                    }

                    override fun onFailure(error: String) {
                        continuation.resumeWithException(IllegalStateException(error))
                    }
                },
            )
        }

    private suspend fun setLocal(connection: RTCPeerConnection, description: RTCSessionDescription) {
        suspendCancellableCoroutine { continuation ->
            connection.setLocalDescription(
                description,
                object : SetSessionDescriptionObserver {
                    override fun onSuccess() {
                        continuation.resume(Unit)
                    }

                    override fun onFailure(error: String) {
                        continuation.resumeWithException(IllegalStateException(error))
                    }
                },
            )
        }
    }

    private suspend fun setRemote(connection: RTCPeerConnection, description: RTCSessionDescription) {
        suspendCancellableCoroutine { continuation ->
            connection.setRemoteDescription(
                description,
                object : SetSessionDescriptionObserver {
                    override fun onSuccess() {
                        continuation.resume(Unit)
                    }

                    override fun onFailure(error: String) {
                        continuation.resumeWithException(IllegalStateException(error))
                    }
                },
            )
        }
    }
}

private fun RTCIceConnectionState.isTerminalIce(): Boolean =
    this == RTCIceConnectionState.CONNECTED ||
        this == RTCIceConnectionState.COMPLETED ||
        this == RTCIceConnectionState.FAILED ||
        this == RTCIceConnectionState.CLOSED

