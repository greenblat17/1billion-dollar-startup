package com.eliteteam.speakingcoach.data

import kotlinx.serialization.Serializable

@Serializable
data class ReviewStepDto(
    val metric: String,
    val score: Int,
    val lead: String,
    val bullets: List<String> = emptyList(),
    val examples: List<ReviewExampleDto> = emptyList(),
    val tip: String,
)

@Serializable
data class ReviewExampleDto(
    val original: String,
    val improved: String,
)
