package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.ai.AdminModelOverview
import com.eliteteam.speakingcoach.ai.AdminHistoryDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Product-facing daily overview. Missing source data is displayed as missing, never as zero. */
internal fun adminOverviewHtml(
    range: LlmRange,
    models: AdminModelOverview?,
    usage: AdminUsageOverview?,
    historicalRates: OpenRouterHistoricalRates? = null,
): String {
    val byModel = models?.days?.filter { models.modelSince.isNotBlank() && it.day >= models.modelSince }
        ?.associateBy { it.day }.orEmpty()
    val byUsage = usage?.days?.filter { usage.firstObservedDay != null && it.day >= usage.firstObservedDay }
        ?.associateBy { it.day }.orEmpty()
    val days = generateSequence(range.from) { previous -> previous.plusDays(1).takeIf { it <= range.to } }
        .map(LocalDate::toString).toList()
    val observedModels = days.mapNotNull(byModel::get)
    // The first recorded date may begin after midnight, so ratios require later days.
    val fullJournal = usage?.firstObservedDay?.let { it < range.from.toString() } == true
    val fullModels = models?.modelSince?.let { it.isNotBlank() && it < range.from.toString() } == true
    val fullVoiceCost = fullJournal && fullModels &&
        models.voiceLlmSince.let { it.isNotBlank() && it < range.from.toString() }
    val llm = observedModels.sumOf { it.llmCalls }
    val stt = observedModels.sumOf { it.sttCalls }
    val tts = observedModels.sumOf { it.ttsCalls }
    val telegram = usage?.days?.sumOf { it.telegramApiCalls } ?: 0L
    val active = usage?.activeChatsInPeriod ?: 0L
    val exchanges = usage?.days?.sumOf { it.exchanges } ?: 0L
    val voiceLlmCalls = observedModels.sumOf { it.voiceLlmCalls }
    val pricedLlmCalls = observedModels.sumOf { it.voiceLlmPricedCalls }
    val voiceLlmCost = observedModels.sumOf { it.voiceLlmCostMicro }
    val callsPerUser = if (fullJournal && fullModels && active > 0)
        decimal((llm + stt + tts + telegram).toDouble() / active, 1) else "—"
    val llmCostPerExchange = if (fullVoiceCost && exchanges > 0 && pricedLlmCalls > 0)
        dollars(voiceLlmCost.toDouble() / exchanges) else "—"
    val modelSince = models?.modelSince?.ifBlank { "нет данных" } ?: "нет данных"
    val journalSince = usage?.firstObservedDay ?: "нет данных"
    val costCoverage = if (voiceLlmCalls > 0) "$pricedLlmCalls из $voiceLlmCalls вызовов с известной ценой"
        else "нет вызовов с сохранённой ценой"
    val periodStatus = if (range.to == LocalDate.now(ZoneId.of("Europe/Moscow"))) "Текущий день ещё не завершён." else "Завершённые дни."
    val rows = days.joinToString("") { day ->
        val model = byModel[day]
        val product = byUsage[day]
        """<tr><td>$day</td><td>${product?.activeChats ?: "—"}</td><td>${product?.voices ?: "—"}</td>
          <td>${product?.exchanges ?: "—"}</td><td>${model?.llmCalls ?: "—"}</td>
          <td>${model?.sttCalls ?: "—"}</td><td>${model?.ttsCalls ?: "—"}</td>
          <td>${product?.telegramApiCalls ?: "—"}</td><td>${product?.voiceErrors ?: "—"}</td></tr>"""
    }
    return """
        <!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <meta name="robots" content="noindex"><title>Speaky · показатели</title>
        ${pageStyle()}${overviewStyle()}</head><body>
        <header class="overview-head"><div><span class="eyebrow">Speaky / аналитика</span><h1>Главные показатели</h1>
        <p class="meta">${range.from} — ${range.to} · Europe/Moscow. $periodStatus</p></div>
        <form method="get" action="$METRICS_PATH" class="period"><label>От <input type="date" name="from" value="${range.from}" required></label>
        <label>До <input type="date" name="to" value="${range.to}" required></label><button type="submit">Показать</button></form></header>
        ${adminTabs(METRICS_PATH)}
        ${historicalRatesHtml(historicalRates)}
        ${historicalOverviewHtml(days, models)}
        ${if (days.any { byModel.containsKey(it) || byUsage.containsKey(it) }) "<h2>Детальный учёт · с ${escapeHtml(listOfNotNull(models?.modelSince?.takeIf { it.isNotBlank() }, usage?.firstObservedDay).minOrNull() ?: "6 октября")}</h2>" else ""}
        ${if (days.any { byModel.containsKey(it) || byUsage.containsKey(it) }) """
        <section class="overview-cards" aria-label="Итоги">
          ${overviewCard("Активных пользователей", if (fullJournal) usage.activeChatsInPeriod.toString() else "—", "Уникальные Telegram ID с голосом, текстом или нажатием кнопки за период")}
          ${overviewCard("Голосовых", if (fullJournal) usage.days.sumOf { it.voices }.toString() else "—", "Уникальные входящие голосовые сообщения")}
          ${overviewCard("Успешных обменов", if (fullJournal) usage.days.sumOf { it.exchanges }.toString() else "—", "Голосовое и доставленный голосовой ответ с распознанной репликой")}
          ${overviewCard("Вызовов / активного пользователя", callsPerUser, "Учтённые LLM + STT + TTS + Telegram API. Неполный показатель")}
          ${overviewCard("LLM-cost / обмен", llmCostPerExchange, "Известная стоимость LLM при обработке голосовых / успешные обмены. $costCoverage")}
          ${overviewCard("Ошибок голосового пути", if (fullJournal) usage.days.sumOf { it.voiceErrors }.toString() else "—", "Неуспешные конечные исходы голосовых")}
        </section>
        <p class="coverage">История действий: с ${escapeHtml(journalSince)} (PostgreSQL хранит 30 дней).
        История моделей: с ${escapeHtml(modelSince)} (Redis хранит 30 дней); дни без вызовов после этой даты показаны нулями.
        Стоимость LLM для голосовых собирается с ${escapeHtml(models?.voiceLlmSince?.ifBlank { "первого измеренного обращения" } ?: "первого измеренного обращения")}; первый день каждого источника может быть неполным и не входит в расчёт итоговых карточек. Неизвестная цена не считается нулём.</p>
        <div class="chart-grid">
          ${overviewChart("Активные пользователи", days, listOf(ChartLine("Пользователи", "#2563eb", days.map { byUsage[it]?.activeChats })), usage != null)}
          ${overviewChart("Голосовые и ответы", days, listOf(
              ChartLine("Отправлено", "#2563eb", days.map { byUsage[it]?.voices }),
              ChartLine("Успешно", "#16a34a", days.map { byUsage[it]?.exchanges })), usage != null)}
          ${overviewChart("Вызовы компонентов", days, listOf(
              ChartLine("LLM", "#2563eb", days.map { byModel[it]?.llmCalls }),
              ChartLine("STT", "#f59e0b", days.map { byModel[it]?.sttCalls }),
              ChartLine("TTS", "#a855f7", days.map { byModel[it]?.ttsCalls }),
              ChartLine("Telegram API", "#16a34a", days.map { byUsage[it]?.telegramApiCalls })),
              observedModels.isNotEmpty() || usage != null)}
          ${overviewChart("Известная стоимость LLM · USD", days, listOf(
              ChartLine("Все вызовы", "#2563eb", days.map { byModel[it]?.llmCostMicro }),
              ChartLine("Голосовые", "#a855f7", days.map { day -> byModel[day]
                  ?.takeIf { models?.voiceLlmSince?.let { since -> since.isNotBlank() && day >= since } == true }
                  ?.voiceLlmCostMicro })),
              observedModels.isNotEmpty(), valueDivisor = 1_000_000)}
          ${overviewChart("Ошибки голосового пути", days, listOf(
              ChartLine("Ошибки", "#dc2626", days.map { byUsage[it]?.voiceErrors })), usage != null)}
        </div>
        <h2>Данные по дням</h2><div class="table-wrap"><table><thead><tr><th>День</th><th>Активные</th><th>ГС</th>
        <th>Обмены</th><th>LLM</th><th>STT</th><th>TTS</th><th>Telegram API</th><th>Ошибки</th></tr></thead>
        <tbody>$rows</tbody></table></div>
        <p class="coverage">Вызовы LLM, STT и TTS — успешно учтённые ответы провайдеров; ошибочные попытки до нового счётчика не восстановлены.
        Telegram API — записанные попытки отправки и редактирования в контексте входящего сообщения; фоновые рассылки исключены.
        Неучтённые методы API и отдельные навыки в сумму не входят. «—» означает отсутствие измерений.</p>
        """ else ""}
        </body></html>
    """.trimIndent()
}

