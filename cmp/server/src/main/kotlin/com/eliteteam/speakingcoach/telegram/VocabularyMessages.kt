package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.VocabularyCard
import com.eliteteam.speakingcoach.ai.VocabularyLesson
import com.eliteteam.speakingcoach.ai.VocabularyResponse
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.buttons.KeyboardButtonStyle
import dev.inmo.tgbotapi.utils.row

internal const val VOCABULARY_BUTTON = "📚 Vocabulary Builder"
private const val PAGE_SIZE = 5

internal fun vocabularyListText(state: VocabularyResponse, page: Int): String {
    val totalPages = maxOf(1, (state.items.size + PAGE_SIZE - 1) / PAGE_SIZE)
    val safePage = page.coerceIn(0, totalPages - 1)
    val shown = state.items.drop(safePage * PAGE_SIZE).take(PAGE_SIZE)
    val words = shown.joinToString("\n") { item ->
        val mark = if (item.id in state.selected) "✅" else "▫️"
        "$mark ${item.word}${if (item.reviewDue) " · 🔁 Ready to revisit" else ""}"
    }
    return "📚 My words\nSelected ${state.selected.size}/5\n\n" +
        (words.ifEmpty { "No words yet. Add one to start your first lesson." }) +
        "\n\nPage ${safePage + 1}/$totalPages"
}

internal fun vocabularyListKeyboard(state: VocabularyResponse, page: Int): InlineKeyboardMarkup {
    val totalPages = maxOf(1, (state.items.size + PAGE_SIZE - 1) / PAGE_SIZE)
    val safePage = page.coerceIn(0, totalPages - 1)
    return inlineKeyboard {
        state.items.drop(safePage * PAGE_SIZE).take(PAGE_SIZE).forEach { item ->
            row {
                dataButton(if (item.id in state.selected) "✅ ${item.word}" else
                    if (item.reviewDue) "🔁 ${item.word}" else item.word,
                    "vocab:${if (item.id in state.selected) "unchoose" else "choose"}:${item.id}:$safePage")
                dataButton("ℹ️", "vocab:card:${item.id}:$safePage")
            }
        }
        row {
            if (safePage > 0) dataButton("◀", "vocab:list:${safePage - 1}")
            if (safePage + 1 < totalPages) dataButton("▶", "vocab:list:${safePage + 1}")
        }
        row { dataButton("Add word", "vocab:add") }
        if (state.selected.isNotEmpty()) row {
            dataButton("🎙 Start lesson · ${state.selected.size}", "vocab:start")
        }
    }
}

internal fun vocabularyCardText(card: VocabularyCard): String =
    "📚 ${card.word}\n${card.translation}\n${card.meaning}\n\n${card.example}"

internal fun vocabularyCardKeyboard(card: VocabularyCard, page: Int, selected: Boolean): InlineKeyboardMarkup =
    inlineKeyboard {
        row { dataButton(if (selected) "Remove from selection" else "Select for lesson",
            "vocab:${if (selected) "unchoose" else "choose"}:${card.id}:$page") }
        row { dataButton("Delete", "vocab:delask:${card.id}:$page") }
        row { dataButton("← My words", "vocab:list:$page") }
    }

internal fun vocabularyDeleteKeyboard(card: VocabularyCard, page: Int): InlineKeyboardMarkup = inlineKeyboard {
    row { dataButton("Delete ${card.word}", "vocab:delete:${card.id}:$page",
        style = KeyboardButtonStyle.Danger) }
    row { dataButton("Cancel", "vocab:card:${card.id}:$page") }
}

internal fun vocabularyDraftKeyboard(): InlineKeyboardMarkup = inlineKeyboard {
    row { dataButton("Save word", "vocab:confirm") }
    row { dataButton("Cancel", "vocab:cancel") }
}

internal fun vocabularyRoundProgress(lesson: VocabularyLesson): String {
    if (lesson.format >= 4) {
        val words = (lesson.total / 3).coerceAtLeast(1)
        val progress = lesson.index.coerceIn(0, 3 * words)
        return (0 until words).joinToString("") { word ->
            when {
                progress >= 2 * words + word + 1 -> "🟢"
                lesson.format >= 5 && progress >= 2 * (word + 1) -> "🟠"
                lesson.format >= 5 && progress >= 2 * word + 1 -> "🟡"
                lesson.format == 4 && progress >= words + word + 1 -> "🟠"
                lesson.format == 4 && progress >= word + 1 -> "🟡"
                else -> "⚪️"
            }
        }
    }
    val words = (if (lesson.round == "Word game") lesson.total else lesson.total / 3).coerceAtLeast(1)
    val completed = when (lesson.round) {
        "Word game" -> lesson.index.coerceIn(0, words)
        "Recall" -> (lesson.index - 2 * words).coerceIn(0, words)
        else -> (lesson.index / 2).coerceIn(0, words)
    }
    val dots = "🟢".repeat(completed) + "⚪️".repeat(words - completed)
    if (lesson.round == "Recall" && lesson.index == 2 * words) {
        return "$words/$words → Recall · $dots 0/$words"
    }
    val stage = if (lesson.round == "Learn" && lesson.phase == "Use") "Make a phrase" else lesson.round
    return "$stage · $dots $completed/$words"
}

internal fun vocabularyLessonKeyboard(lesson: VocabularyLesson, spoken: Boolean = true): InlineKeyboardMarkup = inlineKeyboard {
    row { dataButton(vocabularyRoundProgress(lesson), "vocab:progress") }
    if (lesson.status == "active" || lesson.status == "cards") {
        if (spoken || lesson.format >= 4 || lesson.hints < 1) row {
            if (spoken) dataButton(SPOKEN_TEXT_BUTTON, "vocab:subtitle")
            if (lesson.format >= 4 || lesson.hints < 1) dataButton("Hint",
                "vocab:hint:${lesson.id}:${lesson.index}")
        }
        row { dataButton("End lesson", "vocab:end:${lesson.id}", style = KeyboardButtonStyle.Danger) }
    } else if (lesson.status == "offer") {
        row {
            dataButton("🎲 Play", "vocab:play:${lesson.id}")
            dataButton("Skip", "vocab:skip:${lesson.id}")
        }
        row { dataButton("End lesson", "vocab:end:${lesson.id}", style = KeyboardButtonStyle.Danger) }
        if (spoken) row { dataButton(SPOKEN_TEXT_BUTTON, "vocab:subtitle") }
    } else if (spoken) {
        row { dataButton(SPOKEN_TEXT_BUTTON, "vocab:subtitle") }
    }
}
