package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.analytics.CohortFunnel
import com.eliteteam.speakingcoach.analytics.ErrorStat
import com.eliteteam.speakingcoach.analytics.OnboardingFilter
import com.eliteteam.speakingcoach.analytics.OnboardingReport
import com.eliteteam.speakingcoach.analytics.OnboardingAnalyticsHealth
import com.eliteteam.speakingcoach.analytics.decisionCounts
import kotlin.math.roundToInt
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal fun onboardingReportHtml(report: OnboardingReport?): String {
    val body = if (report == null) {
        "<p>История онбординга не пишется: нет базы.</p>"
    } else {
        val closed = report.closedPrimary
        val started = closed.sumOf { it.size }
        val completed = closed.sumOf { cohort -> cohort.steps.first { it.name == "Результат собран" }.count }
        val returned = closed.sumOf { it.returnedNextDay }
        """
        <form class="inline" method="get" action="$ONBOARDING_ANALYTICS_PATH">
        <label>Дней <select name="days">${listOf(7, 30, 90).joinToString("") { option ->
            "<option value=\"$option\"${if (option == report.filter.days) " selected" else ""}>$option</option>"
        }}</select></label>
        <label>Версия <select name="version">${filterOptions(report.versions, report.filter.version)}</select></label>
        <label>Источник <select name="source">${filterOptions(report.sources, report.filter.source)}</select></label>
        <label>Причина <select name="trigger">${filterOptions(report.triggers, report.filter.trigger)}</select></label>
        <button type="submit">Показать</button>
        </form>
        <a class="onb-export" href="${agentExportLink(report.filter)}">Скачать JSON для анализа агентом</a>
        <dl class="onb-kpis">
          ${card("Начали · закрытые дни", started.toString())}
          ${card("Результат за 24 ч", "$completed / $started · ${percentage(completed, started)}")}
          ${card("Вернулись D1", "$returned / $started · ${percentage(returned, started)}")}
          ${card("Нераспознанные · 7 дней", "${report.recognition.errors} / ${report.recognition.denominator} · ${percentage(report.recognition.errors, report.recognition.denominator)}")}
        </dl>
        <h2>Воронка · первая попытка, закрытые дни</h2>
        ${funnelChart(closed)}
        <h2>Когорты по дням</h2>
        ${cohortTable(closed, withReturn = true)}
        <details><summary>Открытые дни · ${report.openPrimary.sumOf { it.size }} попыток</summary>
          ${cohortTable(report.openPrimary, withReturn = false)}
        </details>
        <details><summary>Повторные попытки · ${report.closedRepeats.sumOf { it.size }} закрытых, ${report.openRepeatCount} открытых</summary>
          ${cohortTable(report.closedRepeats, withReturn = false)}
        </details>
        <h2>Голосовые и ошибки</h2>
        <div class="onb-two">
          <section><h3>Исходы голосовых</h3>${outcomeChart(report.diagnostics.outcomes)}</section>
          <section><h3>Ошибки за 7 дней</h3>${errorTable(report.recognition, report.assessment)}</section>
        </div>
        <div class="onb-scroll">${voiceStages(report.diagnostics.outcomesByStage)}</div>
        ${timingTable(report)}
        <h2>Результат и действия</h2>
        ${decisionTable(report)}
        <h2>Качество сбора · с запуска сервера</h2>
        <table><thead><tr><th>Показатель</th><th>Число</th><th>Из записей</th></tr></thead><tbody>
          <tr><td>Ошибки записи</td><td>${OnboardingAnalyticsHealth.failedWrites()}</td><td>${OnboardingAnalyticsHealth.attemptedWrites()}</td></tr>
          <tr><td>Без связанной попытки</td><td>${OnboardingAnalyticsHealth.missingAttempts()}</td><td>${OnboardingAnalyticsHealth.attemptedWrites()}</td></tr>
        </tbody></table>
        <details class="onb-notes"><summary>Как читать данные</summary>
          <ul><li>Закрытый день: прошли 24 часа после его окончания по Москве. Шаг воронки учитывается в первые 24 часа после старта.</li>
          <li>Открытые дни ещё не завершены. D1 — обычный голос на следующий календарный день; D7 — на седьмой.</li>
          <li>Нажатие карточки не означает прочтение. Пауза между голосовыми и время обработки показаны отдельно.</li></ul>
        </details>
        """.trimIndent()
    }
    return """
        <!doctype html>
        <html lang="ru">
        <head>
        <meta charset="utf-8">
        <meta name="robots" content="noindex">
        <title>Speaky onboarding</title>
        ${pageStyle()}
        ${onboardingStyle()}
        </head>
        <body>
        <h1>Speaky</h1>
        ${adminTabs(ONBOARDING_ANALYTICS_PATH)}
        $body
        </body>
        </html>
    """.trimIndent()
}

