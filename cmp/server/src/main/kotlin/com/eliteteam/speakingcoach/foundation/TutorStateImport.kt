package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Offline, frozen-source import. Not registered as a model tool or ordinary HTTP operation. */
internal class TutorStateImport(private val service: FoundationService) {
    suspend fun import(subject: String, state: JsonObject): JsonObject {
        val legacy = state.text("learnerId")
        if (legacy !in setOf("tg-$subject", "tg-ChatId(chatId=$subject)")) reject("import_identity_conflict")
        val student = service.identity.resolve("telegram", subject)
        return service.database.transaction { connection, now ->
            connection.lockStudent(student)
            val sourceId = "tutor:v1:$legacy"
            val hash = digest(canonical(state))
            val receipt = connection.document("SELECT to_jsonb(i) FROM foundation_import i WHERE source_id=?", sourceId)
            if (receipt != null) {
                if (receipt.text("content_digest") != hash) reject("import_source_changed")
                return@transaction receipt.obj("target_refs")
            }
            if (connection.document("SELECT to_jsonb(c) FROM tutor_conversation c WHERE student_id=? LIMIT 1", student) != null ||
                connection.document("SELECT to_jsonb(l) FROM lesson l WHERE student_id=? LIMIT 1", student) != null ||
                connection.document("SELECT to_jsonb(f) FROM memory_fact f WHERE student_id=? LIMIT 1", student) != null) reject("import_target_not_empty")
            val conversation = state.text("conversationId")
            if (conversation.isBlank() || conversation.length > 200) reject("invalid_conversation")
            connection.update("INSERT INTO tutor_conversation(id,student_id,channel) VALUES (?,?,'telegram')", conversation, student)
            connection.update("UPDATE student SET profile=?::jsonb,version=version+1 WHERE id=?",
                json("trialPreference" to (state.optionalText("trialPreference") ?: "unknown"),
                    "introduction" to (state.optionalText("introduction") ?: "getting_acquainted")), student)
            val bookings = state["bookings"]?.jsonArray ?: reject("import_bookings_missing")
            if (bookings.count { it.jsonObject.text("status") == "scheduled" } > 1) reject("import_multiple_active_bookings")
            bookings.forEach { importBooking(connection, student, legacy, it.jsonObject) }
            bookings.forEach { item ->
                val booking = item.jsonObject
                booking.optionalText("previousBookingId")?.let { previous ->
                    connection.update("UPDATE lesson SET previous_lesson_id=? WHERE student_id=? AND id=?", previous, student, booking.text("id"))
                }
            }
            val sources = mutableMapOf<String, String>()
            val requests = state["requests"]?.jsonObject ?: json()
            val receipts = state["receipts"]?.jsonObject ?: json()
            val inputs = (requests.keys + receipts.keys).associateWith { id -> (requests[id] ?: receipts[id])!!.jsonObject }
            (state["history"]?.jsonArray ?: JsonArray(emptyList())).forEachIndexed { index, item ->
                val entry = item.jsonObject
                val at = entry.legacyInstant("at")
                if (at.plusSeconds(7 * 86400) > now) {
                    val match = if (entry.text("role") == "user") inputs.entries.firstOrNull { (_, input) ->
                        input.legacyInstant("at") == at && (input.text("text").ifEmpty { "/start" }) == entry.text("content")
                    }?.key else null
                    val event = importEvent(connection, student, conversation, match ?: "legacy-history:$index",
                        entry.text("role"), entry.text("content"), at, now)
                    if (match != null) sources[match] = event
                }
            }
            inputs.entries.sortedBy { it.value.legacyInstant("at") }.forEach { (request, input) ->
                val at = input.legacyInstant("at")
                val body = input.text("text")
                val start = input["start"] == JsonPrimitive(true)
                val source = sources[request] ?: importEvent(connection, student, conversation, request, "user", body, at, now)
                sources[request] = source
                val oldReceipt = receipts[request]?.jsonObject
                val response = oldReceipt?.obj("response")?.let { old ->
                    val booking = old["booking"]?.takeUnless { it == JsonNull }?.jsonObject?.let { prior ->
                        connection.document("SELECT to_jsonb(l) FROM lesson l WHERE student_id=? AND id=?", student, prior.text("id")) ?: reject("import_booking_missing")
                    }
                    json("conversationId" to conversation, "reply" to old.text("reply"), "booking" to booking,
                        "degraded" to (old["degraded"] ?: JsonPrimitive(false)))
                }
                val fingerprint = digest(canonical(json("text" to body, "start" to start, "occurredAt" to at.toString())))
                connection.update("""INSERT INTO tutor_inbox(student_id,request_id,event_id,input_digest,status,response,expires_at,created_at)
                    VALUES (?,?,?,?,?,?::jsonb,?,?)""", student, request, source, fingerprint,
                    if (response != null) "ready" else "pending", if (at.plusSeconds(7 * 86400) > now) response else null, at.plusSeconds(7 * 86400), at)
                oldReceipt?.optionalText("replyMessageId")?.toLong()?.let { message ->
                    connection.update("INSERT INTO tutor_delivery(student_id,request_id,kind,outcome,message_id) VALUES (?,?,'reply','sent',?)", student, request, message)
                }
            }
            val evidenceConversation = connection.conversation(student, "import-evidence")
            listOf("personal", "learning").forEach { category ->
                val facts = state[category]?.jsonObject ?: json()
                if (category == "learning" && facts["goal"] != null && facts["goals"] != null && facts["goal"] != facts["goals"]) reject("import_goal_conflict")
                facts.forEach { (oldKey, raw) ->
                    if (category == "learning" && oldKey == "goals" && facts["goal"] != null) return@forEach
                    val fact = raw.jsonObject
                    val key = if (category == "learning" && oldKey == "goals") "goal" else oldKey
                    if (fact.text("kind") !in setOf("user_report", "self_assessment")) reject("import_unconfirmed_fact")
                    val proof = fact.obj("source")
                    val at = proof.legacyInstant("at")
                    if (inputs[proof.text("requestId")]?.text("text")?.contains(proof.text("text")) == false) reject("import_evidence_conflict")
                    val source = sources[proof.text("requestId")] ?: importEvent(connection, student, evidenceConversation,
                        "legacy-fact:$category:$key", "user", proof.text("text"), at, now)
                    if (category == "learning" && key == "goal") {
                        connection.update("INSERT INTO learning_goal(student_id,revision,content,source_event_id,created_at) VALUES (?,1,?::jsonb,?,?)",
                            student, json("value" to fact["value"]), source, at)
                    } else connection.update("INSERT INTO memory_fact(student_id,category,fact_key,value,kind,source_event_id,updated_at) VALUES (?,?,?,?,?,?,?)",
                        student, category, key, fact.optionalText("value"), fact.text("kind"), source, at)
                }
            }
            importOperations(connection, student, state, now)
            (state["cards"]?.jsonObject ?: json()).forEach { (lesson, raw) ->
                val card = raw.jsonObject
                connection.update("INSERT INTO lesson_telegram_card(student_id,lesson_id,message_id,lesson_version) VALUES (?,?,?,?)",
                    student, lesson, card.number("messageId"), card.number("version"))
            }
            val summaryAt = state["summaryAt"]?.takeUnless { it == JsonNull }?.let { state.legacyInstant("summaryAt") }
            if (!state.optionalText("summary").isNullOrBlank() && summaryAt != null && summaryAt.plusSeconds(7 * 86400) > now) {
                connection.update("INSERT INTO tutor_summary(student_id,body,expires_at) VALUES (?,?,?)", student, state.text("summary"), summaryAt.plusSeconds(7 * 86400))
            }
            val result = json("studentId" to student, "conversationId" to conversation, "bookings" to bookings.size, "sourceRevision" to state.number("revision"))
            connection.update("INSERT INTO foundation_import(source_id,content_digest,target_refs) VALUES (?,?,?::jsonb)", sourceId, hash, result)
            result
        }
    }

