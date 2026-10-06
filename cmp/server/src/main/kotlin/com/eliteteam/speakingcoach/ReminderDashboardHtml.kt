package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.ai.MetricsSnapshot
import com.eliteteam.speakingcoach.ai.ReminderClockSummary
import com.eliteteam.speakingcoach.ai.ReminderRun
import com.eliteteam.speakingcoach.ai.RemindersSnapshot
import com.eliteteam.speakingcoach.analytics.ReminderOfferSummary
import com.eliteteam.speakingcoach.telegram.REMINDER_TEMPLATES
import com.eliteteam.speakingcoach.telegram.STREAK_REMINDER_TEMPLATES
import com.eliteteam.speakingcoach.telegram.reminderTemplateById
import com.eliteteam.speakingcoach.telegram.renderReminder
import java.time.Duration
import java.time.OffsetDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale

private const val SEGMENT_NEW = "new"

internal fun remindersPageHtml(
    snapshot: MetricsSnapshot?,
    notice: String?,
    controls: Boolean,
    summaryRoot: String = "/admin/metrics",
    clock: ReminderClockSummary? = null,
    offers: ReminderOfferSummary? = null,
    days: Int = 7,
): String = """
    <!doctype html>
    <html lang="ru">
    <head>
    <meta charset="utf-8">
    <meta name="robots" content="noindex">
    <title>Speaky reminders</title>
    ${pageStyle()}
    </head>
    <body>
    <h1>Speaky</h1>
    ${adminTabs("$summaryRoot/reminders", summaryRoot)}
    <p class="meta">${escapeHtml(snapshot?.day ?: LocalDate.now(ZoneId.of("Europe/Moscow")).toString())} · Europe/Moscow. Время отправки задаёт пользователь.</p>
    ${reminderAnalyticsHtml(snapshot?.reminders, clock, offers, days, summaryRoot)}
    ${remindersSectionHtml(snapshot?.reminders, notice, controls, summaryRoot)}
    </body>
    </html>
""".trimIndent()

