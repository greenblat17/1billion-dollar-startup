package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.analytics.CohortFunnel
import com.eliteteam.speakingcoach.analytics.ErrorStat
import com.eliteteam.speakingcoach.analytics.OnboardingReport
import com.eliteteam.speakingcoach.analytics.OnboardingAnalyticsHealth

internal fun onboardingReportHtml(report: OnboardingReport?): String {
    val body = if (report == null) {
        "<p>История онбординга не пишется: нет базы.</p>"
    } else {
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
        <h2>Первая попытка, закрытые дни</h2>
        <p class="meta">День закрывается через 24 часа после его конца. Шаг считается, только если он случился в течение 24 часов после старта. Возврат — обычное голосовое на следующий календарный день.</p>
        ${cohortSections(report.closedPrimary, withReturn = true)}
        <h2>Ещё идёт</h2>
        <p class="meta">Эти первые попытки ещё не закрыты. Проценты здесь не отвал.</p>
        ${cohortSections(report.openPrimary, withReturn = false)}
        <h2>Повторные попытки</h2>
        <p class="meta">Повторный /onboarding и автоматический перезапуск. В главную воронку не входят. Ещё идёт: ${report.openRepeatCount}.</p>
        ${cohortSections(report.closedRepeats, withReturn = false)}
        <h2>Ошибки за 7 дней</h2>
        ${errorLine("Не распознано", report.recognition, "голосовых")}
        ${errorLine("Сборка результата", report.assessment, "попыток, дошедших до 120 секунд")}
        <h2>Голосовые за выбранный период</h2>
        <p>${report.diagnostics.outcomes.entries.sortedBy { it.key }.joinToString(" · ") { "${escapeHtml(it.key)} ${it.value}" }.ifEmpty { "Пока нет данных" }}</p>
        ${voiceStages(report.diagnostics.outcomesByStage)}
        <p>Обработка и доставка ответа на распознанное голосовое: p50 ${metric(report.diagnostics.processingP50Ms, "мс")} · p95 ${metric(report.diagnostics.processingP95Ms, "мс")}</p>
        <p>Пауза между ответом бота и следующим голосовым: p50 ${metric(report.diagnostics.replyGapP50Sec, "с")} · p95 ${metric(report.diagnostics.replyGapP95Sec, "с")}</p>
        <p>Распознанных голосовых до результата: p50 ${metric(report.diagnostics.voicesPerCompletedP50, "")} · ${report.diagnostics.voicesPerCompleted.entries.sortedBy { it.key }.joinToString(" · ") { "${it.key} записи: ${it.value}" }.ifEmpty { "пока нет данных" }}</p>
        <h2>Результат и следующий шаг</h2>
        <p>Результат собран: ${report.decisions.resultBuilt} из ${report.decisions.attempts} попыток · доставлен: ${report.decisions.resultDelivered} из ${report.decisions.resultBuilt} собранных · с баллом: ${report.decisions.scored} из ${report.decisions.resultDelivered} доставленных</p>
        <p>Доставлены примеры: Grammar ${report.decisions.grammarExamplesShown} из ${report.decisions.resultDelivered} · Vocabulary ${report.decisions.vocabularyExamplesShown} из ${report.decisions.resultDelivered}; измерения Fluency: ${report.decisions.fluencyMeasurementsShown} из ${report.decisions.resultDelivered}</p>
        <p>Показан выбор минут: ${report.decisions.goalShown} из ${report.decisions.resultDelivered} доставленных результатов · минуты выбраны: ${report.decisions.goalSelected} из ${report.decisions.goalShown} · предложено напоминание: ${report.decisions.reminderOffered} из ${report.decisions.goalSelected}</p>
        <p>Напоминание выбрали: ${report.decisions.reminderAccepted} из ${report.decisions.reminderOffered} · отказались: ${report.decisions.reminderDeclined} из ${report.decisions.reminderOffered} · время сохранено: ${report.decisions.reminderSet} из ${report.decisions.reminderAccepted}</p>
        <p>Открыли Profile: ${report.decisions.profileOpened} из ${report.decisions.goalSelected} выбравших минуты · See you tomorrow: ${report.decisions.bye} из ${report.decisions.goalSelected} · первый обычный голос: ${report.decisions.firstPractice} из ${report.decisions.attempts} начавших</p>
        <p>Повтор сборки результата: ${report.decisions.retryRequested} · успешный повтор: ${report.decisions.retryRecovered} · подсказка для текста: ${report.decisions.textHint} · голосовое до старта: ${report.decisions.preBeginVoiceHint}</p>
        <h2>Качество сбора, с запуска сервера</h2>
        <p>Ошибки записи: ${OnboardingAnalyticsHealth.failedWrites()} из ${OnboardingAnalyticsHealth.attemptedWrites()} · события без попытки: ${OnboardingAnalyticsHealth.missingAttempts()} из ${OnboardingAnalyticsHealth.attemptedWrites()} записей</p>
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
        </head>
        <body>
        <h1>Speaky</h1>
        ${adminTabs(ONBOARDING_ANALYTICS_PATH)}
        $body
        </body>
        </html>
    """.trimIndent()
}

private fun cohortSections(cohorts: List<CohortFunnel>, withReturn: Boolean): String {
    if (cohorts.isEmpty()) return "<p class=\"meta\">Пока нет данных.</p>"
    return cohorts.joinToString("\n") { cohort ->
        val levels = levelLine(cohort.levels)
        val goals = goalLine(cohort.goals)
        val returned = if (withReturn) {
            "<p>D1: ${cohort.returnedNextDay} из ${cohort.size} начавших (${cohort.returnedNextDayPercent}%) · " +
                "${cohort.returnedNextDay} из ${cohort.completedByD1} завершивших к концу D1</p>"
        } else {
            ""
        }
        """
        <h3>${escapeHtml(cohort.day.toString())}</h3>
        <p class="meta">Попыток: ${cohort.size}${if (cohort.skippedPrevious > 0) " · шагов без предыдущего: ${cohort.skippedPrevious}" else ""}</p>
        <table>
        <thead><tr><th>Шаг</th><th>Люди</th><th>От старта</th><th>От прошлого шага</th></tr></thead>
        <tbody>
        ${cohort.steps.joinToString("\n") { step ->
            "<tr><td>${escapeHtml(step.name)}</td><td>${step.count}</td><td>${step.ofStartPercent}%</td><td>${step.ofPreviousPercent}%</td></tr>"
        }}
        </tbody>
        </table>
        <p>$levels</p>
        <p>$goals</p>
        <p>Первый обычный голос: ${cohort.firstPractice}${if (cohort.d7Eligible > 0) " · D7: ${cohort.returnedDay7} из ${cohort.d7Eligible} начавших · ${cohort.returnedDay7} из ${cohort.completedByD7} завершивших к концу D7" else ""}</p>
        $returned
        """.trimIndent()
    }
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
    val rest = levels.filterKeys { it !in order }.map { "${it.key} ${it.value}" }
    return "Уровни: " + (known + rest).joinToString(" · ")
}

private fun goalLine(goals: Map<Int, Int>): String {
    if (goals.isEmpty()) return "Минуты: пока не выбраны"
    return "Минуты: " + listOf(5, 10, 15).filter { goals.containsKey(it) }
        .joinToString(" · ") { "$it мин ${goals.getValue(it)}" }
}

private fun errorLine(title: String, stat: ErrorStat, denominatorLabel: String): String {
    return "<p>${escapeHtml(title)}: ${stat.errors} из ${stat.denominator} $denominatorLabel (${stat.percent}%), затронуто ${stat.people}</p>"
}
