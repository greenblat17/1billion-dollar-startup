package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.speaking.Correction
import com.eliteteam.speakingcoach.speaking.CorrectionKind
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.utils.row

internal const val SCORE_FAILED_TEXT = "Couldn't score this lesson. Try again."

private const val TELEGRAM_TEXT_LIMIT = 4096

internal enum class LessonScoreMetric(
    val prefix: String,
    val title: String,
    val emptyLine: String,
    val kind: CorrectionKind,
) {
    GRAMMAR("g", "Grammar", "No grammar corrections in this lesson.", CorrectionKind.GRAMMAR),
    VOCABULARY("v", "Vocabulary", "No vocabulary corrections in this lesson.", CorrectionKind.WORD),
    FLUENCY("f", "Fluency", "No fluency corrections in this lesson.", CorrectionKind.NATURAL),
}

internal fun lessonScoreCallbackData(metric: LessonScoreMetric, lessonId: String): String =
    "${metric.prefix}:$lessonId"

internal fun parseLessonScoreCallback(data: String): Pair<LessonScoreMetric, String>? {
    val prefix = data.substringBefore(':', missingDelimiterValue = "")
    val lessonId = data.substringAfter(':', missingDelimiterValue = "")
    if (lessonId.isBlank()) {
        return null
    }
    val metric = LessonScoreMetric.entries.firstOrNull { it.prefix == prefix } ?: return null
    return metric to lessonId
}

internal fun lessonScoreCard(
    metric: LessonScoreMetric,
    score: Int,
    corrections: List<Correction>,
): String {
    val head = "${metric.title}\nScore: $score"
    val examples = corrections
        .filter { it.kind == metric.kind }
        .map { "${it.wrong} → ${it.better}" }
    if (examples.isEmpty()) {
        return "$head\n\n${metric.emptyLine}"
    }
    val full = head + "\n\n" + examples.joinToString("\n")
    if (full.length <= TELEGRAM_TEXT_LIMIT) {
        return full
    }
    val kept = examples.toMutableList()
    var dropped = 0
    while (kept.isNotEmpty()) {
        kept.removeAt(0)
        dropped += 1
        val text = head + "\n\n" + (kept + "And $dropped more.").joinToString("\n")
        if (text.length <= TELEGRAM_TEXT_LIMIT) {
            return text
        }
    }
    return "$head\n\nAnd $dropped more."
}

internal fun scoreKeyboard(label: String, data: String): InlineKeyboardMarkup = inlineKeyboard {
    row {
        dataButton(label, data)
    }
}

internal fun grammarScoreKeyboard(lessonId: String): InlineKeyboardMarkup =
    scoreKeyboard("Grammar", lessonScoreCallbackData(LessonScoreMetric.GRAMMAR, lessonId))

internal fun nextScoreKeyboard(metric: LessonScoreMetric, lessonId: String): InlineKeyboardMarkup? {
    val next = when (metric) {
        LessonScoreMetric.GRAMMAR -> LessonScoreMetric.VOCABULARY
        LessonScoreMetric.VOCABULARY -> LessonScoreMetric.FLUENCY
        LessonScoreMetric.FLUENCY -> null
    } ?: return null
    return scoreKeyboard(next.title, lessonScoreCallbackData(next, lessonId))
}
