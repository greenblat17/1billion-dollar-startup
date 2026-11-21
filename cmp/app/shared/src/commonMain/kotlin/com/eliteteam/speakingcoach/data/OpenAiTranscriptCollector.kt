package com.eliteteam.speakingcoach.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class OpenAiTranscriptCollector {
    private val json = Json { ignoreUnknownKeys = true }
    private val items = mutableListOf<TranscriptTurn>()
    private val assistantBuffer = StringBuilder()

    val turns: List<TranscriptTurn>
        get() = items.toList()

    fun onMessage(raw: String): String? {
        val event = runCatching { json.decodeFromString(OaiEvent.serializer(), raw) }.getOrNull()
            ?: return null
        return when (event.type) {
            "conversation.item.input_audio_transcription.completed" -> {
                val text = event.transcript?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    finishAssistant()
                    items += TranscriptTurn(role = "user", text = text)
                }
                text.ifEmpty { null }
            }
            "response.output_audio_transcript.delta",
            "response.audio_transcript.delta",
            -> {
                val delta = event.delta.orEmpty()
                if (delta.isNotEmpty()) {
                    assistantBuffer.append(delta)
                }
                assistantBuffer.toString().ifEmpty { null }
            }
            "response.output_audio_transcript.done",
            "response.audio_transcript.done",
            -> finishAssistant(event.transcript)
            else -> null
        }
    }

    fun snapshot(): List<TranscriptTurn> {
        finishAssistant()
        return turns
    }

    private fun finishAssistant(transcript: String? = null): String? {
        val text = transcript?.trim()?.takeIf { it.isNotEmpty() }
            ?: assistantBuffer.toString().trim().takeIf { it.isNotEmpty() }
        assistantBuffer.clear()
        if (text != null) {
            items += TranscriptTurn(role = "assistant", text = text)
        }
        return text
    }
}

@Serializable
private data class OaiEvent(
    val type: String,
    val transcript: String? = null,
    val delta: String? = null,
)