private fun rate(part: Int, whole: Int): Int = if (whole == 0) 0 else ((part * 100.0) / whole).roundToInt()

private fun agentExportLink(filter: OnboardingFilter): String {
    fun parameter(name: String, value: String?): String? = value?.let {
        "$name=${URLEncoder.encode(it, StandardCharsets.UTF_8)}"
    }
    val query = listOfNotNull(
        "days=${filter.days}", parameter("version", filter.version), parameter("source", filter.source),
        parameter("trigger", filter.trigger),
    ).joinToString("&amp;")
    return "$ONBOARDING_AGENT_PATH?$query"
}

private fun percentage(part: Int, whole: Int): String = if (whole == 0) "—" else "${rate(part, whole)}%"

private fun bar(label: String, count: Int, total: Int): String {
    val width = rate(count, total).coerceIn(0, 100)
    return "<div class=\"onb-bar-row\"><span>${escapeHtml(label)}</span>" +
        "<span class=\"onb-track\"><span style=\"width:$width%\"></span></span>" +
        "<strong>$count <small>/$total</small></strong></div>"
}

private fun funnelChart(cohorts: List<CohortFunnel>): String {
    if (cohorts.isEmpty()) return "<p class=\"meta\">Пока нет закрытых когорт.</p>"
    val total = cohorts.sumOf { it.size }
    return "<div class=\"onb-chart\" aria-label=\"Воронка онбординга\">" +
        cohorts.first().steps.indices.joinToString("") { index ->
            val step = cohorts.first().steps[index]
            bar(step.name, cohorts.sumOf { it.steps[index].count }, total)
        } + "</div>"
}

private fun cohortTable(cohorts: List<CohortFunnel>, withReturn: Boolean): String {
    if (cohorts.isEmpty()) return "<p class=\"meta\">Пока нет данных.</p>"
    val rows = cohorts.joinToString("") { cohort ->
        fun step(name: String): Int = cohort.steps.first { it.name == name }.count
        val day7 = if (withReturn && cohort.d7Eligible > 0) "${cohort.returnedDay7}/${cohort.d7Eligible}" else "—"
        """
        <tr><th scope="row">${escapeHtml(cohort.day.toString())}</th><td>${cohort.size}</td>
          <td>${step("Let’s chat")}</td><td>${step("Первое голосовое")}</td>
          <td>${step("120 сек")}</td><td>${step("Результат собран")}</td>
          <td>${step("Минуты выбраны")}</td><td>${if (withReturn) "${cohort.returnedNextDay}/${cohort.size}" else "—"}</td>
          <td>$day7</td></tr>
        <tr class="onb-detail"><td colspan="9"><details><summary>Все шаги и состав результата · ${escapeHtml(cohort.day.toString())}</summary>
          <div class="onb-scroll"><table><thead><tr><th>Шаг</th><th>Люди</th><th>От старта</th><th>От прошлого</th></tr></thead><tbody>
          ${cohort.steps.joinToString("") { step ->
              "<tr><td>${escapeHtml(step.name)}</td><td>${step.count}</td><td>${step.ofStartPercent}%</td><td>${step.ofPreviousPercent}%</td></tr>"
          }}</tbody></table></div>
          <div class="onb-detail-grid"><span>${levelLine(cohort.levels)}</span><span>${goalLine(cohort.goals)}</span>
          <span>Первый обычный голос: ${cohort.firstPractice}/${cohort.size}</span>
          <span>Шагов без предыдущего: ${cohort.skippedPrevious}</span>
          ${if (withReturn) "<span>D1 среди завершивших: ${cohort.returnedNextDay}/${cohort.completedByD1}</span>" else ""}
          ${if (withReturn && cohort.d7Eligible > 0) "<span>D7 среди завершивших: ${cohort.returnedDay7}/${cohort.completedByD7}</span>" else ""}</div>
        </details></td></tr>
        """.trimIndent()
    }
    return "<div class=\"onb-scroll\"><table class=\"onb-cohorts\"><thead><tr>" +
        "<th>День</th><th>Начали</th><th>Кнопка</th><th>Голос</th><th>120 с</th><th>Результат</th>" +
        "<th>Минуты</th><th>D1</th><th>D7</th></tr></thead><tbody>$rows</tbody></table></div>"
}