private fun reminderAnalyticsHtml(
    reminders: RemindersSnapshot?, clock: ReminderClockSummary?, offers: ReminderOfferSummary?,
    days: Int, summaryRoot: String,
): String {
    val today = LocalDate.now(ZoneId.of("Europe/Moscow"))
    val period = if (days == 30) 30 else 7
    val from = today.minusDays(period.toLong() - 1)
    val maturedTo = today.minusDays(2)
    val maturedFrom = maturedTo.minusDays(period.toLong() - 1)
    val trackingSince = reminders?.trackingSince?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val trackingNote = trackingSince?.let { " Учёт новых событий с $it; более ранние дни недоступны." }.orEmpty()
    val selector = "<p class=\"meta\">Период: " + listOf(7, 30).joinToString(" · ") { choice ->
        if (choice == period) "$choice дней" else "<a href=\"$summaryRoot/reminders?days=$choice\">$choice дней</a>"
    } + "</p>"
    val distribution = if (clock == null) "<p class=\"meta\">Текущие настройки недоступны.</p>" else {
        val rows = (0..23).joinToString("") { hour ->
            val key = "%02d".format(hour)
            val count = clock.hours[key] ?: 0
            val value = if (clock.active > 0) count * 100.0 / clock.active else 0.0
            val percent = if (clock.active > 0) String.format(Locale.US, "%.1f%%", value) else "—"
            val meter = String.format(Locale.US, "%.1f", value)
            "<tr><th scope=\"row\">$key:00–$key:59</th><td>$count</td>" +
                "<td><meter min=\"0\" max=\"100\" value=\"$meter\"></meter> $percent</td></tr>"
        }
        "<p class=\"meta\">Активных настроек: ${clock.active}. Все пользователи; время по Москве. " +
            "Текущий срез, без фильтра по датам.</p><table><thead><tr><th>Выбрали время</th>" +
            "<th>Пользователи</th><th>Доля от ${clock.active}</th></tr></thead><tbody>$rows</tbody></table>"
    }
    val offered = if (offers == null) "<p class=\"meta\">Данные о предложениях онбординга недоступны.</p>" else
        "<p>${offers.saved} / ${offers.offered} · ${rate(offers.saved.toLong(), offers.offered.toLong())} сохранили время " +
            "после предложения в онбординге</p><p class=\"meta\">Уникальные пользователи с предложением " +
            "с $from по $today. Историческая конверсия; поздний /remind и отключения в неё не входят.</p>"
    val efficiency = if (reminders == null) "<p class=\"meta\">Данные об отправках недоступны.</p>" else if (
        trackingSince != null && trackingSince.isAfter(maturedTo)
    ) "<p class=\"meta\">Ещё нет автоматических отправок с завершённым окном ответа.</p>" else {
        val selected = reminders.analyticsDays.filter { row ->
            LocalDate.parse(row.day) in maturedFrom..maturedTo &&
                (trackingSince == null || !LocalDate.parse(row.day).isBefore(trackingSince))
        }
        val rows = (0..23).joinToString("") { hour ->
            val key = "%02d".format(hour)
            val sent = selected.sumOf { it.hours[key]?.sent ?: 0 }
            val returned = selected.sumOf { it.hours[key]?.returned ?: 0 }
            "<tr><th scope=\"row\">$key:00–$key:59</th><td>$sent</td><td>$returned</td><td>${rate(returned, sent)}</td></tr>"
        }
        "<p class=\"meta\">Автоматические отправки с $maturedFrom по $maturedTo; полные 24 часа для ответа.$trackingNote " +
            "Час сохранён при отправке. Связь с ответом не доказывает влияние времени.</p>" +
            "<table><thead><tr><th>Выбранный час</th><th>Отправлено</th><th>Ответили</th><th>Доля</th></tr></thead><tbody>$rows</tbody></table>"
    }
    val outcomes = if (reminders == null) "<p class=\"meta\">Исходы недоступны.</p>" else {
        val rows = reminders.analyticsDays.filter {
            LocalDate.parse(it.day) in from..today && (trackingSince == null || !LocalDate.parse(it.day).isBefore(trackingSince))
        }
            .joinToString("") { row ->
                val unknown = (row.claimedAuto - row.autoSent - row.autoBlocked - row.autoFailed).coerceAtLeast(0)
                "<tr><td>${escapeHtml(row.day)}</td><td>${row.skippedActive}</td><td>${row.autoSent}</td>" +
                    "<td>${row.autoBlocked}</td><td>${row.autoFailed}</td><td>$unknown</td></tr>"
            }
        "<p class=\"meta\">Авторассылка с $from по $today.$trackingNote «Практиковались» — пропущены до отправки; " +
            "«Неизвестно» — адресат получен, но отчёт об отправке не записан.</p>" +
            "<table><thead><tr><th>День</th><th>Практиковались</th><th>Отправлено</th>" +
            "<th>Блок</th><th>Ошибка</th><th>Неизвестно</th></tr></thead><tbody>$rows</tbody></table>"
    }
    val changes = reminders?.settingUsers?.get(period.toString())?.let { users ->
        "<p>Установили после отсутствия настройки: ${users.setFromEmpty} · Сменили время: ${users.changed} · " +
            "Отключили: ${users.cleared}</p><p class=\"meta\">Уникальные пользователи в каждом действии с $from по $today. " +
            "Один пользователь может входить в несколько действий.$trackingNote</p>"
    } ?: "<p class=\"meta\">Изменения настроек недоступны.</p>"
    return "$selector<h2>Выбранное время</h2>$distribution<h2>Конверсия предложения</h2>$offered" +
        "<h2>Ответы по выбранному часу</h2>$efficiency<h2>Исходы по дням</h2>$outcomes" +
        "<h2>Изменения настроек</h2>$changes"
}

