package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.analytics.NudgeCounts
import com.eliteteam.speakingcoach.analytics.NudgeGroup
import com.eliteteam.speakingcoach.analytics.NudgeReport
import java.util.Locale

internal fun onboardingNudgeHtml(report: NudgeReport?, root: String, days: Int): String {
    val title = "<h2>Напоминания об онбординге и первой практике</h2>"
    if (report == null) return "$title<p class=\"meta\">Данные недоступны.</p>"
    val counts = report.totals
    val cohort = report.cohort
    return """
        $title
        <p>С ${escapeHtml(report.from)} по ${escapeHtml(report.to)} · Europe/Moscow ·
        <a href="$root/reminders/onboarding.json?days=$days">Скачать JSON</a></p>
        <p class="meta">Новый учёт с ${escapeHtml(report.trackingSince ?: "первой новой попытки отправки")}.
        Отправлено = принято Telegram API; прочтение неизвестно. Старые попытки не считаются доставкой.</p>
        <dl>
        ${card("Попытки", counts.attempts.toString())}
        ${card("Получатели", counts.recipients.toString())}
        ${card("Отправлено", counts.sent.toString())}
        ${card("Блокировки (403)", counts.blocked.toString())}
        ${card("Ошибки", counts.failed.toString())}
        ${card("Исход неизвестен", counts.unknown.toString())}
        ${card("Вернулись за 24 ч", nudgeRatio(counts.returned24, counts.mature24))}
        ${card("Прислали голос", nudgeRatio(counts.voice24, counts.mature24))}
        ${card("Получили результат", nudgeRatio(counts.result24, counts.mature24))}
        ${card("Начали практику", nudgeRatio(counts.practice24, counts.mature24))}
        ${card("Финальных сообщений", report.finalSent.toString())}
        ${card("Остановили сообщения", report.stoppedByUser.toString())}
        ${card("Нажали Not now", report.declinedReminder.toString())}
        </dl>
        <p class="meta">Возврат — входящее сообщение или кнопка после отправки. Голос — полученный голос онбординга,
        не обязательно распознанный. Считаем до следующего успешного напоминания, максимум 24 часа.
        Знаменатель — ${counts.mature24} отправок с полным окном 24 часа; ещё наблюдаем ${counts.pending24}.
        Один человек может вернуться после нескольких отправок. Связь не доказывает причинный эффект.</p>
        <h3>Пользователи через 7 дней после первого подтверждённого напоминания</h3>
        <p>Получили результат: ${nudgeRatio(cohort.result7, cohort.mature7)} ·
        Начали обычную практику: ${nudgeRatio(cohort.practice7, cohort.mature7)}.</p>
        <p class="meta">${cohort.users} уникальных пользователей с первым подтверждённым напоминанием в периоде;
        завершили окно ${cohort.mature7}, ещё наблюдаем ${cohort.pending7}. Первое — с начала нового учёта;
        старые доставки неизвестны. Практика — распознанный обычный голос с доставленным ответом после результата.
        Последующие напоминания входят в наблюдаемую цепочку, это не эффект одного сообщения.</p>
        ${nudgeTable("По дням", report.byDay)}
        ${nudgeTable("По этапу", report.byStage)}
        ${nudgeTable("По номеру попытки напоминания", report.byNumber)}
        <p class="meta">Номер включает старые и неуспешные попытки. Сравнение номеров наблюдательное:
        до поздних попыток доходят пользователи, не вернувшиеся раньше.</p>
        ${nudgeTable("По отсутствию активности перед отправкой", report.byInactivity)}
        ${nudgeTable("По тексту и версии", report.byTemplate)}
        ${nudgeTable("Практика: общий / персонализированный текст", report.byCopyKind)}
        ${nudgeTable("По политике времени", report.byTimingPolicy)}
        ${nudgeTable("По числу визитов для оценки времени", report.byVisitCount)}
        ${nudgeTable("Опоздание относительно расчётного времени", report.byLateness)}
    """.trimIndent()
}

private fun nudgeRatio(value: Int, total: Int): String = if (total == 0) "$value / 0 · —" else
    "$value / $total · ${String.format(Locale.US, "%.1f%%", value * 100.0 / total)}"

private fun nudgeTable(title: String, groups: List<NudgeGroup>): String {
    val rows = groups.joinToString("") { group ->
        val label = when (group.label) {
            "begin" -> "Не начали разговор"
            "voice" -> "Прервали разговор"
            "retry" -> "Ожидают Retry"
            "unknown" -> "Неизвестно (старые данные)"
            else -> group.label
        }
        "<tr><td>${escapeHtml(label)}</td>${nudgeCells(group.counts)}</tr>"
    }.ifEmpty { "<tr><td colspan=\"10\">Пока нет данных.</td></tr>" }
    return "<h3>${escapeHtml(title)}</h3><table><thead><tr><th>Группа</th><th>Отправлено</th>" +
        "<th>Блок</th><th>Ошибка</th><th>Неизвестно</th><th>Наблюдаем</th><th>Возврат / 24 ч</th>" +
        "<th>Голос / 24 ч</th><th>Результат / 24 ч</th><th>Практика / 24 ч</th></tr></thead><tbody>$rows</tbody></table>"
}

private fun nudgeCells(c: NudgeCounts): String =
    "<td>${c.sent}</td><td>${c.blocked}</td><td>${c.failed}</td><td>${c.unknown}</td><td>${c.pending24}</td>" +
        "<td>${nudgeRatio(c.returned24, c.mature24)}</td><td>${nudgeRatio(c.voice24, c.mature24)}</td>" +
        "<td>${nudgeRatio(c.result24, c.mature24)}</td><td>${nudgeRatio(c.practice24, c.mature24)}</td>"
