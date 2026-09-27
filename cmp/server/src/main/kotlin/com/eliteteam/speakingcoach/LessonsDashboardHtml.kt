package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.ai.LessonLengthRow
import com.eliteteam.speakingcoach.ai.LessonsSnapshot
import com.eliteteam.speakingcoach.ai.MetricsSnapshot
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

private val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")

internal fun lessonsPageHtml(snapshot: MetricsSnapshot): String = """
    <!doctype html>
    <html lang="ru">
    <head>
    <meta charset="utf-8">
    <meta name="robots" content="noindex">
    <title>Speaky lessons</title>
    ${pageStyle()}
    </head>
    <body>
    <h1>Speaky</h1>
    ${adminTabs(LESSONS_PATH)}
    <p class="meta">${escapeHtml(snapshot.day)} · ${escapeHtml(snapshot.timezone)}. Закрытые разговоры за 14 дней, длина — секунды речи.</p>
    ${lessonsSectionHtml(snapshot.lessons)}
    </body>
    </html>
""".trimIndent()

internal fun speechDuration(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val minutes = seconds / 60
    val rest = seconds % 60
    val minutePart = if (minutes == 0L) null else "$minutes ${minuteWord(minutes)}"
    val secondPart = if (rest == 0L && minutes > 0) null else "$rest сек"
    return listOfNotNull(minutePart, secondPart).joinToString(" ")
}

private fun lessonsSectionHtml(lessons: LessonsSnapshot?): String {
    val report = lessons ?: LessonsSnapshot()
    val rows = report.rows.joinToString("") { row ->
        "<tr><td>${escapeHtml(lessonUser(row))}</td><td>${escapeHtml(moscowStart(row.startedAt))}</td>" +
            "<td>${escapeHtml(speechDuration(row.speechSeconds))}</td></tr>"
    }
    val table = if (report.rows.isEmpty()) {
        "<p class=\"meta\">Пока нет закрытых разговоров.</p>"
    } else {
        """
        <table>
        <thead><tr><th>Кто</th><th>Начало</th><th>Длина</th></tr></thead>
        <tbody>$rows</tbody>
        </table>
        """.trimIndent()
    }
    return """
        <dl>
        ${card("Разговоров", report.count.toString())}
        ${card("Средняя длина", speechDuration(report.averageSeconds))}
        ${card("Сумма речи", speechDuration(report.totalSeconds))}
        </dl>
        <h2>Каждый разговор</h2>
        $table
    """.trimIndent()
}

private fun lessonUser(row: LessonLengthRow): String {
    val parts = listOfNotNull(
        row.username?.takeIf { it.isNotBlank() }?.let { "@$it" },
        row.name?.takeIf { it.isNotBlank() },
    )
    return parts.joinToString(" · ").ifEmpty { "—" }
}

private fun moscowStart(iso: String): String = try {
    val moment = OffsetDateTime.parse(iso).atZoneSameInstant(MOSCOW)
    "${moment.toLocalDate()} ${moment.toLocalTime().withSecond(0).withNano(0)}"
} catch (error: DateTimeParseException) {
    iso.ifBlank { "—" }
}

private fun minuteWord(minutes: Long): String {
    val mod100 = minutes % 100
    val mod10 = minutes % 10
    return when {
        mod100 in 11..14 -> "минут"
        mod10 == 1L -> "минута"
        mod10 in 2..4 -> "минуты"
        else -> "минут"
    }
}
