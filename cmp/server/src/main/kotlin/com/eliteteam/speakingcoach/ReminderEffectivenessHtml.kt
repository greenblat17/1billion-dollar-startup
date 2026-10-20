package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.ai.ReminderClockSummary
import com.eliteteam.speakingcoach.analytics.NudgeReport
import com.eliteteam.speakingcoach.analytics.ReminderConversion
import com.eliteteam.speakingcoach.analytics.ReminderEffectiveness
import com.eliteteam.speakingcoach.telegram.SoftNudgeStats
import java.util.Locale

private fun conversion(part: Int, total: Int): String =
    if (total == 0) "—" else "$part / $total · ${String.format(Locale.US, "%.1f%%", part * 100.0 / total)}"

private fun errorCell(part: Int?, total: Int?): String =
    if (part == null || total == null) "—" else conversion(part, total)

private fun dailyRow(label: String, stats: ReminderConversion, onboarding: Boolean = false): String =
    "<tr><th scope=\"row\">$label</th><td>${stats.sent}</td><td>${stats.pending}</td>" +
        "<td>${conversion(if (onboarding) stats.progressed else stats.firstVoice, stats.mature)}</td>" +
        "<td>${conversion(stats.clicked, stats.mature)}</td>" +
        "<td>${conversion(stats.firstVoice, stats.mature)}</td></tr>"

private fun softRow(label: String, stats: SoftNudgeStats?): String = if (stats == null) {
    "<tr><th scope=\"row\">$label</th><td colspan=\"5\">Данные недоступны</td></tr>"
} else {
    "<tr><th scope=\"row\">$label</th><td>${stats.sent}</td><td>${stats.pending5}</td>" +
        "<td>${conversion(stats.succeeded5, stats.mature5)}</td>" +
        "<td>${conversion(stats.voice5, stats.mature5)}</td>" +
        "<td>${if (stats.kind == "call") conversion(stats.ended5, stats.mature5) else "—"}</td></tr>"
}

