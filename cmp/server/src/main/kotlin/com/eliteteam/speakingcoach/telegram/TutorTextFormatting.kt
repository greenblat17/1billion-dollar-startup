package com.eliteteam.speakingcoach.telegram

import dev.inmo.tgbotapi.requests.abstracts.SimpleRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Telegram optional fields must be absent, not JSON null. Separate from storage encoding. */
internal val telegramRequestJson = Json { encodeDefaults = false; explicitNulls = false }

@Serializable
internal data class TutorTextEntity(val type: String, val offset: Int, val length: Int)

internal data class TutorTextPart(val text: String, val entities: List<TutorTextEntity>)

private val introductionParagraph = Regex("\\r?\\n(?:[ \\t]*\\r?\\n)+")
private const val TELEGRAM_TEXT_LIMIT = 4096

/** Interpret up to two blank-line breaks chosen by the model for an introduction reply. */
internal fun introductionTextParts(source: String): List<TutorTextPart> {
    val paragraphs = introductionParagraph.split(source.trim(), limit = 3)
    val parts = paragraphs.flatMap { tutorTextParts(it.trim(), limit = TELEGRAM_TEXT_LIMIT) }
    if (parts.size <= 3) return parts
    // An uneven paragraph split may overflow; the stored reply is bounded to 12,000 UTF-16 units.
    return tutorTextParts(source, limit = TELEGRAM_TEXT_LIMIT)
}

/** Small, explicit Markdown subset. Unrecognised markup stays literal; no Telegram parse_mode. */
internal fun tutorTextParts(source: String, limit: Int = 3500): List<TutorTextPart> {
    require(limit >= 2)
    val text = StringBuilder()
    val entities = mutableListOf<TutorTextEntity>()
    val tokens = Regex("```([\\s\\S]*?)```|`([^`\\n]+)`|(?<![\\\\*])\\*\\*([^*\\n]+)\\*\\*(?!\\*)")
    var cursor = 0
    for (match in tokens.findAll(source)) {
        text.append(source.substring(cursor, match.range.first))
        val group = (1..3).first { match.groups[it] != null }
        val content = match.groupValues[group]
        if (content.isNotEmpty()) entities += TutorTextEntity(
            when (group) { 1 -> "pre"; 2 -> "code"; else -> "bold" }, text.length, content.length,
        )
        text.append(content)
        cursor = match.range.last + 1
    }
    text.append(source.substring(cursor))
    val parts = mutableListOf<TutorTextPart>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + limit, text.length)
        // Telegram offsets use UTF-16, as Kotlin does; never cut an emoji's surrogate pair.
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        val clipped = entities.mapNotNull { entity ->
            val from = maxOf(start, entity.offset)
            val to = minOf(end, entity.offset + entity.length)
            if (from < to) entity.copy(offset = from - start, length = to - from) else null
        }
        parts += TutorTextPart(text.substring(start, end), clipped)
        start = end
    }
    return parts
}

@Serializable
internal data class TutorReplyParameters(
    @SerialName("message_id") val messageId: Long,
    @SerialName("allow_sending_without_reply") val allowSendingWithoutReply: Boolean,
)

@Serializable
internal data class TutorTextRequest(
    @SerialName("chat_id") val chatId: Long,
    val text: String,
    val entities: List<TutorTextEntity>,
    @SerialName("reply_markup") val markup: kotlinx.serialization.json.JsonObject?,
    @SerialName("reply_parameters") val reply: TutorReplyParameters? = null,
) : SimpleRequest<JsonElement> {
    override fun method() = "sendMessage"
    override val requestSerializer get() = serializer()
    override val resultDeserializer get() = JsonElement.serializer()
}
