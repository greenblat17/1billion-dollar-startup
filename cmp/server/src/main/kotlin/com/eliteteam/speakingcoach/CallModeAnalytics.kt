package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.analytics.CallEvent
import kotlin.math.ceil

internal val callModes = listOf("free", "job", "manager", "custom", "unknown")

internal fun callModeLabel(mode: String): String = when (mode) {
    "free" -> "Обычный разговор"
    "job" -> "Job Interview"
    "manager" -> "Talk to Your Manager"
    "custom" -> "Custom Scenario"
    else -> "Неизвестно"
}

internal data class CallModeStats(
    val mode: String,
    val users: Int,
    val calls: Int,
    val repeatUsers: Int,
    val dialogues: Int,
    val voiceTurns: Int,
    val speechSeconds: Double,
    val speechSample: List<Double>,
    val audioReplies: Int,
    val failedCalls: Int,
    val deliveredCorrections: Int,
    val subtitleClicks: Int,
    val deliveredReviews: Int,
)

internal fun callModeStats(rows: List<CallDashboardRow>): List<CallModeStats> = callModes.map { mode ->
    val group = rows.filter { it.mode == mode }
    val chats = group.groupingBy { it.opened.chatId }.eachCount()
    CallModeStats(
        mode = mode,
        users = chats.size,
        calls = group.size,
        repeatUsers = chats.count { it.value > 1 },
        dialogues = group.count { it.firstVoice != null },
        voiceTurns = group.sumOf { it.voices.size },
        speechSeconds = group.sumOf { it.recognizedSeconds },
        speechSample = group.filter { it.firstVoice != null }.map { it.recognizedSeconds },
        audioReplies = group.sumOf { it.audioReplies },
        failedCalls = group.count { it.failed },
        deliveredCorrections = group.sumOf { row -> row.cards.count { it.state == "delivered" } },
        subtitleClicks = group.sumOf { it.subtitles.size },
        deliveredReviews = group.count { it.reviewDelivered },
    )
}

internal data class ScenarioChoiceStats(
    val kind: String,
    val selections: Int,
    val users: Int,
    val validDescriptions: Int,
    val callsOpened: Int,
    val startersDelivered: Int,
    val dialogues: Int,
)

internal data class ScenarioPathStats(
    val menus: Int,
    val menuUsers: Int,
    val menusWithSelection: Int,
    val backs: Int,
    val invalidDescriptions: Int,
    val choices: List<ScenarioChoiceStats>,
)

/** Counts a selection only when its own message (or custom description) opened the call. */
internal fun scenarioPathStats(events: List<CallEvent>, rows: List<CallDashboardRow>): ScenarioPathStats {
    val menus = events.filter { it.kind == "scenario_menu_opened" }.distinctBy { it.id }
    val selections = events.filter { it.kind == "scenario_selected" && it.state in setOf("job", "manager", "custom") }
        .distinctBy { it.id }
    val descriptions = events.filter { it.kind == "custom_description_submitted" }.distinctBy { it.id }
    val descriptionByMenu = descriptions.associateBy { it.chatId to it.botMessageId }
    val starts = events.filter { it.kind == "start_pressed" }.distinctBy { it.id }
        .associateBy { it.chatId to it.messageId }
    val calls = rows.associateBy { it.callId }
    val choices = listOf("job", "manager", "custom").map { kind ->
        val selected = selections.filter { it.state == kind }
        val valid = selected.associateWith { choice ->
            descriptionByMenu[choice.chatId to choice.botMessageId]
        }
        val opened = selected.mapNotNull { choice ->
            val startMessageId = if (kind == "custom") valid[choice]?.messageId else choice.botMessageId
            val start = starts[choice.chatId to startMessageId]
            calls[start?.callId]?.takeIf { row ->
                row.mode == kind && row.opened.chatId == choice.chatId && row.opened.messageId == startMessageId
            }
        }
        ScenarioChoiceStats(
            kind = kind,
            selections = selected.size,
            users = selected.map { it.chatId }.distinct().size,
            validDescriptions = if (kind == "custom") valid.values.count { it != null } else 0,
            callsOpened = opened.size,
            startersDelivered = opened.count { row -> row.events.any { it.kind == "starter_delivered" } },
            dialogues = opened.count { it.firstVoice != null },
        )
    }
    val menuKeys = menus.map { it.chatId to it.botMessageId }.toSet()
    val selectedMenuKeys = selections.map { it.chatId to it.botMessageId }.toSet()
    return ScenarioPathStats(
        menus = menus.size,
        menuUsers = menus.map { it.chatId }.distinct().size,
        menusWithSelection = menuKeys.intersect(selectedMenuKeys).size,
        backs = events.count { it.kind == "scenario_back" },
        invalidDescriptions = events.count { it.kind == "custom_description_invalid" },
        choices = choices,
    )
}

internal fun speechPercentiles(values: List<Double>): String {
    if (values.isEmpty()) return "— (n=0)"
    val sorted = values.sorted()
    fun pick(p: Double) = sorted[(ceil(sorted.size * p).toInt() - 1).coerceIn(0, sorted.lastIndex)]
    return "${"%.1f".format(java.util.Locale.ROOT, pick(0.5))} / ${"%.1f".format(java.util.Locale.ROOT, pick(0.95))} с (n=${values.size})"
}