private fun outcomeChart(outcomes: Map<String, Int>): String {
    if (outcomes.isEmpty()) return "<p class=\"meta\">Пока нет данных.</p>"
    val names = mapOf(
        "recognized" to "Распознано", "no_speech" to "Нет речи", "stt_failure" to "Сбой STT",
        "processing_failure" to "Сбой обработки", "delivery_failure" to "Сбой доставки",
        "queue_full" to "Очередь полна", "unknown" to "Неизвестно",
    )
    val total = outcomes.values.sum()
    return "<div class=\"onb-chart\" aria-label=\"Исходы голосовых\">" +
        outcomes.entries.sortedByDescending { it.value }.joinToString("") { (key, count) ->
            bar(names[key] ?: key, count, total)
        } + "</div>"
}

private fun errorTable(recognition: ErrorStat, assessment: ErrorStat): String = """
    <table><thead><tr><th>Ошибка</th><th>Случаи</th><th>Доля</th><th>Люди</th></tr></thead><tbody>
      <tr><td>Не распознано</td><td>${recognition.errors}/${recognition.denominator} голосовых</td><td>${percentage(recognition.errors, recognition.denominator)}</td><td>${recognition.people}</td></tr>
      <tr><td>Сборка результата</td><td>${assessment.errors}/${assessment.denominator} попыток на 120 с</td><td>${percentage(assessment.errors, assessment.denominator)}</td><td>${assessment.people}</td></tr>
    </tbody></table>
""".trimIndent()

private fun timingTable(report: OnboardingReport): String {
    val data = report.diagnostics
    val voiceCounts = data.voicesPerCompleted.entries.sortedBy { it.key }
        .joinToString(" · ") { "${escapeHtml(it.key)}: ${it.value}" }.ifEmpty { "—" }
    return """
        <div class="onb-scroll"><table><thead><tr><th>Показатель</th><th>p50</th><th>p95</th><th>Распределение</th></tr></thead><tbody>
          <tr><td>Обработка + доставка, распознанное голосовое</td><td>${metric(data.processingP50Ms, "мс")}</td><td>${metric(data.processingP95Ms, "мс")}</td><td>—</td></tr>
          <tr><td>Пауза до следующего голосового</td><td>${metric(data.replyGapP50Sec, "с")}</td><td>${metric(data.replyGapP95Sec, "с")}</td><td>—</td></tr>
          <tr><td>Голосовых до результата</td><td>${metric(data.voicesPerCompletedP50, "")}</td><td>—</td><td>$voiceCounts</td></tr>
        </tbody></table></div>
    """.trimIndent()
}

private fun decisionTable(report: OnboardingReport): String {
    val d = report.decisions
    val primaryAttempts = report.closedPrimary.sumOf { it.size } + report.openPrimary.sumOf { it.size }
    val rowsHtml = decisionCounts(d, primaryAttempts).joinToString("") { item ->
        "<tr><td>${escapeHtml(item.label)}</td><td>${item.count}</td><td>${item.denominator}</td>" +
            "<td>${percentage(item.count, item.denominator)}</td></tr>"
    }
    val extras = listOf(
        "Повтор сборки" to d.retryRequested, "Успешный повтор" to d.retryRecovered,
        "Подсказка для текста" to d.textHint, "Голосовое до старта" to d.preBeginVoiceHint,
    ).joinToString("") { (label, count) -> "<tr><td>${escapeHtml(label)}</td><td>$count</td><td>—</td><td>—</td></tr>" }
    return "<div class=\"onb-scroll\"><table><thead><tr><th>Действие</th><th>Люди / события</th>" +
        "<th>Из</th><th>Доля</th></tr></thead><tbody>$rowsHtml$extras</tbody></table></div>"
}

