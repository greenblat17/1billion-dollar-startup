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
                    flushAssistant()
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
            -> {
                val text = event.transcript?.trim()?.ifEmpty { null }
                    ?: assistantBuffer.toString().trim().ifEmpty { null }
                assistantBuffer.clear()
                if (!text.isNullOrEmpty()) {
                    items += TranscriptTurn(role = "assistant", text = text)
                }
                text
            }
            else -> null
        }
    }

    fun snapshot(): List<TranscriptTurn> {
        flushAssistant()
        return turns
    }

    private fun flushAssistant() {
        val text = assistantBuffer.toString().trim()
        assistantBuffer.clear()
        if (text.isNotEmpty()) {
            items += TranscriptTurn(role = "assistant", text = text)
        }
    }
}

@Serializable
private data class OaiEvent(
    val type: String,
    val transcript: String? = null,
    val delta: String? = null,
)
