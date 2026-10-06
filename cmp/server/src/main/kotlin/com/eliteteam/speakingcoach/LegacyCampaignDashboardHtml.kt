package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.ai.LegacyCampaignStatus
import com.eliteteam.speakingcoach.telegram.LEGACY_CAMPAIGN_AFTER
import com.eliteteam.speakingcoach.telegram.LEGACY_CAMPAIGN_BEFORE
import com.eliteteam.speakingcoach.telegram.LEGACY_CAMPAIGN_BOLD

internal const val LEGACY_CAMPAIGN_PATH = "/admin/metrics/onboarding-campaign"

internal fun legacyCampaignPageHtml(
    status: LegacyCampaignStatus,
    notice: String?,
    controls: Boolean,
    summaryRoot: String = "/admin/metrics",
): String {
    val note = when (notice) {
        "started" -> "<p class=\"notice\">Отправка запущена. Обновите страницу, чтобы увидеть результаты.</p>"
        "busy" -> "<p class=\"notice warn\">Отправка уже идёт.</p>"
        "test-sent" -> "<p class=\"notice\">Тестовое сообщение отправлено.</p>"
        "test-failed" -> "<p class=\"notice warn\">Тестовое сообщение не отправлено.</p>"
        "test-invalid" -> "<p class=\"notice warn\">Укажите положительный chat ID.</p>"
        "not-ready" -> "<p class=\"notice warn\">Список адресатов ещё не зафиксирован.</p>"
        else -> ""
    }
    val readiness = if (status.ready) {
        "Аудитория зафиксирована при первой раскатке кампании. Новые пользователи в неё не попадут."
    } else {
        "Аудитория будет зафиксирована при раскатке AI-сервиса. Сейчас рассылка недоступна."
    }
    val campaignPath = "$summaryRoot/onboarding-campaign"
    val controlsHtml = if (!controls) {
        "<p class=\"meta\">Отправка доступна только в webhook-режиме.</p>"
    } else {
        val sendForm = if (status.ready && status.remaining > 0) """
            <form class="inline" method="post" action="$campaignPath/send"
              onsubmit="return confirm('Отправить сообщение ${status.remaining} пользователям из зафиксированного списка?')">
              <button type="submit">Отправить выбранным пользователям</button>
            </form>
        """.trimIndent() else ""
        """
            <form class="inline" method="post" action="$campaignPath/test">
              <label>Мой chat ID <input name="chatId" inputmode="numeric" required></label>
              <button type="submit">Отправить тест себе</button>
            </form>
            $sendForm
        """.trimIndent()
    }
    val message = escapeHtml(LEGACY_CAMPAIGN_BEFORE).replace("\n", "<br>") +
        "<b>${escapeHtml(LEGACY_CAMPAIGN_BOLD)}</b>" +
        escapeHtml(LEGACY_CAMPAIGN_AFTER).replace("\n", "<br>")
    return """
        <!doctype html>
        <html lang="ru">
        <head>
        <meta charset="utf-8">
        <meta name="robots" content="noindex">
        <title>Speaky — onboarding campaign</title>
        ${pageStyle()}
        </head>
        <body>
        <h1>Speaky</h1>
        ${adminTabs(campaignPath, summaryRoot)}
        <h2>Повторное знакомство со Speaky</h2>
        <p class="meta">$readiness</p>
        $note
        <dl>
        ${card("Зафиксировано", status.audience.toString())}
        ${card("Осталось", status.remaining.toString())}
        ${card("Исключены: прошли onboarding", status.excluded.toString())}
        ${card("Отправлено", status.sent.toString())}
        ${card("Заблокировали бота", status.blocked.toString())}
        ${card("Ошибки", status.failed.toString())}
        ${card("Неясный результат", status.uncertain.toString())}
        </dl>
        <h2>Сообщение</h2>
        <p>$message</p>
        <p><strong>Кнопка:</strong> 🎙 Пройти onboarding</p>
        $controlsHtml
        </body>
        </html>
    """.trimIndent()
}
