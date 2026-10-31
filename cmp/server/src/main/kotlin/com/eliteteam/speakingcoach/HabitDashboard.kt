package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.ai.AdminRetentionDay
import com.eliteteam.speakingcoach.ai.HabitSnapshot
import com.eliteteam.speakingcoach.analytics.HabitDuration
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

internal fun habitDashboardHtml(habits: HabitSnapshot?, duration: HabitDuration? = null): String {
    val durationHtml = duration?.let(::habitDurationHtml)
        ?: "<h3>Речь пользователя на разговор</h3><p>Источник длительности недоступен.</p>"
    if (habits == null || !habits.healthy) {
        return "<h2>Регулярность</h2><p class=\"coverage\">Источник активности недоступен. Значения не заменены нулями.</p>$durationHtml"
    }
    val last = habits.days.lastOrNull()
    val windowStart = last?.day?.let { LocalDate.parse(it).minusDays(6) }
    val groups = last?.distribution.orEmpty().joinToString("") {
        val label = if (it.bucket == "4_7") "4–7" else escapeHtml(it.bucket)
        "<tr><td>$label</td><td>${it.users ?: "—"}</td><td>${habitPercent(it.users, last?.wau)}</td></tr>"
    }
    val days = habits.days.map { it.day }
    return """
        <section aria-label="Регулярность"><h2>Регулярность</h2>
        <p class="coverage">Активность — завершённый голосовой ход AI, включая просьбу повторить речь, до доставки Telegram.
        Один Telegram chat ID считается одним пользователем. Неделя: $windowStart — ${escapeHtml(habits.asOf)} · Europe/Moscow.
        ${if (last?.provisional == true) "Предварительно: текущий день не завершён." else "Завершённые дни."}
        Обновлено: ${Instant.ofEpochSecond(habits.updatedAt.toLong())}.</p>
        <div class="overview-cards">
        ${overviewCard("DAU", last?.dau?.toString() ?: "—", "За последний день выбранного периода")}
        ${overviewCard("WAU", last?.wau?.toString() ?: "—", "Уникальные за семь дней, не сумма DAU")}
        ${overviewCard("DAU / WAU", habitPercent(last?.dau, last?.wau), "Доля недельной аудитории, активная в последний день")}
        </div>
        <div class="chart-grid">
        ${overviewChart("DAU и WAU", days, listOf(
            ChartLine("DAU", "#2563eb", habits.days.map { it.dau }),
            ChartLine("WAU", "#16a34a", habits.days.map { it.wau })), true)}
        ${overviewChart("DAU / WAU · %", days, listOf(
            ChartLine("%", "#2563eb", habits.days.map { it.ratio?.let { ratio -> (ratio * 1000).toLong() } })), true, valueDivisor = 10)}
        </div>
        <h3>Активных дней за неделю</h3><table><thead><tr><th>Дней</th><th>Пользователей</th><th>Доля WAU</th></tr></thead>
        <tbody>$groups</tbody></table>
        ${habitCohortsHtml(habits)}
        $durationHtml
        <p class="coverage">История активности с ${escapeHtml(habits.historySince.ifBlank { "неизвестной даты" })}.
        Первый день сбора неполный; недельные окна с ним скрыты. Дни без записей после начала сбора считаются пустыми;
        исторические пропуски сбора и старые нагрузочные обращения не всегда различимы.</p></section>
    """.trimIndent()
}

internal fun habitCohortsHtml(habits: HabitSnapshot): String {
    if (!habits.healthy) return "<p>Когорты регулярности недоступны.</p>"
    val rows = habits.cohorts.joinToString("") { cohort ->
        val rates = cohort.rates.associateBy { it.day }
        "<tr><td>${escapeHtml(cohort.week)}${if (cohort.partialHistory) " · неполная история" else ""}</td>" +
            "<td>${cohort.size}</td>" + listOf(1, 3, 7, 30).joinToString("") {
                "<td>${habitRate(rates[it], cohort.size)}</td>"
            } + "</tr>"
    }
    val totals = habits.periodRetention.associateBy { it.day }
    return """<h3>Retention от первого завершённого хода AI</h3>
        <p class="coverage">Недели понедельник–воскресенье. Возврат ровно на Dn.
        Наблюдение до ${escapeHtml(habits.observedThrough)} включительно; незавершённые целевые дни исключены.
        Первый наблюдаемый ход может отличаться от первого за жизнь пользователя.</p>
        <div class="table-wrap"><table><thead><tr><th>Неделя первого хода</th><th>Размер</th><th>D1</th><th>D3</th><th>D7</th><th>D30</th></tr></thead>
        <tbody>$rows</tbody></table></div>
        <p class="coverage">По датам входа выбранного периода:
        ${listOf(1, 3, 7, 30).joinToString(" · ") { "D$it: ${habitRate(totals[it], null)}" }}.</p>"""
}

private fun habitRate(rate: AdminRetentionDay?, size: Long?): String =
    if (rate == null || rate.eligible == 0L) "— (нет зрелой выборки)"
    else "${habitPercent(rate.returned, rate.eligible)} · ${rate.returned}/${rate.eligible}" +
        if (size != null && rate.eligible < size) " · частично зрелая" else ""

private fun habitPercent(part: Long?, total: Long?): String =
    if (part == null || total == null || total == 0L) "—"
    else String.format(Locale.US, "%.1f%%", part * 100.0 / total)

private fun habitDurationHtml(duration: HabitDuration): String {
    val value = if (duration.calls > 0) String.format(Locale.US, "%.2f мин", duration.speechSeconds / duration.calls / 60) else "—"
    return """<h3>Речь пользователя на разговор</h3><p><strong>$value</strong> · звонков: ${duration.calls}.</p>
        <p class="coverage">Средняя сумма распознанной речи на закрытый практический звонок.
        Период — по дате начала; речь AI не входит. Старый метод считает запись с паузами:
        ${duration.legacyCalls} звонков; новый метод (VAD или fallback): ${duration.vadCalls};
        смешанных: ${duration.mixedCalls}.
        Ходов с возвратом к длительности записи: ${duration.fallbackTurns}.
        Исключено открытых, пустых или неполных: ${duration.excluded},
        из них неполных: ${duration.incomplete}. История с ${escapeHtml(duration.historySince ?: "неизвестной даты")}.
        Первый день может быть неполным. Потерь записи в текущем процессе: ${duration.writeFailures};
        необнаруженные потери могут занизить результат. ${if (duration.memoryOnly) "Хранилище в памяти: история теряется после рестарта." else "Компактная история хранится 365 дней."}</p>"""
}
