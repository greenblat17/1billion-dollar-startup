package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.ai.AdminModelOverview
import com.eliteteam.speakingcoach.ai.AdminHistoryDay
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Product-facing daily overview. Missing source data is displayed as missing, never as zero. */
internal fun adminOverviewHtml(
    range: LlmRange,
    models: AdminModelOverview?,
    usage: AdminUsageOverview?,
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
        ${historicalOverviewHtml(days, models)}
        ${if (days.any { byModel.containsKey(it) || byUsage.containsKey(it) }) "<h2>Детальный учёт · с ${escapeHtml(listOfNotNull(models?.modelSince?.takeIf { it.isNotBlank() }, usage?.firstObservedDay).minOrNull() ?: "6 октября")}</h2>" else ""}
        ${if (days.any { byModel.containsKey(it) || byUsage.containsKey(it) }) """
        <section class="overview-cards" aria-label="Итоги">
          ${overviewCard("Активных чатов", if (fullJournal) usage.activeChatsInPeriod.toString() else "—", "Уникальные чаты с голосом, текстом или нажатием кнопки за период")}
          ${overviewCard("Голосовых", if (fullJournal) usage.days.sumOf { it.voices }.toString() else "—", "Уникальные входящие голосовые сообщения")}
          ${overviewCard("Успешных обменов", if (fullJournal) usage.days.sumOf { it.exchanges }.toString() else "—", "Голосовое и доставленный голосовой ответ с распознанной репликой")}
          ${overviewCard("Вызовов / активный чат", callsPerUser, "Учтённые LLM + STT + TTS + Telegram API. Неполный показатель")}
          ${overviewCard("LLM-cost / обмен", llmCostPerExchange, "Известная стоимость LLM при обработке голосовых / успешные обмены. $costCoverage")}
          ${overviewCard("Ошибок голосового пути", if (fullJournal) usage.days.sumOf { it.voiceErrors }.toString() else "—", "Неуспешные конечные исходы голосовых")}
        </section>
        <p class="coverage">История действий: с ${escapeHtml(journalSince)} (PostgreSQL хранит 30 дней).
        История моделей: с ${escapeHtml(modelSince)} (Redis хранит 30 дней); дни без вызовов после этой даты показаны нулями.
        Стоимость LLM для голосовых собирается с ${escapeHtml(models?.voiceLlmSince?.ifBlank { "первого измеренного обращения" } ?: "первого измеренного обращения")}; первый день каждого источника может быть неполным и не входит в расчёт итоговых карточек. Неизвестная цена не считается нулём.</p>
        <div class="chart-grid">
          ${overviewChart("Активные чаты", days, listOf(ChartLine("Чаты", "#2563eb", days.map { byUsage[it]?.activeChats })), usage != null)}
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

private fun historicalOverviewHtml(days: List<String>, models: AdminModelOverview?): String {
    val overview = models ?: return "<p class=\"coverage\">Данные Redis сейчас недоступны.</p>"
    val byDay = overview.historyDays.associateBy { it.day }
    val observed = overview.historyDays.filter(AdminHistoryDay::tracked)
    if (observed.isEmpty()) return "<p class=\"coverage\">За выбранный период сохранённых данных Redis нет.</p>"
    val through = observed.last()
    val rows = days.joinToString("") { day ->
        val row = byDay[day]?.takeIf(AdminHistoryDay::tracked)
        """<tr><td>$day</td><td>${row?.totalChatsToDate ?: "—"}</td><td>${row?.newChats ?: "—"}</td>
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
        Активный чат — Telegram-чат, для которого завершился хотя бы один ход AI. Это не число всех входящих сообщений.
        Ход AI фиксируется до доставки ответа. Токены, STT и TTS охватывают все клиентские платформы.
        ${if (overview.chatGrowthUnattributed > 0) "${overview.chatGrowthUnattributed} чатов без даты первого обращения не включены в график роста." else ""}</p>
        <div class="overview-cards">
          ${overviewCard("Всего чатов на конец периода", through.totalChatsToDate.toString(), "Накопленно с первого сохранённого обращения")}
          ${overviewCard("Активных чатов за период", active.toString(), "Уникальные Telegram-чаты с завершённым ходом AI")}
          ${overviewCard("DAU в последний день", through.activeChatsWithTurn.toString(), "Чаты с завершённым ходом AI")}
          ${overviewCard("Чатов с первым голосовым", through.firstVoiceChatsToDate.toString(), "Накопленно на конец периода")}
          ${overviewCard("Завершённых ходов AI", turns.toString(), "За выбранный период, все платформы")}
          ${overviewCard("Ходов / активную AI-сессию", if (sessions > 0) decimal(turns.toDouble() / sessions, 1) else "—", "Все платформы, уникальные сессии с завершённым ходом")}
          ${overviewCard("Токенов LLM", tokens.toString(), "Входные + выходные, все платформы")}
          ${overviewCard("Токенов / ход", if (turns > 0) decimal(tokens.toDouble() / turns, 1) else "—", "Входные + выходные токены / завершённые ходы")}
          ${overviewCard("Обработано речи", decimal(observed.sumOf { it.sttMs } / 60000.0, 1) + " мин", "Длительность аудио, обработанного STT; не время ожидания")}
          ${overviewCard("Символов TTS", observed.sumOf { it.ttsChars }.toString(), "Текст, переданный озвучке")}
        </div>
        <div class="chart-grid">
          ${overviewChart("Рост пользователей · Telegram-чаты", days, listOf(
              ChartLine("Всего чатов", "#2563eb", days.map { byDay[it]?.takeIf(AdminHistoryDay::tracked)?.totalChatsToDate }),
              ChartLine("С первым голосовым", "#16a34a", days.map { byDay[it]?.takeIf(AdminHistoryDay::tracked)?.firstVoiceChatsToDate })), true)}
          ${overviewChart("DAU и активные за 7 дней", days, listOf(
              ChartLine("DAU", "#2563eb", days.map { byDay[it]?.takeIf(AdminHistoryDay::tracked)?.activeChatsWithTurn }),
              ChartLine("7 дней", "#16a34a", days.map { byDay[it]?.takeIf(AdminHistoryDay::tracked)?.activeChats7d })), true)}
          ${overviewChart("Новые и вернувшиеся", days, listOf(
              ChartLine("Новые чаты", "#2563eb", days.map { byDay[it]?.takeIf(AdminHistoryDay::tracked)?.newChats }),
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
        <p class="coverage">«7 дней» — уникальные активные чаты за последние семь календарных суток; первые шесть дней истории не отображаются.
        Возврат — чат, активный сегодня и хотя бы в один более ранний сохранённый день. Новые чаты и первые голосовые считаются по первой дате чата.</p>
        <h2>Источники новых чатов за период</h2>
        <div class="table-wrap"><table><thead><tr><th>Источник</th><th>Новые чаты</th><th>Первый голос</th></tr></thead>
        <tbody>${sources.ifBlank { "<tr><td colspan=\"3\">Нет данных</td></tr>" }}</tbody></table></div>
        <h2>Данные по дням · сохранённая история</h2>
        <div class="table-wrap"><table><thead><tr><th>День</th><th>Всего чатов</th><th>Новые</th>
        <th>DAU</th><th>7 дней</th><th>Вернулись</th><th>Первый голос</th><th>Ходы AI</th>
        <th>Токены вход</th><th>Токены выход</th><th>STT, с</th><th>TTS, символы</th></tr></thead><tbody>$rows</tbody></table></div>
        </section>
    """.trimIndent()
}

private fun overviewCard(title: String, value: String, note: String): String =
    """<article class="overview-card"><dt>${escapeHtml(title)}</dt><dd>${escapeHtml(value)}</dd>
    <p>${escapeHtml(note)}</p></article>"""

private data class ChartLine(val label: String, val color: String, val values: List<Long?>)

private fun overviewChart(
    title: String, days: List<String>, lines: List<ChartLine>, available: Boolean, valueDivisor: Long = 1,
): String {
    if (!available || lines.all { line -> line.values.all { it == null } })
        return "<section class=\"chart-card\"><h2>${escapeHtml(title)}</h2><p class=\"meta\">Нет данных</p></section>"
    val max = (lines.flatMap { it.values }.filterNotNull().maxOrNull() ?: 0L).coerceAtLeast(1)
    val width = 720.0
    val x = { index: Int -> 20.0 + index * (width - 40.0) / (days.size - 1).coerceAtLeast(1) }
    val y = { value: Long -> 125.0 - value.toDouble() / max * 100.0 }
    val paths = lines.joinToString("") { line ->
        val segments = mutableListOf<MutableList<Pair<Int, Long>>>()
        line.values.forEachIndexed { index, value ->
            if (value == null) return@forEachIndexed
            if (index == 0 || line.values[index - 1] == null) segments.add(mutableListOf())
            segments.last().add(index to value)
        }
        segments.joinToString("") { segment ->
            val points = segment.joinToString(" ") { (index, value) -> "${x(index)},${y(value)}" }
            "<polyline points=\"$points\" fill=\"none\" stroke=\"${line.color}\" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>"
        } + line.values.mapIndexedNotNull { index, value ->
            value?.let { "<circle cx=\"${x(index)}\" cy=\"${y(it)}\" r=\"3\" fill=\"${line.color}\"><title>${days[index]} · ${escapeHtml(line.label)}: ${decimal(it.toDouble() / valueDivisor, 3)}</title></circle>" }
        }.joinToString("")
    }
    val legend = lines.joinToString("") { line -> "<span><i style=\"background:${line.color}\"></i>${escapeHtml(line.label)}</span>" }
    return """<section class="chart-card"><h2>${escapeHtml(title)}</h2><div class="chart-legend">$legend</div>
      <svg viewBox="0 0 720 150" role="img" aria-label="${escapeHtml(title)}">
      <line x1="20" y1="125" x2="700" y2="125" stroke="#d8dee8"/>$paths</svg>
      <div class="chart-dates"><span>${days.first()}</span><span>${days.last()}</span></div></section>"""
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
  .chart-card svg { width:100%; height:auto; display:block; }
  .chart-legend { display:flex; gap:12px; flex-wrap:wrap; color:#57534e; font-size:11px; }
  .chart-legend span { display:inline-flex; align-items:center; gap:4px; }
  .chart-legend i { display:inline-block; width:8px; height:8px; border-radius:50%; }
  .chart-dates { display:flex; justify-content:space-between; font-size:11px; color:#78716c; }
  .table-wrap { overflow-x:auto; background:white; border:1px solid #e7e5e4; border-radius:12px; }
  .table-wrap table { margin:0; width:100%; white-space:nowrap; }
  .tabs { overflow-x:auto; white-space:nowrap; }
  .tabs a { flex:0 0 auto; }
  @media(max-width:800px) { .overview-head { display:block; } .period { margin-top:15px; justify-content:flex-start; } .chart-grid { grid-template-columns:1fr; } }
  @media(max-width:600px) { .overview-cards { grid-template-columns:1fr; } }
</style>
"""