private fun remindersSectionHtml(
    reminders: RemindersSnapshot?,
    notice: String?,
    controls: Boolean,
    summaryRoot: String,
): String {
    val body = if (reminders == null) {
        "<p class=\"meta\">Нет данных о напоминаниях.</p>"
    } else {
        """
        <dl>
        ${card("Отправлено сегодня", reminders.today.sent.toString())}
        ${card("Ответили за 24 ч (7 дн.)", countWithRate(reminders.week.returned, reminders.week.sent))}
        ${card("Заблокировали (7 дн.)", countWithRate(reminders.week.blocked, reminders.week.sent + reminders.week.blocked))}
        ${card("Медиана до ответа", formatWait(reminders.replyMedianSeconds))}
        ${card("Прогноз на сегодня", reminders.forecast.toString())}
        ${card("Авто-рассылка сегодня", autoTodayLabel(reminders.autoToday))}
        </dl>
        ${controlsHtml(reminders.forecast, controls, summaryRoot)}
        <h2>Рассылки</h2>
        <table>
        <thead><tr><th>Начало</th><th>Режим</th><th>Claimed</th><th>Отправлено</th><th>Блок</th><th>Ошибки</th><th>Длительность</th></tr></thead>
        <tbody>
        ${runRows(reminders)}
        </tbody>
        </table>
        <h2>По дням</h2>
        <table>
        <thead><tr><th>День</th><th>Отправлено</th><th>Ответили</th><th>Конверсия</th><th>Блок</th><th>Ошибки</th></tr></thead>
        <tbody>
        ${dayRows(reminders)}
        </tbody>
        </table>
        <h2>Шаблоны</h2>
        <table>
        <thead><tr><th>Шаблон</th><th>Отправлено</th><th>Ответили</th><th>Конверсия</th><th>Блок</th></tr></thead>
        <tbody>
        ${templateRows(reminders)}
        </tbody>
        </table>
        <h2>Сегменты (7 дн.)</h2>
        <table>
        <thead><tr><th>Сегмент</th><th>Отправлено</th><th>Ответили</th><th>Конверсия</th></tr></thead>
        <tbody>
        ${segmentRows(reminders)}
        </tbody>
        </table>
        """.trimIndent()
    }
    return "${noticeHtml(notice)}\n$body"
}

private fun noticeHtml(notice: String?): String = when (notice) {
    NOTICE_STARTED -> "<p class=\"notice\">Рассылка запущена. Обновите страницу через минуту.</p>"
    NOTICE_BUSY -> "<p class=\"notice warn\">Рассылка уже идёт.</p>"
    NOTICE_TEST_SENT -> "<p class=\"notice\">Тестовое напоминание отправлено.</p>"
    NOTICE_TEST_FAILED -> "<p class=\"notice warn\">Не удалось отправить тестовое напоминание.</p>"
    NOTICE_TEST_INVALID -> "<p class=\"notice warn\">Проверьте chat id и шаблон.</p>"
    else -> ""
}

private fun controlsHtml(forecast: Long, controls: Boolean, summaryRoot: String): String {
    if (!controls) {
        return "<p class=\"meta\">Ручной запуск доступен только в webhook-режиме.</p>"
    }
    val options = (REMINDER_TEMPLATES + STREAK_REMINDER_TEMPLATES).joinToString("\n") { template ->
        "<option value=\"${escapeHtml(template.id)}\">${escapeHtml(template.id)}</option>"
    }
    return """
        <form class="inline" method="post" action="$summaryRoot/reminders/test">
        <label>Chat id <input name="chatId" inputmode="numeric" required></label>
        <label>Шаблон <select name="template"><option value="today">Сегодняшний</option>
        $options
        </select></label>
        <button type="submit">Отправить на себя</button>
        </form>
        <form class="inline" method="post" action="$summaryRoot/reminders/send" onsubmit="return confirm('Отправить напоминание примерно $forecast людям?')">
        <button type="submit">Отправить всем сейчас</button>
        </form>
    """.trimIndent()
}