private fun historicalRatesHtml(rates: OpenRouterHistoricalRates?): String {
    val imported = rates ?: return ""
    val zone = ZoneId.of("Europe/Moscow")
    val archiveFrom = Instant.parse(imported.fromUtc).atZone(zone).toLocalDate()
    val archiveTo = Instant.parse(imported.toUtc).minusNanos(1).atZone(zone).toLocalDate()
    val archiveDays = imported.days(LlmRange(archiveFrom, archiveTo))
    val days = archiveDays.map { it.day.toString() }
    val byDay = archiveDays.associateBy { it.day.toString() }
    val minutes = imported.minutes.sortedBy { it.minuteUtc }
    val scope = if (imported.scope == "test") "ТЕСТОВЫЙ аккаунт" else "рабочий аккаунт"
    val timedCalls = minutes.sumOf { it.timedCalls }
    val allCalls = minutes.sumOf { it.calls }
    val rows = days.mapNotNull { day -> byDay[day]?.let { """<tr><td>$day</td><td>${it.coveredMinutes} / 1440</td>
        <td>${it.peakTpm}</td><td>${it.peakTpsMilli?.let { value -> decimal(value / 1000.0, 2) } ?: "—"}</td></tr>""" } }
        .joinToString("")
    return """
        <section aria-label="Исторические TPM и TPS OpenRouter">
        <h2>TPM и TPS · ${escapeHtml(scope)} OpenRouter</h2>
        <p class="coverage">Архив за $archiveFrom — $archiveTo по Москве; фильтр дат выше на него не влияет.
        Источник: OpenRouter Analytics API · ${escapeHtml(imported.fromUtc)} — ${escapeHtml(imported.toUtc)} UTC.
        Модели LLM: ${escapeHtml(imported.models.joinToString(", "))}.
        Графики показывают значения за каждую минуту с вызовами; промежутки без вызовов показаны разрывами. Время на оси — московское.
        TPM — входные и выходные токены за минуту. TPS — выходные токены / суммарное время генерации вызовов этой минуты.
        Это вызовы выбранного аккаунта OpenRouter, включая его тесты; вызовы через других провайдеров сюда не входят.
        Время генерации известно для $timedCalls из $allCalls вызовов; TPS учитывает только их.
        Дневные максимумы и неполные дни видны в таблице.</p>
        <div class="chart-grid">
          ${openRouterMinuteChart("TPM по минутам · OpenRouter", minutes, { it.tpm }, "#2563eb")}
          ${openRouterMinuteChart("TPS по минутам · OpenRouter", minutes, { it.tpsMilli }, "#16a34a", 1000)}
        </div>
        <h2>TPM и TPS · дневные максимумы и покрытие</h2><div class="table-wrap"><table><thead><tr>
        <th>День по Москве</th><th>Минут в выгрузке</th><th>Пиковый TPM</th><th>Пиковый TPS</th>
        </tr></thead><tbody>$rows</tbody></table></div>
    """.trimIndent()
}