private fun voiceStages(stages: Map<String, Map<String, Int>>): String {
    if (stages.isEmpty()) return ""
    val outcomes = listOf("recognized", "no_speech", "stt_failure", "processing_failure", "delivery_failure", "queue_full", "unknown")
    val rows = listOf("0–30 сек", "30–60 сек", "60–90 сек", "90–120 сек", "120+ сек", "неизвестно")
        .filter { it in stages }
        .joinToString("\n") { stage ->
            "<tr><td>${escapeHtml(stage)}</td>${outcomes.joinToString("") { outcome -> "<td>${stages[stage]?.get(outcome) ?: 0}</td>" }}</tr>"
        }
    return "<table><thead><tr><th>Речь до записи</th>${outcomes.joinToString("") { "<th>$it</th>" }}</tr></thead><tbody>$rows</tbody></table>"
}

private fun filterOptions(values: List<String>, selected: String?): String =
    "<option value=\"\">Все</option>" + values.distinct().joinToString("") { value ->
        val escaped = escapeHtml(value)
        "<option value=\"$escaped\"${if (value == selected) " selected" else ""}>$escaped</option>"
    }

private fun metric(value: Int?, unit: String): String = value?.let { "$it $unit" } ?: "—"

private fun levelLine(levels: Map<String, Int>): String {
    if (levels.isEmpty()) return "Уровни: пока нет"
    val order = listOf("A1", "A2", "B1", "B2", "C1", "C2", "нет уровня")
    val known = order.filter { levels.containsKey(it) }.map { "$it ${levels.getValue(it)}" }
    val rest = levels.filterKeys { it !in order }.map { "${escapeHtml(it.key)} ${it.value}" }
    return "Уровни: " + (known + rest).joinToString(" · ")
}

private fun goalLine(goals: Map<Int, Int>): String {
    if (goals.isEmpty()) return "Минуты: пока не выбраны"
    return "Минуты: " + listOf(5, 10, 15).filter { goals.containsKey(it) }
        .joinToString(" · ") { "$it мин ${goals.getValue(it)}" }
}

private fun onboardingStyle(): String = """
    <style>
      body { max-width: 1180px; padding: 0 20px 40px; }
      h2 { margin: 28px 0 12px; }
      h3 { font-size: 0.95rem; margin: 0 0 10px; }
      .onb-kpis { grid-template-columns: repeat(auto-fit, minmax(205px, 1fr)); margin: 20px 0 28px; }
      .onb-kpis dd { font-size: 1.35rem; font-variant-numeric: tabular-nums; }
      .onb-chart { background: #fff; border: 1px solid #e7e5e4; border-radius: 12px; padding: 14px; }
      .onb-bar-row { display: grid; grid-template-columns: minmax(130px, 190px) 1fr 88px; align-items: center; gap: 12px; margin: 7px 0; font-size: 0.88rem; }
      .onb-bar-row strong { text-align: right; font-variant-numeric: tabular-nums; }
      .onb-bar-row small { color: #78716c; font-weight: 400; }
      .onb-track { height: 12px; border-radius: 8px; background: #f5f5f4; overflow: hidden; }
      .onb-track > span { display: block; height: 100%; background: #0f766e; border-radius: 8px; }
      .onb-two { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin: 12px 0; }
      .onb-two section { min-width: 0; }
      .onb-scroll { overflow-x: auto; margin: 12px 0; }
      .onb-scroll table { min-width: 650px; }
      .onb-cohorts th, .onb-cohorts td { white-space: nowrap; font-variant-numeric: tabular-nums; }
      .onb-detail td { padding: 0 10px 8px; background: #fafaf9; white-space: normal; }
      .onb-detail-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 8px; font-size: 0.88rem; padding: 8px 0; }
      details { margin: 12px 0; }
      summary { cursor: pointer; color: #0f766e; font-weight: 600; }
      .onb-export { display: inline-block; color: #0f766e; margin: 2px 0 12px; font-weight: 600; }
      .onb-notes { margin-top: 24px; color: #57534e; }
      td:nth-child(n+2) { font-variant-numeric: tabular-nums; }
      @media (max-width: 760px) {
        .onb-two { grid-template-columns: 1fr; }
        .onb-bar-row { grid-template-columns: 110px 1fr 65px; gap: 6px; font-size: 0.78rem; }
      }
    </style>
""".trimIndent()
