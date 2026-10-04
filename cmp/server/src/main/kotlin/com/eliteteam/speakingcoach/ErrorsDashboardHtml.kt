package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.ai.ErrorDay
import com.eliteteam.speakingcoach.ai.MetricsSnapshot
import java.util.Locale

internal fun errorsPageHtml(snapshot: MetricsSnapshot, summaryRoot: String = METRICS_PATH): String {
    val errors = snapshot.errors
    val today = errors?.today ?: ErrorDay()
    val failures = today.timeout + today.pipelineFailed
    val attempts = today.ok + failures
    val rate = if (attempts == 0L) "—" else String.format(Locale.US, "%.1f%%", failures * 100.0 / attempts)
    val rows = errors?.days.orEmpty().joinToString("\n") { day ->
        "<tr><td>${escapeHtml(day.day)}</td><td>${day.ok}</td><td>${day.timeout}</td>" +
            "<td>${day.pipelineFailed}</td><td>${day.timeout + day.pipelineFailed}</td></tr>"
    }.ifEmpty { "<tr><td colspan=\"5\">Пока нет данных.</td></tr>" }
    val recentRows = errors?.recent.orEmpty().take(15).joinToString("\n") { item ->
        val username = item.username.takeIf { it.isNotBlank() }?.let { "@${escapeHtml(it.removePrefix("@"))}" } ?: "—"
        "<tr><td>${escapeHtml(item.at)}</td><td>$username</td><td>${escapeHtml(item.stage)}</td>" +
            "<td>${escapeHtml(item.code)}</td><td>${escapeHtml(item.message)}</td></tr>"
    }.ifEmpty { "<tr><td colspan=\"5\">Недавних ошибок нет.</td></tr>" }
    return """
        <!doctype html>
        <html lang="ru">
        <head>
        <meta charset="utf-8">
        <meta name="robots" content="noindex">
        <title>Speaky errors</title>
        ${pageStyle()}
        </head>
        <body>
        <h1>Speaky</h1>
        ${adminTabs("$summaryRoot/errors", summaryRoot)}
        <p class="meta">${escapeHtml(snapshot.day)} · ${escapeHtml(snapshot.timezone)}. День считается по Москве.</p>
        <h2>Ошибки обработки голосовых сообщений</h2>
        <p class="meta">Считаются завершённые задания после приёма аудио. Ошибки Telegram, звонков и напоминаний сюда не входят. Данные доступны с момента включения счётчика.</p>
        <dl>
        ${card("Успешно сегодня", today.ok.toString())}
        ${card("Ошибки сегодня", failures.toString())}
        ${card("Доля ошибок", rate)}
        ${card("Таймауты", today.timeout.toString())}
        ${card("Сбой обработки", today.pipelineFailed.toString())}
        </dl>
        <h2>Последние 14 дней</h2>
        <table>
        <thead><tr><th>День</th><th>Успешно</th><th>Таймаут</th><th>Сбой обработки</th><th>Всего ошибок</th></tr></thead>
        <tbody>$rows</tbody>
        </table>
        <h2>Последние ошибки</h2>
        <p class="meta">До 15 записей за последние 30 дней. Текст сокращён до 240 символов, типичные секреты и ID сессий скрыты.</p>
        <table>
        <thead><tr><th>Время</th><th>Username</th><th>Этап</th><th>Код</th><th>Текст ошибки</th></tr></thead>
        <tbody>$recentRows</tbody>
        </table>
        </body>
        </html>
    """.trimIndent()
}
