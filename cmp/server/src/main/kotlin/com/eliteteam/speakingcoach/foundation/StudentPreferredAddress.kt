package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/** A learner-selected way to address them, shared by Telegram and voice. */
internal class StudentPreferredAddress {
    fun save(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "text", "voice")
        if (scope.role == "voice") {
            args.only("lessonId", "callSessionId", "sourceId", "value", "evidence")
            c.activeTrial(scope, args, now)
        } else {
            args.only("sourceId", "value", "evidence")
        }
        val source = sourceMessage(c, scope, args.text("sourceId"), now)
        val value = StudentLearningModel.requiredString(args["value"], 100).trim()
        val quote = StudentLearningModel.requiredString(args["evidence"], 2000)
        if (value.isEmpty() || value.any(Char::isISOControl) ||
            !quote.contains(value, ignoreCase = true) || !source.text("body").contains(quote)) reject("invalid_evidence")

        val old = c.document("SELECT profile->'preferredAddress' FROM student WHERE id=? AND profile->'preferredAddress' IS NOT NULL",
            scope.studentId)
        val occurred = Instant.parse(source.text("occurred_at"))
        val previousAt = old?.optionalText("sourceOccurredAt")?.let(Instant::parse)
        if (previousAt != null && (previousAt > occurred ||
                (previousAt == occurred && old.optionalText("sourceConversationId") == source.text("conversation_id") &&
                    old.number("sourceSequence") > source.number("sequence")))) reject("stale_source")
        if (old?.optionalText("sourceEventId") == source.text("id") && old.optionalText("value") == value) return old
        val saved = json("value" to value, "source" to if (scope.role == "text") "telegram_explicit" else "voice_explicit",
            "sourceEventId" to source.text("id"), "sourceSequence" to source.number("sequence"),
            "sourceConversationId" to source.text("conversation_id"), "sourceOccurredAt" to occurred.toString())
        c.update("UPDATE student SET profile=profile || ?::jsonb,version=version+1 WHERE id=?",
            json("preferredAddress" to saved), scope.studentId)
        return saved
    }
}
