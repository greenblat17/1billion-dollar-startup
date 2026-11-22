package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.foundation.FoundationFailure
import com.eliteteam.speakingcoach.foundation.FoundationService
import com.eliteteam.speakingcoach.foundation.MockPaymentAdapter
import com.eliteteam.speakingcoach.foundation.json
import com.eliteteam.speakingcoach.foundation.optionalText
import com.eliteteam.speakingcoach.foundation.text
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.requests.abstracts.SimpleRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray

internal fun mockPaymentMarkup(id: String) = json("inline_keyboard" to buildJsonArray {
    add(buildJsonArray { add(json("text" to "Тест: оплатить и включить автопродление", "callback_data" to "sp05:ok:$id")) })
    add(buildJsonArray { add(json("text" to "Тест: платёж отклонён", "callback_data" to "sp05:fail:$id")) })
})

@Serializable
internal data class BillingCallbackAnswer(@SerialName("callback_query_id") val id: String, val text: String,
    @SerialName("show_alert") val alert: Boolean = true) : SimpleRequest<JsonElement> {
    override fun method() = "answerCallbackQuery"
    override val requestSerializer get() = serializer()
    override val resultDeserializer get() = JsonElement.serializer()
}

/** Called synchronously before webhook ACK, only after Telegram webhook authentication. */
internal class MockBillingTelegram(private val service: FoundationService, private val bot: BehaviourContext) {
    suspend fun handle(envelope: JsonObject): Boolean {
        val callback = envelope["callback_query"] as? JsonObject ?: return false
        val data = callback.optionalText("data") ?: return false
        if (!data.startsWith("sp05:")) return false
        val parts = data.split(':')
        val from = callback["from"] as? JsonObject
        val message = callback["message"] as? JsonObject
        val chat = message?.get("chat") as? JsonObject
        val subject = from?.optionalText("id")
        if (parts.size != 3 || parts[1] !in setOf("ok", "fail") || chat?.optionalText("type") != "private" || subject != chat.optionalText("id")) {
            bot.execute(BillingCallbackAnswer(callback.text("id"), "Этот тестовый платёж недоступен."))
            return true
        }
        val reply = try {
            val result = MockPaymentAdapter(service).callback(requireNotNull(subject), parts[2], parts[1] == "ok")
            if (parts[1] == "ok") {
                val subscription = result["subscription"] as? JsonObject
                "Тестовая подписка активна до ${subscription?.optionalText("valid_until")}. Автопродление включено. Деньги не списаны."
            } else "Тестовый платёж отклонён. Подписка не активирована. Попросите Сашу отправить оплату заново."
        } catch (error: FoundationFailure) {
            "Этот тестовый платёж недоступен или уже обработан. Уточните статус у Саши."
        }
        bot.execute(BillingCallbackAnswer(callback.text("id"), reply))
        return true
    }
}
