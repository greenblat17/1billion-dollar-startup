package com.eliteteam.speakingcoach.telegram

import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.utils.row

internal fun followupKeyboard(action: String, runId: String, stage: String? = null): InlineKeyboardMarkup {
    return inlineKeyboard {
        when (action) {
            "begin", "retry", "level", "results", "resume" -> row {
                val label = when (action) {
                    "begin" -> "Let’s chat 👋"
                    "retry" -> "Retry"
                    "level" -> "🔥 See my results"
                    "resume" -> if (stage in setOf("start", "invitation", "begin")) "Let’s chat 👋"
                        else "Continue our chat 🎙"
                    else -> "See what I noticed →"
                }
                dataButton(label, if (action == "resume") "fu:resume:$runId" else "ob:$action:$runId")
            }
            "goal" -> {
                row { dataButton(PRACTICE_5_LABEL, "ob:m5:$runId") }
                row { dataButton(PRACTICE_10_LABEL, "ob:m10:$runId") }
                row { dataButton(PRACTICE_15_LABEL, "ob:m15:$runId") }
                row { dataButton(PRACTICE_SKIP_LABEL, "ob:skip:$runId") }
            }
            "practice" -> row { dataButton("🎙 Start call", "fu:practice") }
        }
    }
}
