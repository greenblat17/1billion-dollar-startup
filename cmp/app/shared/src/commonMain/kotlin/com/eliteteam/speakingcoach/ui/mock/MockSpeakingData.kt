package com.eliteteam.speakingcoach.ui.mock

import com.eliteteam.speakingcoach.ui.call.CallUiState
import com.eliteteam.speakingcoach.ui.home.ConversationSummary
import com.eliteteam.speakingcoach.ui.home.HomeUiState
import com.eliteteam.speakingcoach.ui.home.TopicKind
import com.eliteteam.speakingcoach.ui.profile.ProfileUiState
import com.eliteteam.speakingcoach.ui.review.ExamplePair
import com.eliteteam.speakingcoach.ui.review.ReviewExamples
import com.eliteteam.speakingcoach.ui.review.ReviewMetric
import com.eliteteam.speakingcoach.ui.review.ReviewStep
import com.eliteteam.speakingcoach.ui.review.ReviewUiState

object MockSpeakingData {
    const val UserName = "Алекс"
    const val UserEmail = "alex@example.com"

    val home = HomeUiState(
        userName = UserName,
        selectedTopic = TopicKind.Random,
        lastConversation = ConversationSummary(
            title = "Работа",
            durationMinutes = 8,
            whenLabel = null,
            topic = TopicKind.Work,
        ),
    )

    val call = CallUiState(
        elapsed = "03:24",
        caption = "That's a great point! Can you tell me more about what you're working on right now?",
        micMuted = false,
        captionsOn = true,
    )

    val review = ReviewUiState(
        steps = listOf(
            ReviewStep(
                metric = ReviewMetric.Grammar,
                score = 78,
                lead = "Главная зона роста — времена и предлоги.",
                bullets = listOf(
                    "Past Simple",
                    "Present Perfect",
                    "Предлоги места и направления",
                ),
                examples = ReviewExamples(
                    listOf(
                        ExamplePair(
                            original = "I was in Turkey last summer.",
                            improved = "I went to Turkey last summer.",
                        ),
                        ExamplePair(
                            original = "I work here since 2023.",
                            improved = "I have worked here since 2023.",
                        ),
                    ),
                ),
                tip = "Когда рассказываешь о прошлом опыте, следи за временем глагола.",
            ),
            ReviewStep(
                metric = ReviewMetric.Vocabulary,
                score = 84,
                lead = "Мысль понятная, но словарь можно сделать богаче.",
                bullets = listOf(
                    "Меньше повторов simple words",
                    "Больше сильных глаголов и прилагательных",
                    "Больше синонимов вместо very + adjective",
                ),
                examples = ReviewExamples(
                    listOf(
                        ExamplePair(
                            original = "It was very very good.",
                            improved = "It was really enjoyable.",
                        ),
                        ExamplePair(
                            original = "I work in a big company.",
                            improved = "I work in a large company.",
                        ),
                    ),
                ),
                tip = "Пробуй заменять very + adjective одним более точным словом.",
            ),
        ),
    )

    val profile = ProfileUiState(
        displayName = UserName,
        email = UserEmail,
        language = "Русский",
        tutorVoice = "Emma",
        captionsByDefault = true,
        avatarLetter = "A",
    )
}
