package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.analytics.CohortFunnel
import com.eliteteam.speakingcoach.analytics.ErrorStat
import com.eliteteam.speakingcoach.analytics.OnboardingFilter
import com.eliteteam.speakingcoach.analytics.OnboardingReport
import com.eliteteam.speakingcoach.analytics.OnboardingAnalyticsHealth
import com.eliteteam.speakingcoach.analytics.JourneyMode
import com.eliteteam.speakingcoach.analytics.JOURNEY_STAGES
import com.eliteteam.speakingcoach.analytics.JourneyReport
import com.eliteteam.speakingcoach.analytics.decisionCounts
import com.eliteteam.speakingcoach.ai.ReminderClockSummary
import com.eliteteam.speakingcoach.ai.LlmRequestPeriod
import kotlin.math.roundToInt
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun onboardingReportHtml(
    report: OnboardingReport?, reminderSummary: ReminderClockSummary? = null, llm: LlmRequestPeriod? = null,
    llmRange: LlmRange = LlmRange(LocalDate.now(ZoneId.of("Europe/Moscow")), LocalDate.now(ZoneId.of("Europe/Moscow"))),
): String {
    val body = if (report == null) {
        "<p>История онбординга не пишется: нет базы.</p>"
    } else {
        val closed = report.closedPrimary
        val started = closed.sumOf { it.size }
        val completed = closed.sumOf { cohort -> cohort.steps.first { it.name == "Результат собран" }.count }
        val returned = closed.sumOf { it.returnedNextDay }
        """
        <form class="inline" method="get" action="$ONBOARDING_ANALYTICS_PATH">
        <input type="hidden" name="llmFrom" value="${llmRange.from}"><input type="hidden" name="llmTo" value="${llmRange.to}">
        <label>Дней <select name="days">${listOf(7, 30, 90).joinToString("") { option ->
            "<option value=\"$option\"${if (option == report.filter.days) " selected" else ""}>$option</option>"
        }}</select></label>
        <label>Версия <select name="version">${filterOptions(report.versions, report.filter.version)}</select></label>
        <label>Источник <select name="source">${filterOptions(report.sources, report.filter.source)}</select></label>
        <label>Причина <select name="trigger">${filterOptions(report.triggers, report.filter.trigger)}</select></label>
        <label>Группа <select name="journey"><option value="primary"${if (report.filter.journeyMode == JourneyMode.PRIMARY) " selected" else ""}>Первый подходящий /start</option><option value="repeats"${if (report.filter.journeyMode == JourneyMode.REPEATS) " selected" else ""}>Повторные попытки</option></select></label>
        <button type="submit">Показать</button>
        </form>
        <a class="onb-export" href="${agentExportLink(report.filter, llmRange)}">Скачать JSON для анализа агентом</a>
        ${journeySections(report.journey, report.filter.journeyMode)}
        <details><summary>Дополнительная аналитика: когорты, A7 и голосовые</summary>
        <dl class="onb-kpis">
          ${card("Начали · закрытые дни", started.toString())}
          ${card("Результат за 24 ч", "$completed / $started · ${percentage(completed, started)}")}
          ${card("Вернулись D1", "$returned / $started · ${percentage(returned, started)}")}
          ${card("Нераспознанные · 7 дней", "${report.recognition.errors} / ${report.recognition.denominator} · ${percentage(report.recognition.errors, report.recognition.denominator)}")}
        </dl>
        <h2>Активация за 7 дней · первый подходящий /start</h2>
        ${activationTable(report)}
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
        ${voiceTurnTable(report)}
        ${lastOutcomeTable(report)}
        ${timingTable(report)}
        <h2>Результат и действия</h2>
        ${decisionTable(report)}
        <h2>Напоминания сейчас · все пользователи</h2>
        ${reminderClockChart(reminderSummary)}
        <h2>Запросы к LLM · все пользователи</h2>
        ${llmRangeForm(ONBOARDING_ANALYTICS_PATH, llmRange, hiddenOnboardingFilter(report.filter))}
        ${llmRequestTable(llm)}
        <h2>Качество сбора · с запуска сервера</h2>
        <table><thead><tr><th>Показатель</th><th>Число</th><th>Из записей</th></tr></thead><tbody>
          <tr><td>Ошибки записи</td><td>${OnboardingAnalyticsHealth.failedWrites()}</td><td>${OnboardingAnalyticsHealth.attemptedWrites()}</td></tr>
          <tr><td>Без связанной попытки</td><td>${OnboardingAnalyticsHealth.missingAttempts()}</td><td>${OnboardingAnalyticsHealth.attemptedWrites()}</td></tr>
        </tbody></table>
        </details>
        <details class="onb-notes"><summary>Как читать данные</summary>
          <ul><li>Закрытый день: прошли 24 часа после его окончания по Москве. Шаг воронки учитывается в первые 24 часа после старта.</li>
          <li>A7 созревает через 7 × 24 часа от подходящего входа. Знаменатель A7 — только созревшие входы. D1 и D7 — отдельные календарные дни.</li>
          <li>Запись входа происходит после действия бота: при падении процесса часть входов может отсутствовать. Старые попытки без события входа не включены в A7.</li>
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

private val journeyDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

private fun journeySections(journey: JourneyReport, mode: JourneyMode): String {
    val population = if (mode == JourneyMode.PRIMARY) "пользователей · первый подходящий /start" else "повторных попыток"
    val reached = journey.steps.associate { it.stage.id to it.reached }
    val steps = journey.steps.joinToString("") { step ->
        val terminal = step.stage.id == JOURNEY_STAGES.last().id
        "<tr><th scope=\"row\">${escapeHtml(step.stage.label)}</th><td>${step.reached}</td>" +
            "<td>${if (terminal) "—" else step.continued}</td>" +
            "<td>${if (terminal) "—" else step.stopped}</td>" +
            "<td>${if (terminal) "—" else step.stoppedWithOutcome}</td>" +
            "<td>${if (terminal) "—" else percentage(step.continued, step.reached)}</td></tr>"
    }
    val errors = journey.errors.joinToString("") { error ->
        val label = JOURNEY_STAGES.firstOrNull { it.id == error.stageId }?.label ?: error.stageId
        val affected = if (mode == JourneyMode.PRIMARY) error.users else error.attempts
        "<tr><td>${escapeHtml(label)}</td><td>${escapeHtml(error.technicalStage)}</td>" +
            "<td>${escapeHtml(error.reason)}</td><td>${error.events}</td><td>${error.users}</td>" +
            "<td>$affected/${reached[error.stageId] ?: 0} · ${percentage(affected, reached[error.stageId] ?: 0)}</td></tr>"
    }.ifEmpty { "<tr><td colspan=\"6\">Ошибок и неуспешных исходов пока нет.</td></tr>" }
    val recent = journey.recent.joinToString("") { user ->
        val stage = JOURNEY_STAGES.firstOrNull { it.id == user.stageId }?.label ?: user.stageId
        val state = when (user.state) {
            "completed" -> "Завершил"
            "in_progress" -> "В процессе"
            "stopped" -> "Остановился после $stage"
            else -> "Неполные данные"
        }
        val username = user.username?.let { "@${escapeHtml(it)}" } ?: "—"
        "<tr><td>${journeyDateFormat.format(user.startedAt.atZone(ZoneId.of("Europe/Moscow")))}</td>" +
            "<td>$username</td><td>${user.chatId ?: "—"}</td>" +
            "<td>${escapeHtml(user.source ?: "direct")}</td><td>${escapeHtml(user.version ?: "—")}</td>" +
            "<td>${user.attemptNumber ?: "—"}</td><td>${escapeHtml(stage)}</td><td>${escapeHtml(state)}</td>" +
            "<td>${journeyDateFormat.format(user.lastAt.atZone(ZoneId.of("Europe/Moscow")))}</td>" +
            "<td>${escapeHtml(user.lastError ?: "—")}</td></tr>"
    }.ifEmpty { "<tr><td colspan=\"10\">Пока нет начавших онбординг в выбранной группе.</td></tr>" }
    return """
        <h2>Путь по стадиям</h2>
        <p class="meta">${escapeHtml(population)}: ${journey.total}. В процессе: ${journey.open}; окно 24 часа закрыто: ${journey.closed}; неполные или непоследовательные данные: ${journey.incomplete}. Счётчики начинаются с включения записи событий.</p>
        <div class="onb-scroll"><table><thead><tr><th>Стадия</th><th>Дошли</th><th>Перешли дальше</th><th>Остановились</th><th>Из них с исходом</th><th>Переход / дошли</th></tr></thead><tbody>$steps</tbody></table></div>
        <p class="meta">«Дошли» включает открытые попытки. «Остановились» — только закрытые 24-часовые окна без следующей обязательной стадии; технический исход рядом не доказывает причину паузы. Пропущенные отметки вынесены в неполные данные. Grammar, Vocabulary и Fluency остаются дополнительными действиями ниже.</p>
        <h2>Ошибки по стадиям</h2>
        <p class="meta">Те же фильтры начала попытки. События и затронутые пользователи показаны отдельно; отсутствие речи — не технический сбой STT. Старые ошибки могут не иметь точной причины.</p>
        <div class="onb-scroll"><table><thead><tr><th>Стадия</th><th>Технический этап</th><th>Исход / причина</th><th>События</th><th>Люди</th><th>${if (mode == JourneyMode.PRIMARY) "Люди / дошли" else "Попытки / дошли"}</th></tr></thead><tbody>$errors</tbody></table></div>
        <h2>Последние 15 начавших</h2>
        <p class="meta">${if (mode == JourneyMode.PRIMARY) "Первый подходящий вход каждого пользователя" else "Последняя подходящая повторная попытка каждого пользователя"}; username на момент начала. Telegram ID означает ID чата. Состояние пересчитывается при открытии страницы и учитывает позднее продолжение; воронка фиксирует первые 24 часа.</p>
        <div class="onb-scroll"><table><thead><tr><th>Начало · МСК</th><th>Username</th><th>Telegram ID чата</th><th>Источник</th><th>Версия</th><th>Попытка</th><th>Последняя стадия</th><th>Состояние</th><th>Последнее событие · МСК</th><th>Последний исход</th></tr></thead><tbody>$recent</tbody></table></div>
    """.trimIndent()
}

private fun llmRequestTable(summary: LlmRequestPeriod?): String {
    if (summary == null) return "<p>Счётчик LLM сейчас недоступен.</p>"
    val byPurpose = summary.byPurpose
    return """
        <p class="meta">${escapeHtml(summary.from)} — ${escapeHtml(summary.to)} включительно · ${escapeHtml(summary.timezone)}. Попытки вызова модели, включая ошибки и повторы приложения. Данные не относятся к выбранной когорте; вызовы до внедрения счётчика не восстановлены.</p>
        <table><thead><tr><th>Всего</th><th>Ошибки</th><th>Онбординг</th><th>Ответы</th><th>Исправления</th><th>Review</th></tr></thead><tbody>
        <tr><td>${summary.requests}</td><td>${summary.failures}</td><td>${byPurpose["onboarding"] ?: 0}</td><td>${byPurpose["reply"] ?: 0}</td><td>${byPurpose["notes"] ?: 0}</td><td>${byPurpose["session_review"] ?: 0}</td></tr>
        </tbody></table>
    """.trimIndent()
}

private fun hiddenOnboardingFilter(filter: OnboardingFilter): String = buildString {
    append("<input type=\"hidden\" name=\"days\" value=\"${filter.days}\">")
    append("<input type=\"hidden\" name=\"journey\" value=\"${if (filter.journeyMode == JourneyMode.REPEATS) "repeats" else "primary"}\">")
    for ((name, value) in listOf("version" to filter.version, "source" to filter.source, "trigger" to filter.trigger)) {
        if (value != null) append("<input type=\"hidden\" name=\"$name\" value=\"${escapeHtml(value)}\">")
    }
}

private fun rate(part: Int, whole: Int): Int = if (whole == 0) 0 else ((part * 100.0) / whole).roundToInt()

private fun agentExportLink(filter: OnboardingFilter, range: LlmRange): String {
    fun parameter(name: String, value: String?): String? = value?.let {
        "$name=${URLEncoder.encode(it, StandardCharsets.UTF_8)}"
    }
    val query = listOfNotNull(
        "days=${filter.days}", parameter("version", filter.version), parameter("source", filter.source),
        parameter("trigger", filter.trigger), "journey=${if (filter.journeyMode == JourneyMode.REPEATS) "repeats" else "primary"}",
        "llmFrom=${range.from}", "llmTo=${range.to}",
    ).joinToString("&amp;")
    return "$ONBOARDING_AGENT_PATH?$query"
}

private fun percentage(part: Int, whole: Int): String = if (whole == 0) "—" else "${rate(part, whole)}%"

private fun reminderClockChart(summary: ReminderClockSummary?): String {
    if (summary == null) return "<p class=\"meta\">Текущие настройки напоминаний недоступны.</p>"
    val hours = (0..23).map { "%02d".format(it) to (summary.hours["%02d".format(it)] ?: 0) }
    val bars = hours.filter { it.second > 0 }.joinToString("") { (hour, count) ->
        bar("$hour:00–$hour:59", count, summary.active)
    }
    return "<p class=\"meta\">Активных: ${summary.active}. Время по Москве; текущие настройки всех пользователей, " +
        "фильтры онбординга не применяются. Смена времени и отключение отражаются сразу.</p>" +
        if (bars.isEmpty()) "<p class=\"meta\">Пока нет активных напоминаний.</p>" else
            "<div class=\"onb-chart\" aria-label=\"Распределение времени напоминаний\">$bars</div>"
}

private fun activationTable(report: OnboardingReport): String {
    if (report.activation.isEmpty()) return "<p class=\"meta\">Пока нет входов с новой аналитикой.</p>"
    val rows = report.activation.joinToString("") { c ->
        "<tr><th scope=\"row\">${c.day}</th><td>${c.eligible}</td><td>${c.invitations}</td>" +
            "<td>${c.beginPressed}</td><td>${c.firstQuestions}</td><td>${c.firstVoices}</td>" +
            "<td>${c.recognizedVoices}</td><td>${c.results}</td>" +
            "<td>${c.activated}/${c.mature} · ${percentage(c.activated, c.mature)}</td>" +
            "<td>${c.practiceTwoDays}/${c.mature}</td><td>${metric(c.resultP50Sec, "с")}</td>" +
            "<td>${metric(c.practiceP50Sec, "с")}</td><td>${c.missingAttempt}</td></tr>"
    }
    return "<div class=\"onb-scroll\"><table><thead><tr><th>Когорта</th><th>Подходящих</th>" +
        "<th>Приглашение</th><th>Нажали</th><th>Первый вопрос</th><th>Голос получен</th>" +
        "<th>Распознан</th><th>Результат</th><th>A7 / созрели</th><th>2 дня / созрели</th>" +
        "<th>До результата p50</th><th>До практики p50</th><th>Без попытки</th></tr></thead><tbody>$rows</tbody></table></div>"
}

private fun voiceTurnTable(report: OnboardingReport): String {
    val turns = report.diagnostics.turns
    if (turns.isEmpty()) return ""
    val rows = turns.joinToString("") { t ->
        "<tr><td>${if (t.index == 10) "10+" else t.index}</td><td>${t.voices}</td>" +
            "<td>${t.recognized}</td><td>${t.noSpeech}</td><td>${t.technicalFailures}</td>" +
            "<td>${t.nextVoice}/${t.voices}</td><td>${t.resultAfter}/${t.voices}</td></tr>"
    }
    return "<h3>Переход после голосового ответа</h3><div class=\"onb-scroll\"><table><thead><tr>" +
        "<th>Номер</th><th>Голосовых</th><th>Распознано</th><th>Нет речи</th><th>Техсбой</th>" +
        "<th>Следующий голос</th><th>Результат позже</th></tr></thead><tbody>$rows</tbody></table></div>"
}

private fun lastOutcomeTable(report: OnboardingReport): String {
    val d = report.diagnostics
    if (d.lastOutcomes.isEmpty()) return ""
    val rows = d.lastOutcomes.entries.sortedByDescending { it.value }
        .joinToString("") { (outcome, count) -> "<tr><td>${escapeHtml(outcome)}</td><td>$count</td></tr>" }
    return "<h3>Последний исход перед паузой</h3><table><thead><tr><th>Исход</th><th>Попыток</th>" +
        "</tr></thead><tbody>$rows</tbody></table>" +
        "<p class=\"meta\">Два подряд сбоя распознавания: ${d.attemptsWithConsecutiveRecognitionFailures} попыток. " +
        "Последний исход не доказывает причину остановки.</p>"
}

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
