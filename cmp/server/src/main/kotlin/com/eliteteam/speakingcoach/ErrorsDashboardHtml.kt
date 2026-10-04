package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.analytics.VoiceAttemptReport
import com.eliteteam.speakingcoach.ai.ErrorDay
import com.eliteteam.speakingcoach.ai.MetricsSnapshot
import java.util.Locale

internal fun errorsPageHtml(snapshot: MetricsSnapshot?, voice: VoiceAttemptReport? = null,
                            summaryRoot: String = METRICS_PATH): String {
    val errors = snapshot?.errors
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
        val telegramId = item.telegramId?.toString() ?: "—"
        "<tr><td>${escapeHtml(item.at)}</td><td>$username</td><td>$telegramId</td><td>${escapeHtml(item.stage)}</td>" +
            "<td>${escapeHtml(item.code)}</td><td>${escapeHtml(item.message)}</td>" +
            "<td><code>${escapeHtml(item.attemptId.ifBlank { "—" })}</code></td>" +
            "<td><code>${escapeHtml(item.jobId.ifBlank { "—" })}</code></td></tr>"
    }.ifEmpty { "<tr><td colspan=\"8\">Недавних ошибок нет.</td></tr>" }
    val reasonRows = errors?.reasons.orEmpty().groupBy { it.stage to it.reason }
        .map { (key, values) -> Triple(key.first, key.second, values.sumOf { it.count }) }
        .sortedByDescending { it.third }.joinToString("\n") { (stage, reason, count) ->
            "<tr><td>${escapeHtml(stage)}</td><td>${escapeHtml(reason)}</td><td>$count</td></tr>"
        }.ifEmpty { "<tr><td colspan=\"3\">Пока нет данных.</td></tr>" }
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
        <p class="meta">${escapeHtml(snapshot?.day ?: "—")} · Europe/Moscow. День считается по Москве.</p>
        ${voiceSection(voice)}
        ${partialSection(snapshot)}
        ${providerSection(snapshot)}
        <h2>Ошибки обработки голосовых сообщений</h2>
        <p class="meta">AI service: завершённые задания после приёма аудио. День завершения задания. Звонки и напоминания не входят. Данные доступны с момента включения счётчика.</p>
        ${if (snapshot == null) "<p>Метрики AI service недоступны.</p>" else ""}
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
        <h3>Причины сбоев AI service · 14 дней</h3>
        <table><thead><tr><th>Этап</th><th>Причина</th><th>Случаев</th></tr></thead><tbody>$reasonRows</tbody></table>
        <h2>Последние ошибки</h2>
        <p class="meta">До 15 записей за последние 30 дней. Текст сокращён до 240 символов, типичные секреты и ID сессий скрыты.</p>
        <table>
        <thead><tr><th>Время</th><th>Username</th><th>Telegram ID</th><th>Этап</th><th>Код</th><th>Текст ошибки</th><th>ID попытки</th><th>Job ID</th></tr></thead>
        <tbody>$recentRows</tbody>
        </table>
        </body>
        </html>
    """.trimIndent()
}

private fun providerSection(snapshot: MetricsSnapshot?): String {
    if (snapshot == null) return "<h2>Провайдеры</h2><p>Метрики AI service недоступны.</p>"
    val rows = snapshot.providerOutcomes.entries.sortedBy { it.key }.joinToString("\n") { (key, value) ->
        val parts = key.split(':', limit = 4)
        val columns = if (parts.size == 4) parts else listOf(key, "—", "—", "—")
        "<tr>${columns.joinToString("") { "<td>${escapeHtml(it)}</td>" }}<td>$value</td></tr>"
    }.ifEmpty { "<tr><td colspan=\"5\">Пока нет данных.</td></tr>" }
    return """
        <h2>Провайдеры · сегодня</h2>
        <p class="meta">Попытки и итоговые операции STT, генерации ответа и TTS считаются отдельно. Ошибка первой попытки при успешном повторе не означает ошибку голосового ответа.</p>
        <table><thead><tr><th>Этап</th><th>Провайдер</th><th>Попытка / итог</th><th>Результат</th><th>Количество</th></tr></thead><tbody>$rows</tbody></table>
    """.trimIndent()
}

private fun partialSection(snapshot: MetricsSnapshot?): String {
    if (snapshot == null) return "<h2>Частичные сбои</h2><p>Метрики AI service недоступны.</p>"
    val corrections = snapshot.corrections.filterKeys { it in setOf("shown", "empty", "filtered", "deadline", "provider_timeout", "rate_limit", "provider_5xx", "provider_4xx", "network", "no_choices", "empty_text", "invalid_json", "invalid_schema", "token_limit", "other_error") }
    val correctionTotal = corrections.values.sumOf { it.count }
    val correctionFailures = corrections.filterKeys { it !in setOf("shown", "empty", "filtered") }.values.sumOf { it.count }
    val featureCounts = mutableMapOf<String, MutableMap<String, Long>>()
    snapshot.partialFailures.forEach { (key, value) ->
        val feature = key.substringBefore(':')
        val status = key.substringAfter(':', "")
        if (status in setOf("attempted", "succeeded", "failed", "skipped"))
            featureCounts.getOrPut(feature) { mutableMapOf() }[status] = value
    }
    snapshot.v2?.clients.orEmpty().firstOrNull { it.client == "telegram" }?.actions.orEmpty().forEach { (key, value) ->
        val status = key.removePrefix("follow_up_card_")
        if (key.startsWith("follow_up_card_") && status in setOf("attempted", "succeeded", "failed"))
            featureCounts.getOrPut("follow_up_card") { mutableMapOf() }[status] = value
    }
    val rows = featureCounts.entries.sortedBy { it.key }.joinToString("\n") { (feature, counts) ->
        "<tr><td>${escapeHtml(partialFeatureLabel(feature))}</td><td>${counts["attempted"] ?: 0}</td>" +
            "<td>${counts["succeeded"] ?: 0}</td><td>${counts["failed"] ?: 0}</td>" +
            "<td>${counts["skipped"] ?: 0}</td></tr>"
    }.ifEmpty { "<tr><td colspan=\"5\">Пока нет данных.</td></tr>" }
    val recent = snapshot.partialRecent.take(15).joinToString("\n") { event ->
        "<tr><td>${escapeHtml(event.at)}</td><td>${escapeHtml(event.feature)}</td>" +
            "<td>${escapeHtml(event.reason)}</td></tr>"
    }.ifEmpty { "<tr><td colspan=\"3\">Недавних частичных сбоев нет.</td></tr>" }
    return """
        <h2>Частичные сбои</h2>
        <p class="meta">Операции AI service за сегодня. Пока эти счётчики не связаны с фактом доставки конкретного ответа; они не входят в долю ошибок голосовых сообщений.</p>
        <dl>${card("Запросы исправлений", correctionTotal.toString())}${card("Сбои исправлений", correctionFailures.toString())}</dl>
        <table><thead><tr><th>Функция</th><th>Попыток</th><th>Успешно</th><th>Сбой</th><th>Пропущено</th></tr></thead><tbody>$rows</tbody></table>
        <h3>Последние частичные сбои AI service</h3>
        <table><thead><tr><th>Время</th><th>Функция</th><th>Причина</th></tr></thead><tbody>$recent</tbody></table>
    """.trimIndent()
}

private fun partialFeatureLabel(feature: String): String = when (feature) {
    "streak" -> "Серия занятий"
    "call_turn" -> "История звонка"
    "call_summary" -> "Итог звонка"
    "assessment" -> "Оценка онбординга"
    "review" -> "Итог онбординга"
    "review_verification" -> "Проверка исправлений"
    "closing_voice" -> "Финальный голос"
    "closing_callback" -> "Финальная реплика"
    "follow_up_card" -> "Дополнительная карточка Telegram"
    else -> feature
}

private fun voiceSection(report: VoiceAttemptReport?): String {
    if (report == null) return """
        <h2>Результат голосового сообщения в Telegram</h2>
        <p>Данные Telegram недоступны.</p>
    """.trimIndent()
    val today = report.days.firstOrNull()
    val delivered = today?.delivered ?: 0
    val failed = today?.failed ?: 0
    val rate = if (delivered + failed == 0) "—" else String.format(Locale.US, "%.1f%%", failed * 100.0 / (delivered + failed))
    val days = report.days.joinToString("\n") { day ->
        "<tr><td>${escapeHtml(day.day)}</td><td>${day.delivered}</td><td>${day.failed}</td>" +
            "<td>${day.affectedChats}</td><td>${day.pending}</td><td>${day.notEligible}</td></tr>"
    }.ifEmpty { "<tr><td colspan=\"6\">Пока нет данных.</td></tr>" }
    val attempts14 = report.days.sumOf { it.delivered + it.failed }
    val todayCounts = report.todayBreakdown.associate { (it.stage to it.reason) to it.count }
    val reasons = report.breakdown.joinToString("\n") { item ->
        val share = if (attempts14 == 0) "—" else String.format(Locale.US, "%.1f%%", item.count * 100.0 / attempts14)
        "<tr><td>${escapeHtml(item.stage)}</td><td>${escapeHtml(item.reason)}</td>" +
            "<td>${todayCounts[item.stage to item.reason] ?: 0}</td><td>${item.count}</td><td>$share</td></tr>"
    }.ifEmpty { "<tr><td colspan=\"5\">Пока нет данных.</td></tr>" }
    val latency = report.latencies.joinToString("\n") { item ->
        "<tr><td>${escapeHtml(item.stage)}</td><td>${escapeHtml(item.outcome)}</td><td>${item.count}</td>" +
            "<td>${item.p50Ms}</td><td>${item.p95Ms}</td></tr>"
    }.ifEmpty { "<tr><td colspan=\"5\">Пока нет данных.</td></tr>" }
    val todayLatency = report.todayLatencies.joinToString("\n") { item ->
        "<tr><td>${escapeHtml(item.stage)}</td><td>${escapeHtml(item.outcome)}</td><td>${item.count}</td>" +
            "<td>${item.p50Ms}</td><td>${item.p95Ms}</td></tr>"
    }.ifEmpty { "<tr><td colspan=\"5\">Пока нет данных.</td></tr>" }
    fun chatRows(items: List<com.eliteteam.speakingcoach.analytics.VoiceRepeated>): String = items.joinToString("\n") { item ->
        "<tr><td>${escapeHtml(item.username.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "—")}</td>" +
            "<td>${item.chatId}</td><td>${item.failures}</td><td><code>${escapeHtml(item.attemptId)}</code></td></tr>"
    }.ifEmpty { "<tr><td colspan=\"4\">Пока нет данных.</td></tr>" }
    val affected = chatRows(report.affected)
    val repeated = chatRows(report.repeated)
    val recent = report.recent.joinToString("\n") { item ->
        "<tr><td>${escapeHtml(item.receivedAt.atZone(java.time.ZoneId.of("Europe/Moscow")).toString())}</td>" +
            "<td>${escapeHtml(item.username.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "—")}</td>" +
            "<td>${item.chatId}</td><td>${escapeHtml(item.outcome ?: "unknown")}</td>" +
            "<td>${escapeHtml(item.stage ?: "other")}</td><td>${escapeHtml(item.reason ?: "unknown")}</td>" +
            "<td><code>${escapeHtml(item.attemptId)}</code></td>" +
            "<td><code>${escapeHtml(item.jobId ?: "—")}</code></td></tr>"
    }.ifEmpty { "<tr><td colspan=\"8\">Недавних ошибок нет.</td></tr>" }
    return """
        <h2>Результат голосового сообщения в Telegram</h2>
        <p class="meta">Источник: бот/Postgres. День приёма сообщения по Москве; история 14 дней, записи хранятся до 30 дней. Первые данные: ${escapeHtml(report.firstObserved ?: "ещё нет")}. «Доставлено» означает, что Telegram принял голосовой ответ, а не что пользователь его прослушал. Голоса до допуска к обработке в долю ошибок не входят.</p>
        ${if (report.truncated) "<p>Отчёт ограничен 50 000 последними попытками за период; агрегаты неполные.</p>" else ""}
        <dl>
        ${card("Доставлено сегодня", if (today == null) "—" else delivered.toString())}
        ${card("Не получили голосовой ответ", if (today == null) "—" else failed.toString())}
        ${card("Доля ошибок", rate)}
        ${card("Затронуто чатов за 14 дней", report.affectedChats.toString())}
        ${card("Сбои записи метрик", report.writeFailures.toString())}
        </dl>
        <h3>Последние 14 дней</h3>
        <table><thead><tr><th>День</th><th>Доставлено</th><th>Ошибка</th><th>Затронуто чатов</th><th>В работе</th><th>Вне обработки</th></tr></thead><tbody>$days</tbody></table>
        <h3>Где произошла ошибка</h3>
        <table><thead><tr><th>Этап</th><th>Причина</th><th>Сегодня</th><th>14 дней</th><th>Доля от обработанных за 14 дней</th></tr></thead><tbody>$reasons</tbody></table>
        <h3>Время ответа, мс · 14 дней</h3>
        <table><thead><tr><th>Этап</th><th>Результат</th><th>Выборка</th><th>p50</th><th>p95</th></tr></thead><tbody>$latency</tbody></table>
        <h3>Время ответа, мс · сегодня</h3>
        <table><thead><tr><th>Этап</th><th>Результат</th><th>Выборка</th><th>p50</th><th>p95</th></tr></thead><tbody>$todayLatency</tbody></table>
        <h3>Три и более неудачи подряд</h3>
        <table><thead><tr><th>Username</th><th>Telegram ID чата</th><th>Неудач</th><th>ID попытки</th></tr></thead><tbody>$repeated</tbody></table>
        <h3>Чаты с ошибками за 14 дней</h3>
        <table><thead><tr><th>Username</th><th>Telegram ID чата</th><th>Неудач</th><th>ID последней попытки</th></tr></thead><tbody>$affected</tbody></table>
        <h3>Последние 15 неудачных сообщений</h3>
        <table><thead><tr><th>Получено</th><th>Username</th><th>Telegram ID чата</th><th>Итог</th><th>Этап</th><th>Причина</th><th>ID попытки</th><th>Job ID</th></tr></thead><tbody>$recent</tbody></table>
    """.trimIndent()
}
