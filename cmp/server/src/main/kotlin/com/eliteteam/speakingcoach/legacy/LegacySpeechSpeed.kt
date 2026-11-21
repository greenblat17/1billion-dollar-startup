package com.eliteteam.speakingcoach.legacy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class LegacySpeechSpeed(val value: Double, val label: String) {
    val callback get() = "speed:$value"

    companion object {
        val choices: List<LegacySpeechSpeed> = checkNotNull(
            LegacySpeechSpeed::class.java.getResourceAsStream("/legacy-speech-speeds-v1.json"),
        ).bufferedReader().use { Json.decodeFromString(it.readText()) }
    }
}