private fun openRouterMinuteChart(
    title: String,
    minutes: List<OpenRouterRateMinute>,
    value: (OpenRouterRateMinute) -> Long?,
    color: String,
    valueDivisor: Long = 1,
): String {
    val points = minutes.map { Instant.parse(it.minuteUtc) to value(it) }.sortedBy { it.first }
    if (points.none { it.second != null })
        return "<section class=\"chart-card\"><h2>${escapeHtml(title)}</h2><p class=\"meta\">Нет данных</p></section>"

    val left = 62.0
    val right = 704.0
    val top = 16.0
    val bottom = 160.0
    val firstMillis = points.first().first.toEpochMilli()
    val lastMillis = points.last().first.toEpochMilli()
    val duration = (lastMillis - firstMillis).toDouble()
    val x = { instant: Instant ->
        if (duration == 0.0) (left + right) / 2
        else left + (instant.toEpochMilli() - firstMillis) / duration * (right - left)
    }
    val max = (points.mapNotNull { it.second }.maxOrNull() ?: 0L).toDouble() / valueDivisor
    val tickStep = chartTickStep(max, wholeNumbers = valueDivisor == 1L)
    val tickCount = ceil(max / tickStep - 1e-9).toInt().coerceAtLeast(1)
    val axisMax = tickCount * tickStep
    val y = { amount: Long -> bottom - amount.toDouble() / valueDivisor / axisMax * (bottom - top) }
    val yAxis = (0..tickCount).joinToString("") { tick ->
        val amount = tick * tickStep
        val position = bottom - amount / axisMax * (bottom - top)
        """<line x1="$left" y1="$position" x2="$right" y2="$position" stroke="${if (tick == 0) "#9ca3af" else "#e5e7eb"}"/>
        <text class="axis-y" x="54" y="${position + 4}" text-anchor="end">${chartAxisLabel(amount, tickStep, axisMax)}</text>"""
    }
    val timeFormat = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.of("Europe/Moscow"))
    val xAxis = (if (duration == 0.0) listOf(0) else (0..5).toList()).joinToString("") { index ->
        val instant = Instant.ofEpochMilli(firstMillis + ((lastMillis - firstMillis) * index / 5))
        val position = x(instant)
        val anchor = when {
            duration == 0.0 -> "middle"
            index == 0 -> "start"
            index == 5 -> "end"
            else -> "middle"
        }
        """<line x1="$position" y1="$bottom" x2="$position" y2="${bottom + 5}" stroke="#9ca3af"/>
        <text class="axis-x" x="$position" y="182" text-anchor="$anchor" style="font-size:11px">${timeFormat.format(instant)}</text>"""
    }
    val segments = mutableListOf<List<Pair<Instant, Long>>>()
    var current = mutableListOf<Pair<Instant, Long>>()
    for ((time, amount) in points) {
        if (amount == null || (current.isNotEmpty() && time.epochSecond - current.last().first.epochSecond > 60)) {
            if (current.isNotEmpty()) segments += current
            current = mutableListOf()
        }
        if (amount != null) current += time to amount
    }
    if (current.isNotEmpty()) segments += current
    val paths = segments.joinToString("") { segment ->
        val coordinates = segment.joinToString(" ") { (time, amount) -> "${x(time)},${y(amount)}" }
        "<polyline points=\"$coordinates\" fill=\"none\" stroke=\"$color\" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>"
    }
    val dots = points.mapNotNull { (time, amount) -> amount?.let {
        "<circle cx=\"${x(time)}\" cy=\"${y(it)}\" r=\"2.5\" fill=\"$color\"><title>${timeFormat.format(time)} · ${decimal(it.toDouble() / valueDivisor, 3)}</title></circle>"
    } }.joinToString("")
    return """<section class="chart-card"><h2>${escapeHtml(title)}</h2><div class="chart-scroll">
      <svg viewBox="0 0 720 194" role="img" aria-label="${escapeHtml(title)}">
      <g class="chart-axis">$yAxis<line x1="$left" y1="$top" x2="$left" y2="$bottom" stroke="#9ca3af"/>$xAxis</g>
      $paths$dots</svg></div></section>"""
}