private fun runRows(reminders: RemindersSnapshot): String {
    if (reminders.runs.isEmpty()) {
        return "<tr><td colspan=\"7\">Рассылок ещё не было.</td></tr>"
    }
    return reminders.runs.joinToString("\n") { run ->
        "<tr><td>${escapeHtml(shortTime(run.startedAt))}</td><td>${modeLabel(run.mode)}</td><td>${run.claimed}</td>" +
            "<td>${run.sent}</td><td>${run.blocked}</td><td>${run.failed}</td><td>${escapeHtml(runDuration(run))}</td></tr>"
    }
}

private fun dayRows(reminders: RemindersSnapshot): String {
    val days = reminders.days.filter { it.sent + it.blocked + it.failed + it.returned > 0 }
    if (days.isEmpty()) {
        return "<tr><td colspan=\"6\">Пока нет данных.</td></tr>"
    }
    return days.joinToString("\n") { day ->
        "<tr><td>${escapeHtml(day.day)}</td><td>${day.sent}</td><td>${day.returned}</td>" +
            "<td>${rate(day.returned, day.sent)}</td><td>${day.blocked}</td><td>${day.failed}</td></tr>"
    }
}

private fun templateRows(reminders: RemindersSnapshot): String {
    if (reminders.templates.isEmpty()) {
        return "<tr><td colspan=\"5\">Пока нет данных.</td></tr>"
    }
    return reminders.templates.joinToString("\n") { stats ->
        val text = reminderTemplateById(stats.templateId)?.let { renderReminder(it, null, streak = 2) } ?: "удалён из пула"
        "<tr><td>${escapeHtml(stats.templateId)}<br><span class=\"template\">${escapeHtml(text)}</span></td>" +
            "<td>${stats.sent}</td><td>${stats.returned}</td><td>${rate(stats.returned, stats.sent)}</td><td>${stats.blocked}</td></tr>"
    }
}

private fun segmentRows(reminders: RemindersSnapshot): String {
    if (reminders.segments.isEmpty()) {
        return "<tr><td colspan=\"4\">Пока нет данных.</td></tr>"
    }
    return reminders.segments.joinToString("\n") { segment ->
        val label = if (segment.segment == SEGMENT_NEW) "Только /start" else "Активированные"
        "<tr><td>$label</td><td>${segment.sent}</td><td>${segment.returned}</td><td>${rate(segment.returned, segment.sent)}</td></tr>"
    }
}

private fun autoTodayLabel(run: ReminderRun?): String {
    if (run == null) {
        return "ещё не было"
    }
    val time = run.startedAt.drop(11).take(5).ifEmpty { "?" }
    return "$time · ${run.sent}"
}

internal fun shortTime(iso: String): String = try {
    val moment = OffsetDateTime.parse(iso)
    "${moment.toLocalDate()} ${moment.toLocalTime().withSecond(0).withNano(0)}"
} catch (error: DateTimeParseException) {
    iso
}

private fun modeLabel(mode: String): String = if (mode == "manual") "вручную" else "авто"

private fun runDuration(run: ReminderRun): String = try {
    val seconds = Duration.between(OffsetDateTime.parse(run.startedAt), OffsetDateTime.parse(run.finishedAt)).seconds
    "$seconds с"
} catch (error: DateTimeParseException) {
    "—"
}

private fun countWithRate(part: Long, total: Long): String = "$part · ${rate(part, total)}"

private fun rate(part: Long, total: Long): String {
    if (total <= 0) {
        return "—"
    }
    return String.format(Locale.US, "%.0f%%", part * 100.0 / total)
}

internal fun formatWait(seconds: Long?): String {
    if (seconds == null) {
        return "—"
    }
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 -> "$hours ч $minutes мин"
        minutes > 0 -> "$minutes мин"
        else -> "$seconds с"
    }
}
