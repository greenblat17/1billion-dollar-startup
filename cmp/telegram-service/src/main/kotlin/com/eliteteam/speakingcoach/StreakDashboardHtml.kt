package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.ai.MetricsSnapshot
import com.eliteteam.speakingcoach.ai.RetentionCohort
import com.eliteteam.speakingcoach.ai.RetentionSlice
import com.eliteteam.speakingcoach.ai.StreakReminderBucket
import com.eliteteam.speakingcoach.ai.StreaksSnapshot
import java.util.Locale

private val BUCKET_LABELS = listOf(
    "0" to "0",
    "1" to "1",
    "2_6" to "2–6",
    "7_13" to "7–13",
    "14_plus" to "14+",
)

internal fun streaksPageHtml(snapshot: MetricsSnapshot): String = """
    <!doctype html>
    <html lang="ru">
    <head>
    <meta charset="utf-8">
    <meta name="robots" content="noindex">
    <title>Speaky streaks</title>
    ${pageStyle()}
    </head>
    <body>
    <h1>Speaky</h1>
    ${adminTabs(STREAKS_PATH)}
    <p class="meta">${escapeHtml(snapshot.day)} · ${escapeHtml(snapshot.timezone)}. День считается по Москве.</p>
    ${streaksSectionHtml(snapshot.streaks)}
    </body>
    </html>
""".trimIndent()

private fun streaksSectionHtml(streaks: StreaksSnapshot?): String {
    if (streaks == null) {
        return "<p class=\"meta\">Нет данных о стриках.</p>"
    }
    val released = streaks.retention.releasedDay?.let { "Релиз стриков: ${escapeHtml(it)}." } ?: "День релиза ещё не записан."
    return """
        <h2>Сейчас</h2>
        <table>
        <thead><tr><th>Стрик</th><th>Пользователи</th></tr></thead>
        <tbody>
        ${bucketRows(streaks)}
        </tbody>
        </table>
        <h2>Ответы на напоминания (7 дн.)</h2>
        <table>
        <thead><tr><th>Стрик</th><th>Отправлено</th><th>Ответили</th><th>Конверсия</th></tr></thead>
        <tbody>
        ${reminderRows(streaks.reminderBuckets)}
        </tbody>
        </table>
        <h2>Retention</h2>
        <p class="meta">Недельные когорты по дню активации. DN — доля активных ровно в день N. $released</p>
        <table>
        <thead><tr><th>Неделя</th><th>Размер</th><th>D1</th><th>D7</th><th>D30</th></tr></thead>
        <tbody>
        ${cohortRows(streaks.retention.cohorts)}
        ${sliceRow("До релиза", streaks.retention.before)}
        ${sliceRow("После релиза", streaks.retention.after)}
        </tbody>
        </table>
    """.trimIndent()
}

private fun bucketRows(streaks: StreaksSnapshot): String {
    val counts = streaks.buckets.associate { it.bucket to it.users }
    if (streaks.buckets.isEmpty()) {
        return "<tr><td colspan=\"2\">Пока нет данных.</td></tr>"
    }
    return BUCKET_LABELS.joinToString("\n") { (id, label) ->
        "<tr><td>$label</td><td>${counts[id] ?: 0}</td></tr>"
    }
}

private fun reminderRows(rows: List<StreakReminderBucket>): String {
    if (rows.isEmpty()) {
        return "<tr><td colspan=\"4\">Пока нет данных.</td></tr>"
    }
    val counts = rows.associateBy { it.bucket }
    return BUCKET_LABELS.joinToString("\n") { (id, label) ->
        val row = counts[id]
        val sent = row?.sent ?: 0
        val returned = row?.returned ?: 0
        "<tr><td>$label</td><td>$sent</td><td>$returned</td><td>${share(returned, sent)}</td></tr>"
    }
}

private fun cohortRows(cohorts: List<RetentionCohort>): String {
    if (cohorts.isEmpty()) {
        return "<tr><td colspan=\"5\">Пока нет когорт.</td></tr>"
    }
    return cohorts.joinToString("\n") { cohort ->
        "<tr><td>${escapeHtml(cohort.week)}</td><td>${cohort.size}</td><td>${formatShare(cohort.d1)}</td>" +
            "<td>${formatShare(cohort.d7)}</td><td>${formatShare(cohort.d30)}</td></tr>"
    }
}

private fun sliceRow(label: String, slice: RetentionSlice): String =
    "<tr><td>$label</td><td>${slice.size}</td><td>${formatShare(slice.d1)}</td>" +
        "<td>${formatShare(slice.d7)}</td><td>${formatShare(slice.d30)}</td></tr>"

private fun formatShare(value: Double?): String {
    if (value == null) {
        return "—"
    }
    return String.format(Locale.US, "%.0f%%", value * 100)
}

private fun share(part: Long, total: Long): String {
    if (total <= 0) {
        return "—"
    }
    return formatShare(part.toDouble() / total)
}
