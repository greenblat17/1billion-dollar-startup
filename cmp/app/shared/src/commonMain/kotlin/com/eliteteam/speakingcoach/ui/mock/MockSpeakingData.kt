package com.eliteteam.speakingcoach.ui.mock

import com.eliteteam.speakingcoach.ui.call.CallUiState
import com.eliteteam.speakingcoach.ui.history.HistoryItem
import com.eliteteam.speakingcoach.ui.history.HistoryScores
import com.eliteteam.speakingcoach.ui.history.HistoryUiState
import com.eliteteam.speakingcoach.ui.home.ConversationSummary
import com.eliteteam.speakingcoach.ui.home.HomeUiState
import com.eliteteam.speakingcoach.ui.home.TopicKind
import com.eliteteam.speakingcoach.ui.profile.ProfileUiState
import com.eliteteam.speakingcoach.ui.review.ExampleLabel
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
                bulletsTitle = ReviewStep.BulletsTitle.Improve,
                bullets = listOf(
                    "Past Simple",
                    "Present Perfect",
                    "Предлоги места и направления",
                ),
                examplesTitle = ReviewStep.ExamplesTitle.Quotes,
                examples = ReviewExamples(
                    listOf(
                        ExamplePair(
                            originalLabel = ExampleLabel.YouSaid,
                            original = "I was in Turkey last summer.",
                            improvedLabel = ExampleLabel.Better,
                            improved = "I went to Turkey last summer.",
                        ),
                        ExamplePair(
                            originalLabel = ExampleLabel.YouSaid,
                            original = "I work here since 2023.",
                            improvedLabel = ExampleLabel.Better,
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
                bulletsTitle = ReviewStep.BulletsTitle.Improve,
                bullets = listOf(
                    "Меньше повторов simple words",
                    "Больше сильных глаголов и прилагательных",
                    "Больше синонимов вместо very + adjective",
                ),
                examplesTitle = ReviewStep.ExamplesTitle.Quotes,
                examples = ReviewExamples(
                    listOf(
                        ExamplePair(
                            originalLabel = ExampleLabel.YouSaid,
                            original = "It was very very good.",
                            improvedLabel = ExampleLabel.Better,
                            improved = "It was really enjoyable.",
                        ),
                        ExamplePair(
                            originalLabel = ExampleLabel.YouSaid,
                            original = "I work in a big company.",
                            improvedLabel = ExampleLabel.Better,
                            improved = "I work in a large company.",
                        ),
                    ),
                ),
                tip = "Пробуй заменять very + adjective одним более точным словом.",
            ),
            ReviewStep(
                metric = ReviewMetric.Pronunciation,
                score = 73,
                lead = "Главные сложности — звуки th, w и окончания -ed.",
                bulletsTitle = ReviewStep.BulletsTitle.Attention,
                bullets = listOf(
                    "th в think, three, they",
                    "w в work и world",
                    "Окончания -ed в worked и started",
                ),
                examplesTitle = ReviewStep.ExamplesTitle.Words,
                examples = ReviewExamples(
                    listOf(
                        ExamplePair(
                            originalLabel = ExampleLabel.Word,
                            original = "three years",
                            improvedLabel = ExampleLabel.Try,
                            improved = "/θriː jɪrz/",
                        ),
                        ExamplePair(
                            originalLabel = ExampleLabel.Word,
                            original = "worked",
                            improvedLabel = ExampleLabel.Try,
                            improved = "/wɜːrkt/",
                        ),
                    ),
                ),
                tip = "Старайся говорить медленнее на сложных звуках и отчетливо завершать слово.",
            ),
            ReviewStep(
                metric = ReviewMetric.Fluency,
                score = 81,
                lead = "Речь связная, но местами есть паузы и слова-паразиты.",
                bulletsTitle = ReviewStep.BulletsTitle.Improve,
                bullets = listOf(
                    "Меньше пауз внутри фразы",
                    "Меньше filler words: so, like, you know",
                    "Больше коротких законченных мыслей",
                ),
                examplesTitle = ReviewStep.ExamplesTitle.Quotes,
                examples = ReviewExamples(
                    listOf(
                        ExamplePair(
                            originalLabel = ExampleLabel.AsSpoken,
                            original = "So… I think… we have a lot of competitors…",
                            improvedLabel = ExampleLabel.Smoother,
                            improved = "I think we have a lot of competitors.",
                        ),
                        ExamplePair(
                            originalLabel = ExampleLabel.AsSpoken,
                            original = "I, uh, work as a software engineer…",
                            improvedLabel = ExampleLabel.Smoother,
                            improved = "I work as a software engineer.",
                        ),
                    ),
                ),
                tip = "Сначала формулируй мысль короче, а потом постепенно удлиняй ответ.",
            ),
            ReviewStep(
                metric = ReviewMetric.SpeedOfSpeech,
                score = 76,
                lead = "Темп чуть медленнее естественного, особенно на длинных ответах.",
                bulletsTitle = ReviewStep.BulletsTitle.Improve,
                bullets = listOf(
                    "Средний темп — 96 слов/мин",
                    "Комфортный диапазон — 110–140 слов/мин",
                    "Говори фразу смысловыми блоками",
                ),
                examplesTitle = ReviewStep.ExamplesTitle.Quotes,
                examples = ReviewExamples(
                    listOf(
                        ExamplePair(
                            originalLabel = ExampleLabel.AsSpoken,
                            original = "Right now… we are… building an AI SaaS…",
                            improvedLabel = ExampleLabel.Better,
                            improved = "Right now we are building an AI SaaS.",
                        ),
                        ExamplePair(
                            originalLabel = ExampleLabel.TryThis,
                            original = "Right now we are building / an AI SaaS / for sourcing candidates.",
                            improvedLabel = ExampleLabel.Why,
                            improved = "Так легче держать естественный темп речи.",
                        ),
                    ),
                ),
                tip = "Не ускоряйся резко — лучше говорить чуть быстрее, но ровно и без лишних пауз.",
            ),
        ),
    )

    val history = HistoryUiState(
        items = listOf(
            HistoryItem(
                id = "1",
                title = "Как я строю AI-стартап",
                durationMinutes = 12,
                whenLabel = "Сегодня, 10:24",
                scores = HistoryScores(grammar = 78, vocabulary = 84, pronunciation = 73),
            ),
            HistoryItem(
                id = "2",
                title = "Почему я хочу переехать в США",
                durationMinutes = 9,
                whenLabel = "9 сентября, 18:12",
                scores = HistoryScores(grammar = 82, vocabulary = 76, pronunciation = 80),
            ),
            HistoryItem(
                id = "3",
                title = "Поездка в Турцию прошлым летом",
                durationMinutes = 11,
                whenLabel = "7 сентября, 14:36",
                scores = HistoryScores(grammar = 75, vocabulary = 88, pronunciation = 70),
            ),
            HistoryItem(
                id = "4",
                title = "Обсуждение книг, которые я читаю",
                durationMinutes = 10,
                whenLabel = "5 сентября, 21:03",
                scores = HistoryScores(grammar = 80, vocabulary = 83, pronunciation = 76),
            ),
            HistoryItem(
                id = "5",
                title = "Мои планы на следующий год",
                durationMinutes = 8,
                whenLabel = "3 сентября, 11:17",
                scores = HistoryScores(grammar = 77, vocabulary = 79, pronunciation = 71),
            ),
            HistoryItem(
                id = "6",
                title = "Роль технологий в образовании",
                durationMinutes = 12,
                whenLabel = "31 августа, 16:20",
                scores = HistoryScores(grammar = 85, vocabulary = 81, pronunciation = 78),
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
