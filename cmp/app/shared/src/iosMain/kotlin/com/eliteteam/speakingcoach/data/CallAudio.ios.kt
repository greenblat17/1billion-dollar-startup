package com.eliteteam.speakingcoach.data

import WebRTC.RTCAudioSession
import WebRTC.RTCAudioSessionConfiguration
import WebRTC.setConfiguration
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetooth
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeVoiceChat
import platform.AVFAudio.AVAudioSessionPortOverrideNone
import platform.AVFAudio.AVAudioSessionPortOverrideSpeaker
import platform.Foundation.NSError

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun startCallAudio() {
    RTCAudioSessionConfiguration.initialize()
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val config = RTCAudioSessionConfiguration.webRTCConfiguration()
        config.category = AVAudioSessionCategoryPlayAndRecord.orEmpty()
        config.mode = AVAudioSessionModeVoiceChat.orEmpty()
        config.categoryOptions =
            AVAudioSessionCategoryOptionDefaultToSpeaker or
            AVAudioSessionCategoryOptionAllowBluetooth
        with(RTCAudioSession.sharedInstance()) {
            lockForConfiguration()
            useManualAudio = false
            setConfiguration(config, error.ptr)
            unlockForConfiguration()
        }
    }
    AVAudioSession.sharedInstance().overrideOutputAudioPort(
        AVAudioSessionPortOverrideSpeaker,
        null,
    )
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun stopCallAudio() {
    AVAudioSession.sharedInstance().overrideOutputAudioPort(
        AVAudioSessionPortOverrideNone,
        null,
    )
}

