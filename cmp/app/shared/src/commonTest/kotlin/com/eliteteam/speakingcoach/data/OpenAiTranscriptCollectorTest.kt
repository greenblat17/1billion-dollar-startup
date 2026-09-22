package com.eliteteam.speakingcoach.data

import kotlin.test.Test
import kotlin.test.assertEquals

class OpenAiTranscriptCollectorTest {
    @Test
    fun collectsUserAndAssistantTurnsInOrder() {
        val collector = OpenAiTranscriptCollector()
        collector.onMessage(
            """{"type":"conversation.item.input_audio_transcription.completed","transcript":"I work here since 2023."}""",
        )
        collector.onMessage(
            """{"type":"response.output_audio_transcript.delta","delta":"Have you "}""",
        )
        collector.onMessage(
            """{"type":"response.output_audio_transcript.delta","delta":"tried present perfect?"}""",
        )
        collector.onMessage(
            """{"type":"response.output_audio_transcript.done"}""",
        )
        assertEquals(
            listOf(
                TranscriptTurn("user", "I work here since 2023."),
                TranscriptTurn("assistant", "Have you tried present perfect?"),
            ),
            collector.snapshot(),
        )
    }

    @Test
    fun snapshotFlushesPartialAssistant() {
        val collector = OpenAiTranscriptCollector()
        collector.onMessage(
            """{"type":"response.audio_transcript.delta","delta":"Hey!"}""",
        )
        assertEquals(
            listOf(TranscriptTurn("assistant", "Hey!")),
            collector.snapshot(),
        )
    }

    @Test
    fun ignoresUnknownEvents() {
        val collector = OpenAiTranscriptCollector()
        assertEquals(null, collector.onMessage("""{"type":"session.created"}"""))
        assertEquals(emptyList(), collector.snapshot())
    }
}
