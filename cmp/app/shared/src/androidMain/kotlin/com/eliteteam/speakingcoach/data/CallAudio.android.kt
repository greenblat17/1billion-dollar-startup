package com.eliteteam.speakingcoach.data

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

object CallAudio {
    private var appContext: Context? = null
    private var previousMode: Int? = null
    private var previousSpeaker: Boolean? = null
    private var scoStarted = false
    private var deviceCallback: AudioDeviceCallback? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    internal fun start() {
        val audio = audioManager() ?: return
        if (previousMode == null) {
            previousMode = audio.mode
            @Suppress("DEPRECATION")
            previousSpeaker = audio.isSpeakerphoneOn
        }
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        applyRoute(audio)
        if (deviceCallback == null) {
            val callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    audioManager()?.let(::applyRoute)
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    audioManager()?.let(::applyRoute)
                }
            }
            audio.registerAudioDeviceCallback(callback, null)
            deviceCallback = callback
        }
    }

    internal fun stop() {
        val audio = audioManager() ?: return
        deviceCallback?.let { audio.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
        if (scoStarted) {
            @Suppress("DEPRECATION")
            runCatching { audio.stopBluetoothSco() }
            @Suppress("DEPRECATION")
            audio.isBluetoothScoOn = false
            scoStarted = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.clearCommunicationDevice()
        } else {
            previousSpeaker?.let { speaker ->
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = speaker
            }
        }
        previousMode?.let { audio.mode = it }
        previousMode = null
        previousSpeaker = null
    }

    private fun applyRoute(audio: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audio.availableCommunicationDevices
            val available = devices.mapNotNull { it.toSink() }.toSet()
            val chosen = pickCallAudioSink(available)
            val device = devices.firstOrNull { it.toSink() == chosen }
            if (device != null) {
                audio.setCommunicationDevice(device)
            }
            return
        }
        @Suppress("DEPRECATION")
        val headset = audio.isWiredHeadsetOn || audio.isBluetoothScoOn || audio.isBluetoothA2dpOn
        if (headset) {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
            @Suppress("DEPRECATION")
            if (audio.isBluetoothA2dpOn || audio.isBluetoothScoOn) {
                runCatching {
                    audio.startBluetoothSco()
                    @Suppress("DEPRECATION")
                    audio.isBluetoothScoOn = true
                    scoStarted = true
                }
            }
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = true
        }
    }

    private fun AudioDeviceInfo.toSink(): CallAudioSink? = when (type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET -> CallAudioSink.BleHeadset
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> CallAudioSink.BluetoothSco
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> CallAudioSink.BluetoothSco
        AudioDeviceInfo.TYPE_USB_HEADSET -> CallAudioSink.UsbHeadset
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> CallAudioSink.WiredHeadset
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> CallAudioSink.WiredHeadphones
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> CallAudioSink.Speaker
        else -> null
    }

    private fun audioManager(): AudioManager? {
        val context = appContext ?: return null
        return context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
}

internal actual fun startCallAudio() = CallAudio.start()

internal actual fun stopCallAudio() = CallAudio.stop()
