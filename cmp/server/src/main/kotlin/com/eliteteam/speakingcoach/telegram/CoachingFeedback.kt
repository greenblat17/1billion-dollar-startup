package com.eliteteam.speakingcoach.telegram

import dev.inmo.tgbotapi.types.message.textsources.TextSourcesList
import dev.inmo.tgbotapi.utils.bold
import dev.inmo.tgbotapi.utils.blockquote
import dev.inmo.tgbotapi.utils.buildEntities
import dev.inmo.tgbotapi.utils.regular
import dev.inmo.tgbotapi.utils.regularln
import dev.inmo.tgbotapi.utils.strikethrough

internal const val CORRECTION_SEP = "|||"

internal data class Correction(val wrong: String, val better: String)

private data class CorrectionSpan(
    val start: Int,
    val end: Int,
    val wrong: String,
    val better: String,
)

internal fun parseCorrections(notes: List<String>): List<Correction> =
    notes.mapNotNull { note ->
        val parts = note.split(CORRECTION_SEP, limit = 2)
        if (parts.size != 2) {
            return@mapNotNull null
        }
        val wrong = parts[0].trim()
        val better = parts[1].trim()
        if (wrong.isEmpty() || better.isEmpty()) null else Correction(wrong, better)
    }

internal fun coachingEntities(transcript: String, notes: List<String>): TextSourcesList {
    val text = transcript.trim()
    val spans = correctionSpans(text, parseCorrections(notes).take(3))
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
                strikethrough(span.wrong)
                regular(" ")
                bold(span.better)
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
                add(CorrectionSpan(start, end, correction.wrong, correction.better))
                from = end
            }
        }
    }
    val chosen = mutableListOf<CorrectionSpan>()
    val ordered = candidates.sortedWith(
        compareBy<CorrectionSpan> { it.start }.thenByDescending { it.end - it.start },
    )
    for (span in ordered) {
        if (chosen.none { it.start < span.end && span.start < it.end }) {
            chosen += span
        }
    }
    return chosen.sortedBy { it.start }
}

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
