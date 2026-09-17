package com.eliteteam.speakingcoach.ui.review

data class ReviewUiState(
    val steps: List<ReviewStep>,
)

data class ReviewStep(
    val metric: ReviewMetric,
    val score: Int,
    val lead: String,
    val bulletsTitle: BulletsTitle,
    val bullets: List<String>,
    val examplesTitle: ExamplesTitle,
    val examples: ReviewExamples,
    val tip: String,
) {
    enum class BulletsTitle { Improve, Attention }
    enum class ExamplesTitle { Quotes, Words }
}

enum class ReviewMetric {
    Grammar,
    Vocabulary,
    Pronunciation,
    Fluency,
    SpeedOfSpeech,
}

data class ReviewExamples(
    val items: List<ExamplePair>,
)

data class ExamplePair(
    val originalLabel: ExampleLabel,
    val original: String,
    val improvedLabel: ExampleLabel,
    val improved: String,
)

enum class ExampleLabel {
    YouSaid,
    Better,
    Try,
    Word,
    AsSpoken,
    Smoother,
    TryThis,
    Why,
}
