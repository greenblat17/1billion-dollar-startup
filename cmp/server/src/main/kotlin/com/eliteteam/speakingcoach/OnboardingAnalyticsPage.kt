package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.analytics.CohortFunnel
import com.eliteteam.speakingcoach.analytics.ErrorStat
import com.eliteteam.speakingcoach.analytics.OnboardingReport

internal fun onboardingReportHtml(report: OnboardingReport?): String {
    val body = if (report == null) {
        "<p>История онбординга не пишется: нет базы.</p>"
    } else {
        """
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
            "<p>Вернулся на следующий день: ${cohort.returnedNextDay} · ${cohort.returnedNextDayPercent}% от старта</p>"
        } else {
            ""
        }
        """
        <h3>${escapeHtml(cohort.day.toString())}</h3>
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
        $returned
        """.trimIndent()
    }
}

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