private fun historicalOverviewHtml(days: List<String>, models: AdminModelOverview?): String {
    val overview = models ?: return "<p class=\"coverage\">Данные Redis сейчас недоступны.</p>"
    val byDay = overview.historyDays.associateBy { it.day }
    val observed = overview.historyDays.filter(AdminHistoryDay::tracked)
    if (observed.isEmpty()) return "<p class=\"coverage\">За выбранный период сохранённых данных Redis нет.</p>"
    val through = observed.last()
    val rows = days.joinToString("") { day ->
        val row = byDay[day]?.takeIf(AdminHistoryDay::tracked)
        """<tr><td>$day</td><td>${row?.totalChatsToDate ?: "—"}</td><td>${row?.chatsWithTurnToDate ?: "—"}</td><td>${row?.newChats ?: "—"}</td>
        <td>${row?.activeChatsWithTurn ?: "—"}</td><td>${row?.activeChats7d ?: "—"}</td>
        <td>${row?.returningActiveChats ?: "—"}</td><td>${row?.firstVoiceChats ?: "—"}</td>
        <td>${row?.completedTurns ?: "—"}</td><td>${row?.promptTokens ?: "—"}</td>
        <td>${row?.completionTokens ?: "—"}</td><td>${row?.sttMs?.let { decimal(it / 1000.0, 1) } ?: "—"}</td>
        <td>${row?.ttsChars ?: "—"}</td></tr>"""
    }
    val sources = overview.sourceBreakdown.sortedByDescending { it.newChats }
        .joinToString("") { row -> "<tr><td>${escapeHtml(row.source)}</td><td>${row.newChats}</td><td>${row.firstVoiceChats}</td></tr>" }
    val active = overview.historyActiveChatsInPeriod
    val sessions = overview.historyActiveSessionsInPeriod
    val turns = observed.sumOf { it.completedTurns }
    val tokens = observed.sumOf { it.promptTokens + it.completionTokens }
    return """
        <section aria-label="История Redis">
        <h2>Аудитория и использование AI</h2>
        <p class="coverage">С ${escapeHtml(overview.historySince)} · ежедневные записи Redis за весь выбранный период.
        Пользователь здесь — уникальный Telegram ID чата: в личном диалоге он соответствует одному человеку; групповой чат считается одним ID.
        DAU — пользователи, для которых завершился хотя бы один голосовой ход AI за день. Это не число всех входящих сообщений.
        Ход AI фиксируется до доставки ответа. Токены, STT и TTS охватывают все клиентские платформы.
        ${if (overview.chatGrowthUnattributed > 0) "${overview.chatGrowthUnattributed} Telegram ID без даты первого обращения не включены в график роста." else ""}</p>
        <div class="overview-cards">
          ${overviewCard("Завершили голосовой ход AI · всего", through.chatsWithTurnToDate?.toString() ?: "—", "Уникальные пользователи за всю сохранённую историю; доставка ответа не подтверждена")}
          ${overviewCard("Всего пользователей на конец периода", through.totalChatsToDate.toString(), "Накопленно с первого сохранённого обращения")}
          ${overviewCard("Активных пользователей за период", active.toString(), "Уникальные Telegram ID с завершённым голосовым ходом AI")}
          ${overviewCard("DAU в последний день", through.activeChatsWithTurn.toString(), "Пользователи с завершённым голосовым ходом AI")}
          ${overviewCard("Отправили первое голосовое · всего", through.firstVoiceChatsToDate.toString(), "Накопленно на конец периода")}
          ${overviewCard("Завершённых ходов AI", turns.toString(), "За выбранный период, все платформы")}
          ${overviewCard("Ходов / активную AI-сессию", if (sessions > 0) decimal(turns.toDouble() / sessions, 1) else "—", "Все платформы, уникальные сессии с завершённым ходом")}
          ${overviewCard("Токенов LLM", tokens.toString(), "Входные + выходные, все платформы")}
          ${overviewCard("Токенов / ход", if (turns > 0) decimal(tokens.toDouble() / turns, 1) else "—", "Входные + выходные токены / завершённые ходы")}
          ${overviewCard("Обработано речи", decimal(observed.sumOf { it.sttMs } / 60000.0, 1) + " мин", "Длительность аудио, обработанного STT; не время ожидания")}
          ${overviewCard("Символов TTS", observed.sumOf { it.ttsChars }.toString(), "Текст, переданный озвучке")}
        </div>
        <div class="chart-grid">
          ${overviewChart("Рост пользователей", days, listOf(
              ChartLine("Всего", "#2563eb", days.map { byDay[it]?.takeIf(AdminHistoryDay::tracked)?.totalChatsToDate }),
              ChartLine("Завершили голосовой ход AI", "#a855f7", days.map { byDay[it]?.takeIf(AdminHistoryDay::tracked)?.chatsWithTurnToDate }),
              ChartLine("С первым голосовым", "#16a34a", days.map { byDay[it]?.takeIf(AdminHistoryDay::tracked)?.firstVoiceChatsToDate })), true)}
          ${overviewChart("DAU и активные за 7 дней", days, listOf(
              ChartLine("DAU", "#2563eb", days.map { byDay[it]?.takeIf(AdminHistoryDay::tracked)?.activeChatsWithTurn }),
              ChartLine("7 дней", "#16a34a", days.map { byDay[it]?.takeIf(AdminHistoryDay::tracked)?.activeChats7d })), true)}
          ${overviewChart("Новые и вернувшиеся", days, listOf(
              ChartLine("Новые пользователи", "#2563eb", days.map { byDay[it]?.takeIf(AdminHistoryDay::tracked)?.newChats }),
              ChartLine("Активные повторно", "#16a34a", days.map { byDay[it]?.takeIf(AdminHistoryDay::tracked)?.returningActiveChats })), true)}
          ${overviewChart("Первые шаги", days, listOf(
              ChartLine("Первый /start", "#2563eb", days.map { byDay[it]?.takeIf(AdminHistoryDay::tracked)?.firstStarts }),
              ChartLine("Первый голос", "#16a34a", days.map { byDay[it]?.takeIf(AdminHistoryDay::tracked)?.firstVoiceChats }),
              ChartLine("Третий обмен", "#a855f7", days.map { byDay[it]?.takeIf(AdminHistoryDay::tracked)?.engagedChats })), true)}
          ${overviewChart("Завершённые ходы AI", days, listOf(
              ChartLine("Ходы", "#2563eb", days.map { byDay[it]?.takeIf(AdminHistoryDay::tracked)?.completedTurns })), true)}
          ${overviewChart("Токены LLM", days, listOf(
              ChartLine("Вход", "#2563eb", days.map { byDay[it]?.takeIf(AdminHistoryDay::tracked)?.promptTokens }),
              ChartLine("Выход", "#16a34a", days.map { byDay[it]?.takeIf(AdminHistoryDay::tracked)?.completionTokens })), true)}
          ${overviewChart("Аудио STT · минуты", days, listOf(
              ChartLine("Минуты", "#2563eb", days.map { byDay[it]?.takeIf(AdminHistoryDay::tracked)?.sttMs })), true, valueDivisor = 60_000)}
          ${overviewChart("Текст TTS · символы", days, listOf(
              ChartLine("Символы", "#a855f7", days.map { byDay[it]?.takeIf(AdminHistoryDay::tracked)?.ttsChars })), true)}
        </div>
        <p class="coverage">«7 дней» — уникальные активные пользователи за последние семь календарных суток; первые шесть дней истории не отображаются.
        Возврат — пользователь, активный сегодня и хотя бы в один более ранний сохранённый день. Новые пользователи и первые голосовые считаются по первой дате Telegram ID.
        «Завершили голосовой ход AI» — приближение к числу пользователей с целевым действием за всю историю. Оно не подтверждает доставку ответа; доставка учтена только в детальном блоке с 6 октября.</p>
        <h2>Источники новых пользователей за период</h2>
        <div class="table-wrap"><table><thead><tr><th>Источник</th><th>Новые пользователи</th><th>Первый голос</th></tr></thead>
        <tbody>${sources.ifBlank { "<tr><td colspan=\"3\">Нет данных</td></tr>" }}</tbody></table></div>
        <h2>Данные по дням · сохранённая история</h2>
        <div class="table-wrap"><table><thead><tr><th>День</th><th>Всего пользователей</th><th>Завершили голосовой ход AI · всего</th><th>Новые</th>
        <th>DAU</th><th>7 дней</th><th>Вернулись</th><th>Первый голос</th><th>Ходы AI</th>
        <th>Токены вход</th><th>Токены выход</th><th>STT, с</th><th>TTS, символы</th></tr></thead><tbody>$rows</tbody></table></div>
        </section>
    """.trimIndent()
}