    private fun importBooking(connection: Connection, student: String, legacy: String, booking: JsonObject) {
        if (booking.text("learnerId") != legacy || booking.text("kind") != "trial" || booking.text("status") !in setOf("scheduled", "cancelled") || booking.number("durationMinutes") != 15L) reject("import_invalid_booking")
        ZoneId.of(booking.text("timezone"))
        connection.update("""INSERT INTO lesson(id,student_id,kind,status,version,starts_at,duration_minutes,timezone,launch_mode,timing,created_at,updated_at)
            VALUES (?,?,'trial',?,?,?,15,?,'placeholder',?,?,?)""", booking.text("id"), student, booking.text("status"), booking.number("version"),
            Instant.parse(booking.text("startsAtUtc")), booking.text("timezone"), booking.optionalText("timing") ?: "scheduled",
            booking.legacyInstant("createdAt"), booking.legacyInstant("updatedAt"))
    }

    private fun importOperations(connection: Connection, student: String, state: JsonObject, now: Instant) {
        val actions = mapOf("update_learner_context" to "tutor.update_context", "create_trial_booking" to "lesson.create", "reschedule_trial_booking" to "lesson.reschedule", "cancel_trial_booking" to "lesson.cancel")
        (state["operations"]?.jsonObject ?: json()).forEach { (id, raw) ->
            val operation = raw.jsonObject
            val at = operation.legacyInstant("at")
            val action = actions[operation.text("tool")] ?: reject("import_unknown_operation")
            val result = if (action.startsWith("lesson.")) {
                val booking = operation.obj("result").obj("booking")
                connection.document("SELECT to_jsonb(l) FROM lesson l WHERE student_id=? AND id=?", student, booking.text("id")) ?: reject("import_booking_missing")
            } else json("ok" to true)
            // Original resolved arguments were not stored. Read/recovery is allowed; replay as a new command is rejected.
            connection.update("""INSERT INTO foundation_operation(student_id,operation_id,action,request_id,role,input_digest,result,created_at,expires_at)
                VALUES (?,?,?,?,'text',?,?::jsonb,?,?)""", student, id, action, operation.text("requestId"), "imported:$id",
                if (at.plusSeconds(7 * 86400) > now) result else json(), at, at.plusSeconds(7 * 86400))
        }
    }
}

private fun JsonObject.legacyInstant(key: String): Instant = Instant.ofEpochMilli((double(key) * 1000).toLong())
private fun importEvent(connection: Connection, student: String, conversation: String, source: String, role: String, body: String, at: Instant, now: Instant): String {
    if (role !in setOf("user", "assistant")) reject("import_invalid_role")
    val event = connection.appendEvent(student, conversation, source, role, body, at, at)
    val deadline = at.plusSeconds(7 * 86400)
    connection.update("UPDATE conversation_event SET expires_at=?,body=CASE WHEN ?>? THEN body ELSE NULL END WHERE id=?", deadline, deadline, now, event.text("id"))
    return event.text("id")
}
