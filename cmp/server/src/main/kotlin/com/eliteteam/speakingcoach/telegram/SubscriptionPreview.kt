package com.eliteteam.speakingcoach.telegram

import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.utils.row

internal enum class SubscriptionPreviewPlan(val callback: String, val label: String) {
    MONTH("sub:month", "1 month · 300 ₽"),
    THREE_MONTHS("sub:three_months", "3 months · 500 ₽"),
    YEAR("sub:year", "1 year · 1 700 ₽"),
}

internal fun subscriptionPreviewPlan(data: String): SubscriptionPreviewPlan? =
    SubscriptionPreviewPlan.entries.firstOrNull { it.callback == data }

internal fun subscriptionPreviewText(selected: SubscriptionPreviewPlan? = null): String = buildString {
    append("🔒 Subscription plans\n\n")
    append("After your first conversation, continued practice will be available with a subscription:\n\n")
    SubscriptionPreviewPlan.entries.forEach { append("• ${it.label}\n") }
    if (selected != null) append("\nSelected: ${selected.label}\n")
    append("\nCheckout is coming soon. For now, you can keep practicing for free.")
}

internal fun subscriptionPreviewKeyboard(): InlineKeyboardMarkup = inlineKeyboard {
    SubscriptionPreviewPlan.entries.forEach { plan ->
        row { dataButton(plan.label, plan.callback) }
    }
}