private fun overviewCard(title: String, value: String, note: String): String =
    """<article class="overview-card"><dt>${escapeHtml(title)}</dt><dd>${escapeHtml(value)}</dd>
    <p>${escapeHtml(note)}</p></article>"""

private data class ChartLine(val label: String, val color: String, val values: List<Long?>)
private val chartDateFormat = DateTimeFormatter.ofPattern("dd.MM")

private fun overviewChart(
    title: String, days: List<String>, lines: List<ChartLine>, available: Boolean, valueDivisor: Long = 1,
): String {
    if (!available || lines.all { line -> line.values.all { it == null } })
        return "<section class=\"chart-card\"><h2>${escapeHtml(title)}</h2><p class=\"meta\">Нет данных</p></section>"
    val left = 62.0
    val right = 704.0
    val top = 16.0
    val bottom = 160.0
    val max = (lines.flatMap { it.values }.filterNotNull().maxOrNull() ?: 0L).toDouble() / valueDivisor
    val tickStep = chartTickStep(max, wholeNumbers = valueDivisor == 1L)
    val tickCount = ceil(max / tickStep - 1e-9).toInt().coerceAtLeast(1)
    val axisMax = tickCount * tickStep
    val x = { index: Int -> if (days.size == 1) (left + right) / 2 else left + index * (right - left) / (days.size - 1) }
    val y = { value: Double -> bottom - value / axisMax * (bottom - top) }
    val yAxis = (0..tickCount).joinToString("") { tick ->
        val value = tick * tickStep
        val position = y(value)
        """<line x1="$left" y1="$position" x2="$right" y2="$position" stroke="${if (tick == 0) "#9ca3af" else "#e5e7eb"}"/>
        <text class="axis-y" x="54" y="${position + 4}" text-anchor="end">${chartAxisLabel(value, tickStep, axisMax)}</text>"""
    }
    val xAxis = chartDateIndexes(days.size).joinToString("") { index ->
        val position = x(index)
        val anchor = when { days.size == 1 -> "middle"; index == 0 -> "start"; index == days.lastIndex -> "end"; else -> "middle" }
        """<line x1="$position" y1="$bottom" x2="$position" y2="${bottom + 5}" stroke="#9ca3af"/>
        <text class="axis-x" x="$position" y="182" text-anchor="$anchor">${LocalDate.parse(days[index]).format(chartDateFormat)}</text>"""
    }
    val paths = lines.joinToString("") { line ->
        val segments = mutableListOf<MutableList<Pair<Int, Long>>>()
        line.values.forEachIndexed { index, value ->
            if (value == null) return@forEachIndexed
            if (index == 0 || line.values[index - 1] == null) segments.add(mutableListOf())
            segments.last().add(index to value)
        }
        segments.joinToString("") { segment ->
            val points = segment.joinToString(" ") { (index, value) -> "${x(index)},${y(value.toDouble() / valueDivisor)}" }
            "<polyline points=\"$points\" fill=\"none\" stroke=\"${line.color}\" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>"
        } + line.values.mapIndexedNotNull { index, value ->
            value?.let { "<circle cx=\"${x(index)}\" cy=\"${y(it.toDouble() / valueDivisor)}\" r=\"3\" fill=\"${line.color}\"><title>${days[index]} · ${escapeHtml(line.label)}: ${decimal(it.toDouble() / valueDivisor, 3)}</title></circle>" }
        }.joinToString("")
    }
    val legend = lines.joinToString("") { line -> "<span><i style=\"background:${line.color}\"></i>${escapeHtml(line.label)}</span>" }
    return """<section class="chart-card"><h2>${escapeHtml(title)}</h2><div class="chart-legend">$legend</div>
      <div class="chart-scroll"><svg viewBox="0 0 720 194" role="img" aria-label="${escapeHtml(title)}">
      <g class="chart-axis">$yAxis<line x1="$left" y1="$top" x2="$left" y2="$bottom" stroke="#9ca3af"/>$xAxis</g>
      $paths</svg></div></section>"""
}

