package com.eliteteam.speakingcoach.ui.review

data class ReviewUiState(
    val phase: ReviewPhase = ReviewPhase.Ready,
    val steps: List<ReviewStep> = emptyList(),
)

enum class ReviewPhase {
    Loading,
    Ready,
    TooShort,
    Failed,
}

data class ReviewStep(
    val metric: ReviewMetric,
    val score: Int,
    val lead: String,
    val bullets: List<String>,
    val examples: ReviewExamples,
    val tip: String,
)

enum class ReviewMetric {
    Grammar,
    Vocabulary,
}

data class ReviewExamples(
    val items: List<ExamplePair>,
)

data class ExamplePair(
    val original: String,
    val improved: String,
)
