package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.speaking.Correction
import dev.inmo.tgbotapi.types.message.textsources.TextSourcesList
import dev.inmo.tgbotapi.utils.bold
import dev.inmo.tgbotapi.utils.blockquote
import dev.inmo.tgbotapi.utils.buildEntities
import dev.inmo.tgbotapi.utils.italic
import dev.inmo.tgbotapi.utils.regular
import dev.inmo.tgbotapi.utils.regularln
import dev.inmo.tgbotapi.utils.strikethrough

private const val MAX_CORRECTIONS = 3

private data class CorrectionSpan(
    val start: Int,
    val end: Int,
    val correction: Correction,
)

internal fun coachingEntities(transcript: String, corrections: List<Correction>): TextSourcesList {
    val text = transcript.trim()
    val spans = correctionSpans(text, corrections.sortedBy { it.priority }.take(MAX_CORRECTIONS))
    return buildEntities {
        regularln("🗣️ You said:")
        regularln("")
        blockquote {
            var index = 0
            for (span in spans) {
                val before = text.substring(index, span.start).trim()
                if (before.isNotEmpty()) {
                    regular(before)
                    regular("\n\n")
                }
                span.correction.kind?.let { kind ->
                    italic(correctionKindLabel(kind))
                    regular("\n")
                }
                strikethrough(span.correction.wrong)
                regular("\n")
                bold(span.correction.better)
                index = skipTrailingPunct(text, span.end)
                if (text.substring(index).isNotBlank()) {
                    regular("\n\n")
                }
            }
            if (index < text.length) {
                val tail = text.substring(index).trim()
                if (tail.isNotEmpty()) {
                    regular(tail)
                }
            }
        }
    }
}

private fun correctionSpans(transcript: String, corrections: List<Correction>): List<CorrectionSpan> {
    val candidates = buildList {
        for (correction in corrections) {
            var from = 0
            while (from < transcript.length) {
                val start = transcript.indexOf(correction.wrong, from)
                if (start < 0) {
                    break
                }
                val end = start + correction.wrong.length
                if (isWholePhrase(transcript, start, end)) {
                    add(CorrectionSpan(start, end, correction))
                }
                from = start + 1
            }
        }
    }
    val chosen = mutableListOf<CorrectionSpan>()
    val ordered = candidates.sortedWith(
        compareBy<CorrectionSpan> { it.correction.priority }
            .thenBy { it.start }
            .thenByDescending { it.end - it.start },
    )
    for (span in ordered) {
        if (chosen.none { it.start < span.end && span.start < it.end }) {
            chosen += span
        }
    }
    return chosen.sortedBy { it.start }
}

private fun isWholePhrase(text: String, start: Int, end: Int): Boolean {
    val leftOk = start == 0 || !isWordChar(text[start - 1])
    val rightOk = end == text.length || !isWordChar(text[end])
    return leftOk && rightOk
}

private fun isWordChar(char: Char): Boolean = char.isLetter() || char == '\''

private val TRAILING_PUNCT = setOf('.', '!', '?', ',', ';')

private fun skipTrailingPunct(text: String, from: Int): Int {
    var index = from
    while (index < text.length && text[index].isWhitespace()) {
        index++
    }
    if (index < text.length && text[index] in TRAILING_PUNCT) {
        return index + 1
    }
    return from
}