private fun chartDateIndexes(size: Int): List<Int> {
    if (size <= 7) return (0 until size).toList()
    return (0..5).map { index -> (index * (size - 1).toDouble() / 5).toInt() }.distinct()
}

private fun chartTickStep(max: Double, wholeNumbers: Boolean): Double {
    if (max <= 0) return 1.0
    val raw = max / 4
    val magnitude = 10.0.pow(floor(log10(raw)))
    val nice = (if (wholeNumbers) listOf(1.0, 2.0, 5.0, 10.0) else listOf(1.0, 2.0, 2.5, 5.0, 10.0))
        .first { it >= raw / magnitude - 1e-9 }
    return (nice * magnitude).let { if (wholeNumbers) it.coerceAtLeast(1.0) else it }
}

private fun chartAxisLabel(value: Double, step: Double, axisMax: Double): String {
    if (value == 0.0) return "0"
    val divisor = when {
        axisMax >= 1_000_000 -> 1_000_000.0
        axisMax >= 1_000 -> 1_000.0
        else -> 1.0
    }
    val suffix = when (divisor) { 1_000_000.0 -> "M"; 1_000.0 -> "k"; else -> "" }
    val scaledStep = step / divisor
    val digits = java.math.BigDecimal.valueOf(scaledStep).setScale(9, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros().scale().coerceIn(0, 9)
    val formatted = decimal(value / divisor, digits)
    return (if (digits > 0) formatted.trimEnd('0').trimEnd('.') else formatted) + suffix
}

private fun decimal(value: Double, digits: Int): String = String.format(Locale.ROOT, "%.${digits}f", value)
private fun dollars(micro: Double): String = "$" + decimal(micro / 1_000_000, 4)

private fun overviewStyle(): String = """
<style>
  body { max-width: 1240px; padding: 0 20px 40px; }
  .overview-head { display:flex; justify-content:space-between; align-items:end; gap:24px; margin:34px 0 18px; }
  .eyebrow { color:#2563eb; font-size:12px; font-weight:750; letter-spacing:.1em; text-transform:uppercase; }
  .overview-head h1 { font-size:30px; letter-spacing:-.04em; margin:3px 0; }
  .period { display:flex; flex-direction:row; align-items:end; justify-content:flex-end; gap:8px; flex-wrap:wrap; max-width:none; }
  .period label { color:#57534e; font-size:12px; display:grid; gap:4px; }
  .period input, .period button { font:inherit; border:1px solid #d6d3d1; border-radius:8px; padding:8px 10px; background:white; }
  .period button { background:#1d4ed8; border-color:#1d4ed8; color:white; cursor:pointer; }
  .overview-cards { display:grid; grid-template-columns:repeat(auto-fit,minmax(205px,1fr)); gap:12px; margin:22px 0 14px; }
  .overview-card { background:white; border:1px solid #e7e5e4; border-radius:12px; padding:17px; min-height:132px; }
  .overview-card dt { color:#57534e; font-size:13px; font-weight:600; }
  .overview-card dd { margin:10px 0 8px; font-size:32px; line-height:1; font-weight:700; letter-spacing:-.04em; }
  .overview-card p, .coverage { color:#78716c; font-size:12px; line-height:1.5; margin:0; }
  .coverage { margin:14px 0 20px; }
  .chart-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:13px; }
  .chart-card { background:white; border:1px solid #e7e5e4; border-radius:12px; padding:14px 16px; min-width:0; }
  .chart-card h2 { margin:0 0 8px; font-size:15px; }
  .chart-scroll { overflow-x:auto; }
  .chart-card svg { width:100%; min-width:600px; height:auto; display:block; }
  .chart-axis text { fill:#6b7280; font-size:14px; font-family:system-ui,sans-serif; }
  .chart-legend { display:flex; gap:12px; flex-wrap:wrap; color:#57534e; font-size:11px; }
  .chart-legend span { display:inline-flex; align-items:center; gap:4px; }
  .chart-legend i { display:inline-block; width:8px; height:8px; border-radius:50%; }
  .table-wrap { overflow-x:auto; background:white; border:1px solid #e7e5e4; border-radius:12px; }
  .table-wrap table { margin:0; width:100%; white-space:nowrap; }
  .tabs { overflow-x:auto; white-space:nowrap; }
  .tabs a { flex:0 0 auto; }
  @media(max-width:800px) { .overview-head { display:block; } .period { margin-top:15px; justify-content:flex-start; } .chart-grid { grid-template-columns:1fr; } }
  @media(max-width:600px) { .overview-cards { grid-template-columns:1fr; } }
</style>
"""