internal fun reminderEffectivenessHtml(
    report: ReminderEffectiveness?, soft: List<SoftNudgeStats>?,
    nudges: NudgeReport?, clock: ReminderClockSummary?,
    days: Int, root: String,
): String {
    val period = if (days == 30) 30 else 7
    val selected = listOf(7, 30).joinToString(" · ") { choice ->
        if (choice == period) "$choice дней" else "<a href=\"$root/reminders?days=$choice\">$choice дней</a>"
    }
    val softRows = softRow("Онбординг", soft?.find { it.kind == "onboarding" }
        ?: soft?.let { SoftNudgeStats("onboarding", 0, 0, 0, 0, 0, 0, 0, 0) }) +
        softRow("Открытый звонок", soft?.find { it.kind == "call" }
        ?: soft?.let { SoftNudgeStats("call", 0, 0, 0, 0, 0, 0, 0, 0) })
    val dailyRows = if (report == null) {
        "<tr><td colspan=\"6\">Новый учёт недоступен</td></tr>"
    } else dailyRow("Незавершённый онбординг", report.onboarding, onboarding = true) +
        dailyRow("Практика · время выбрал пользователь", report.configured) +
        dailyRow("Практика · время выбрал бот", report.inferred)
    val copyRows = if (report == null) "<tr><td colspan=\"5\">Данные недоступны</td></tr>" else
        listOf("configured" to report.configuredCopy, "inferred" to report.inferredCopy).joinToString("") { (source, groups) ->
            listOf("general", "personalized").joinToString("") { kind ->
                val stats = groups[kind] ?: ReminderConversion()
                val label = if (source == "configured") "Выбрано пользователем" else "Выбрано ботом"
                "<tr><th scope=\"row\">$label</th><td>${if (kind == "general") "Общий" else "Персонализированный"}</td>" +
                    "<td>${stats.sent}</td><td>${conversion(stats.firstVoice, stats.mature)}</td>" +
                    "<td>${stats.pending}</td></tr>"
            }
        }
    val savedHours = clock?.hours.orEmpty()
    val inferredHours = report?.inferredHours.orEmpty()
    val hourRows = (0..5).joinToString("") { bucket ->
        val start = bucket * 4
        val saved = (start until start + 4).sumOf { savedHours["%02d".format(it)] ?: 0 }
        val inferred = (start until start + 4).sumOf { inferredHours[it] ?: 0 }
        "<tr><th scope=\"row\">%02d:00–%02d:59</th><td>$saved</td><td>$inferred</td></tr>"
            .format(start, start + 3)
    }
    val onboardingErrors = nudges?.byStage?.filter { it.label != "practice" }?.map { it.counts }
    val onboardingAttempts = onboardingErrors?.sumOf { it.attempts }
    val onboardingBlocked = onboardingErrors?.sumOf { it.blocked }
    val onboardingFailed = onboardingErrors?.sumOf { it.failed }
    val onboardingUnknown = onboardingErrors?.sumOf { it.unknown }
    val inferredErrors = nudges?.byStage?.find { it.label == "practice" }?.counts
    val softAttempts = soft?.sumOf { it.sent + it.blocked + it.failed + it.unknown }
    val diagnostics = """
        <table><thead><tr><th>Сценарий</th><th>Попытки</th><th>Блок 403</th><th>Другая ошибка</th><th>Исход неизвестен</th></tr></thead><tbody>
        <tr><th scope="row">Мягкие</th><td>${softAttempts ?: "—"}</td>
            <td>${errorCell(soft?.sumOf { it.blocked }, softAttempts)}</td>
            <td>${errorCell(soft?.sumOf { it.failed }, softAttempts)}</td>
            <td>${errorCell(soft?.sumOf { it.unknown }, softAttempts)}</td></tr>
        <tr><th scope="row">Онбординг</th><td>${onboardingAttempts ?: "—"}</td>
            <td>${errorCell(onboardingBlocked, onboardingAttempts)}</td>
            <td>${errorCell(onboardingFailed, onboardingAttempts)}</td>
            <td>${errorCell(onboardingUnknown, onboardingAttempts)}</td></tr>
        <tr><th scope="row">Практика · выбрал пользователь</th><td>${report?.configuredDelivery?.attempts ?: "—"}</td>
            <td>${errorCell(report?.configuredDelivery?.blocked, report?.configuredDelivery?.attempts)}</td>
            <td>${errorCell(report?.configuredDelivery?.failed, report?.configuredDelivery?.attempts)}</td>
            <td>${errorCell(report?.configuredDelivery?.unknown, report?.configuredDelivery?.attempts)}</td></tr>
        <tr><th scope="row">Практика · выбрал бот</th><td>${inferredErrors?.attempts ?: "—"}</td>
            <td>${errorCell(inferredErrors?.blocked, inferredErrors?.attempts)}</td>
            <td>${errorCell(inferredErrors?.failed, inferredErrors?.attempts)}</td>
            <td>${errorCell(inferredErrors?.unknown, inferredErrors?.attempts)}</td></tr>
        </tbody></table>
    """.trimIndent()
    val configuredReasons = report?.configuredErrorReasons?.entries?.sortedByDescending { it.value }
        ?.joinToString("") { (reason, count) ->
            "<tr><td>${escapeHtml(reason)}</td><td>$count</td></tr>"
        }?.takeIf { it.isNotEmpty() }
    val reasonDetails = if (configuredReasons == null) "" else
        "<details><summary>Коды ошибок отправок с выбранным временем</summary>" +
            "<table><thead><tr><th>Код</th><th>Попытки</th></tr></thead><tbody>$configuredReasons</tbody></table></details>"
    val errorDayRows = report?.errorDays?.filter {
        it.counts.blocked + it.counts.failed + it.counts.unknown > 0
    }?.joinToString("") { day ->
        val source = when (day.source) {
            "soft_onboarding" -> "Мягкое · онбординг"
            "soft_call" -> "Мягкое · звонок"
            "onboarding" -> "Ежедневное · онбординг"
            "configured" -> "Практика · выбрал пользователь"
            "inferred" -> "Практика · выбрал бот"
            else -> day.source
        }
        "<tr><td>${escapeHtml(day.day)}</td><td>${escapeHtml(source)}</td><td>${day.counts.attempts}</td>" +
            "<td>${day.counts.blocked}</td><td>${day.counts.failed}</td><td>${day.counts.unknown}</td></tr>"
    }?.ifEmpty { "<tr><td colspan=\"6\">Ошибок за период нет</td></tr>" }
        ?: "<tr><td colspan=\"6\">Данные недоступны</td></tr>"
    val errorDays = "<details><summary>Когда возникали ошибки</summary>" +
        "<table><thead><tr><th>День</th><th>Сценарий</th><th>Попытки</th>" +
        "<th>Блок 403</th><th>Другая ошибка</th><th>Неизвестно</th></tr></thead>" +
        "<tbody>$errorDayRows</tbody></table></details>"
    return """
        <h2>Результат напоминаний</h2>
        <p>Период отправок: $selected · Europe/Moscow. Отправлено = Telegram принял сообщение, прочтение неизвестно.</p>
        <h3>Мягкие · действие за 5 минут</h3>
        <table><thead><tr><th>Сценарий</th><th>Отправлено</th><th>Наблюдаем</th>
        <th>Успех</th><th>Голос</th><th>End Call</th></tr></thead><tbody>$softRows</tbody></table>
        <p class="meta">Для звонка голос или End Call дают один общий успех. Два действия одного человека не удваивают его.</p>
        <h3>Ежедневные · действие за 24 часа</h3>
        <table><thead><tr><th>Сценарий</th><th>Отправлено</th><th>Наблюдаем</th>
        <th>Главный результат</th><th>Кнопка</th><th>Первый голос</th></tr></thead><tbody>$dailyRows</tbody></table>
        <p class="meta">Онбординг: переход дальше по воронке. Практика: первый входящий голос.
        Окно закрывается через 24 часа или при следующем ежедневном напоминании.
        Знаменатель — только отправки с закрытым окном. Категория времени и тип текста фиксируются при отправке.
        Старые отправки без нового учёта не пересчитываются.</p>
        <h3>Общий и персонализированный текст практики</h3>
        <table><thead><tr><th>Время</th><th>Текст</th><th>Отправлено</th>
        <th>Первый голос</th><th>Наблюдаем</th></tr></thead><tbody>$copyRows</tbody></table>
        <p class="meta">Типы чередуются по дням. Разница не доказывает, что текст вызвал ответ.</p>
        <h3>Распределение времени</h3>
        <table><thead><tr><th>По Москве</th><th>Выбрали сами</th><th>Рассчитал бот</th></tr></thead>
        <tbody>$hourRows</tbody></table>
        <p class="meta">Слева — текущие активные настройки. Справа — последнее рассчитанное время
        у каждого пользователя с отправкой практики за выбранный период. Это разные группы людей;
        распределения не входят в конверсию отправок.</p>
        <h3>Ошибки отправки</h3>$diagnostics$reasonDetails$errorDays
        <p class="meta">«Другая ошибка» объединяет разные причины. Подробный код доступен для отправок
        с выбранным временем; для остальных потоков исходы разделены на ошибку и неизвестный результат.</p>
    """.trimIndent()
}
