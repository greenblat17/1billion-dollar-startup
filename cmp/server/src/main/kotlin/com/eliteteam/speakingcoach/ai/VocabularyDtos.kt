package com.eliteteam.speakingcoach.ai

import kotlinx.serialization.Serializable

@Serializable
data class VocabularyActionRequest(
    val sessionId: String,
    val action: String,
    val itemId: String? = null,
    val selected: Boolean? = null,
    val lessonId: String? = null,
    val text: String? = null,
    val messageId: Long? = null,
)

@Serializable
data class VocabularyCard(
    val id: String = "",
    val word: String = "",
    val meaning: String = "",
    val translation: String = "",
    val example: String = "",
    val source: String = "",
    val mastered: Boolean = false,
    val reviewDue: Boolean = false,
)

@Serializable
data class VocabularyLesson(
    val id: String = "",
    val status: String = "",
    val round: String = "",
    val phase: String = "",
    val index: Int = 0,
    val total: Int = 0,
    val word: VocabularyCard? = null,
    val prompt: String = "",
    val hints: Int = 0,
    val attempts: Int = 0,
)

@Serializable
data class VocabularyResponse(
    val items: List<VocabularyCard> = emptyList(),
    val selected: List<String> = emptyList(),
    val draft: VocabularyCard? = null,
    val card: VocabularyCard? = null,
    val lesson: VocabularyLesson? = null,
    val text: String = "",
    val status: String = "",
    val audio: String? = null,
    val audioContentType: String = "audio/ogg",
    val summary: String? = null,
)

@Serializable
data class VocabularyDueTarget(val sessionId: String, val kind: String, val text: String)

@Serializable
data class VocabularyDueResponse(val targets: List<VocabularyDueTarget> = emptyList())
