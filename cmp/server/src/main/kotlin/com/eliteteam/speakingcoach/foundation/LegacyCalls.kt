package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.round

/** Legacy daily practice calls. This domain never creates a canonical Lesson or Student. */
internal class LegacyCalls(private val database: FoundationDatabase) {
    suspend fun command(request: JsonObject): JsonObject = database.transaction { connection, now ->
        request.only("action", "sessionId", "callId", "args")
        val action = request.text("action")
        val args = request.obj("args")
        if (action == "prune") {
            args.only()
            val calls = connection.documents("SELECT payload FROM legacy_call FOR UPDATE")
            calls.forEach { connection.saveLegacyCall(cleanLegacyCall(it, now)) }
            return@transaction json("result" to calls.size)
        }
        val id = request.optionalText("callId")
        val found = id?.let { connection.document("SELECT payload FROM legacy_call WHERE id=?", it) }
        if (id != null && found == null) return@transaction when (action) {
            "get" -> json("result" to null)
            "save_review" -> json("result" to false)
            else -> reject("call_not_found")
        }
        val session = found?.text("sessionId") ?: request.text("sessionId")
        if (session.isBlank() || session.length > 200 || session.any { it.isISOControl() }) reject("invalid_identity")
        if (found != null && request.optionalText("sessionId")?.let { it != session } == true) reject("not_owned")
        connection.update("INSERT INTO legacy_call_owner(session_id) VALUES (?) ON CONFLICT DO NOTHING", session)
        connection.rows("SELECT session_id FROM legacy_call_owner WHERE session_id=? FOR UPDATE", session) { it.getString(1) }
        val day = now.atZone(ZoneId.of("Europe/Moscow")).toLocalDate()
        val call = if (id != null) connection.document("SELECT payload FROM legacy_call WHERE id=?", id)?.let { cleanLegacyCall(it, now) }
            else connection.document("SELECT payload FROM legacy_call WHERE session_id=? AND status='open'", session)?.let { cleanLegacyCall(it, now) }
        val result = when (action) {
            "open" -> open(connection, session, day, call, args, now)
            "get", "open_today" -> if (action == "get" || call?.text("day") == day.toString()) call else null
            "is_open_today" -> call?.text("day") == day.toString() && (call["turns"]!!.jsonArray.isNotEmpty() || call["openingDelivered"] == JsonPrimitive(true))
            "summary" -> call?.let { connection.legacySummary(session, it) }
            "recent_ids" -> JsonArray(connection.rows("SELECT id FROM legacy_call WHERE session_id=? AND day BETWEEN ? AND ? ORDER BY day DESC,id", session, day.minusDays(7), day) { JsonPrimitive(it.getString(1)) })
            "append" -> append(connection, session, call, args, now)
            "seal" -> call?.let { close(connection, session, it, args.optionalText("reason") ?: "end_button", now); it.text("id") }
            "note_voice" -> {
                if (call == null || call.text("status") != "open") reject("invalid_state")
                val message = args.number("messageId")
                if (message <= 0) reject("invalid_message")
                val first = call.optionalText("firstTelegramVoiceId")?.toLong() ?: message
                val last = maxOf(call.optionalText("lastTelegramVoiceId")?.toLong() ?: 0, message)
                connection.saveLegacyCall(JsonObject(call + json("firstTelegramVoiceId" to first, "lastTelegramVoiceId" to last)))
                call["openingDelivered"] == JsonPrimitive(true) && first == message
            }
            "save_review" -> if (call?.text("status") != "closed") false else {
                connection.saveLegacyCall(JsonObject(call + json("review" to args.obj("review"))))
                true
            }
            "set_character", "save_opening", "replace_opening", "clear_reminder_opening", "save_spoken_opening", "mark_opening_delivered" ->
                editOpening(connection, call ?: reject("call_not_found"), action, args)
            else -> reject("unknown_action")
        }
        json("result" to result)
    }

    private fun open(connection: Connection, session: String, day: LocalDate, previous: JsonObject?, args: JsonObject, now: Instant): JsonObject {
        args.only("scenario", "speechTimeEnabled")
        var call = previous
        var closed: JsonObject? = null
        var unseen: String? = null
        if (call != null && call.text("day") != day.toString()) {
            closed = close(connection, session, call, "next_moscow_day", now)
            if (call["reviewOffered"] != JsonPrimitive(true)) {
                unseen = call.text("id")
                connection.saveLegacyCall(JsonObject(closed + json("reviewOffered" to true)))
            }
            call = null
        }
        val active = call != null && (call["turns"]!!.jsonArray.isNotEmpty() || call["openingDelivered"] == JsonPrimitive(true))
        if (call == null) {
            connection.update("INSERT INTO legacy_call_day(session_id,day) VALUES (?,?) ON CONFLICT DO NOTHING", session, day)
            val existingMethod = connection.rows("SELECT duration_method FROM legacy_call_day WHERE session_id=? AND day=?", session, day) { it.getString(1) }.single()
            val method = existingMethod ?: if (args["speechTimeEnabled"] == JsonPrimitive(true)) {
                connection.update("INSERT INTO legacy_speech_rollout(singleton,activation_day) VALUES (TRUE,?) ON CONFLICT DO NOTHING", day.plusDays(1))
                val activation = connection.rows("SELECT activation_day FROM legacy_speech_rollout") { it.getObject(1, LocalDate::class.java) }.single()
                if (day >= activation) "vad" else "recording"
            } else "recording"
            connection.update("UPDATE legacy_call_day SET duration_method=? WHERE session_id=? AND day=?", method, session, day)
            call = json("id" to newId().replace("-", ""), "sessionId" to session, "day" to day.toString(), "status" to "open",
                "startedUnix" to now.unix(), "endedUnix" to null, "seconds" to 0.0, "turns" to JsonArray(emptyList()), "openingQuestion" to null,
                "scenario" to args["scenario"], "character" to "speaky", "openingDelivered" to false, "review" to null,
                "reviewOffered" to false, "todaySeconds" to 0.0, "goalSeconds" to 0.0, "durationMethod" to method)
            connection.saveLegacyCall(call)
        } else if ("scenario" in args && !active && args["scenario"] != call["scenario"]) {
            call = JsonObject(call + json("scenario" to args["scenario"], "openingQuestion" to null, "openingDraft" to null))
            connection.saveLegacyCall(call)
        }
        return JsonObject(connection.legacySummary(session, call, unseen) + json("alreadyActive" to active,
            "closedPreviousCallId" to closed?.text("id"), "closedPreviousUnix" to closed?.get("endedUnix")))
    }

    private fun append(connection: Connection, session: String, call: JsonObject?, args: JsonObject, now: Instant): JsonObject? {
        args.only("transcript", "reply", "corrections", "seconds", "words", "recordingSeconds", "durationSource", "durationFallbackReason", "attemptId", "receivedAt")
        if (call == null) return null
        val text = args.text("transcript").trim()
        val seconds = args.double("seconds")
        if (text.isEmpty() || seconds <= 0) return connection.legacySummary(session, call)
        val turns = call["turns"]!!.jsonArray
        val attempt = args.optionalText("attemptId")
        if (attempt != null && turns.any { it.jsonObject.optionalText("attemptId") == attempt }) return connection.legacySummary(session, call)
        val before = connection.legacySeconds(session, call.text("day"))
        val turn = JsonObject(args + json("transcript" to text, "receivedAt" to (args["receivedAt"] ?: JsonPrimitive(now.unix())),
            "recordingSeconds" to (args["recordingSeconds"]?.takeUnless { it == JsonNull } ?: args["seconds"]),
            "durationSource" to (args.optionalText("durationSource") ?: "recording_legacy")))
        connection.saveLegacyCall(cleanLegacyCall(JsonObject(call + json("turns" to JsonArray(turns + turn), "seconds" to round3(call.double("seconds") + seconds))), now))
        val goal = connection.legacyGoalSeconds(session)
        val crossed = goal > 0 && before < goal && before + seconds >= goal && connection.update(
            "UPDATE legacy_call_day SET goal_noted=TRUE WHERE session_id=? AND day=?::date AND goal_noted=FALSE", session, call.text("day")) == 1
        return connection.legacySummary(session, call, crossed = crossed)
    }

    private fun close(connection: Connection, session: String, call: JsonObject, reason: String, now: Instant): JsonObject {
        val updated = JsonObject(call + json("status" to "closed", "endedUnix" to now.unix(), "closeReason" to reason,
            "todaySeconds" to connection.legacySeconds(session, call.text("day")), "goalSeconds" to connection.legacyGoalSeconds(session)))
        connection.saveLegacyCall(updated)
        return updated
    }

    private fun editOpening(connection: Connection, call: JsonObject, action: String, args: JsonObject): Any? {
        args.only("character", "question")
        if (call.text("status") != "open" && action != "mark_opening_delivered") reject("invalid_state")
        val delivered = call["openingDelivered"] == JsonPrimitive(true)
        if (delivered && action in setOf("set_character", "replace_opening", "clear_reminder_opening", "save_spoken_opening")) reject("invalid_state")
        val delta = when (action) {
            "set_character" -> {
                if (args.text("character") !in setOf("speaky", "kora_h")) reject("invalid_character")
                json("character" to args.text("character"))
            }
            "save_opening" -> if (call.optionalText("openingQuestion").isNullOrEmpty()) json("openingQuestion" to args.text("question"), "openingFromReminder" to false) else json()
            "replace_opening" -> json("openingQuestion" to args.text("question"), "openingDraft" to null, "openingFromReminder" to true)
            "clear_reminder_opening" -> if (call["openingFromReminder"] == JsonPrimitive(true)) json("openingQuestion" to null, "openingDraft" to null, "openingFromReminder" to false) else json()
            "save_spoken_opening" -> json("openingQuestion" to args.text("question"), "openingDraft" to (call["openingDraft"]?.takeUnless { it == JsonNull } ?: call["openingQuestion"]))
            "mark_opening_delivered" -> {
                if (call.optionalText("openingQuestion").isNullOrEmpty()) reject("invalid_state")
                if (delivered) return null
                json("openingDelivered" to true)
            }
            else -> reject("unknown_action")
        }
        connection.saveLegacyCall(JsonObject(call + delta))
        return if (action == "mark_opening_delivered") JsonArray(listOf(JsonPrimitive(call.text("sessionId")), JsonPrimitive(call.text("openingQuestion")))) else null
    }
}

internal fun Instant.unix(): Double = epochSecond + nano / 1_000_000_000.0
internal fun JsonObject.double(key: String): Double = get(key)?.jsonPrimitive?.doubleOrNull?.takeIf(Double::isFinite) ?: reject("invalid_$key")
private fun round3(value: Double): Double = round(value * 1000) / 1000
private fun Connection.saveLegacyCall(call: JsonObject) {
    update("""INSERT INTO legacy_call(id,session_id,day,status,payload) VALUES (?,?,?::date,?,?::jsonb)
        ON CONFLICT(id) DO UPDATE SET status=EXCLUDED.status,payload=EXCLUDED.payload""", call.text("id"), call.text("sessionId"), call.text("day"), call.text("status"), call)
}
private fun Connection.legacySeconds(session: String, day: String): Double = rows(
    "SELECT COALESCE(sum((payload->>'seconds')::double precision),0) FROM legacy_call WHERE session_id=? AND day=?::date", session, day) { round3(it.getDouble(1)) }.single()
private fun Connection.legacyGoalSeconds(session: String): Double = rows("SELECT practice_minutes FROM legacy_learner WHERE session_id=?", session) {
    it.getInt(1).takeIf { value -> value in setOf(5, 10, 15) }?.times(60.0) ?: 0.0
}.singleOrNull() ?: 0.0
private fun Connection.legacySummary(session: String, call: JsonObject, unseen: String? = null, crossed: Boolean = false): JsonObject = json(
    "callId" to call.text("id"), "character" to call["character"], "startedUnix" to call["startedUnix"],
    "todaySeconds" to legacySeconds(session, call.text("day")), "goalSeconds" to legacyGoalSeconds(session),
    "goalJustCrossed" to crossed, "durationMethod" to (call.optionalText("durationMethod") ?: "recording"), "unseenCallId" to unseen)
internal fun cleanLegacyCall(call: JsonObject, now: Instant): JsonObject {
    val cutoff = now.unix() - 7 * 86400
    val turns = call["turns"]?.jsonArray?.filter { (it as? JsonObject)?.get("receivedAt")?.jsonPrimitive?.doubleOrNull?.let { value -> value > cutoff } == true } ?: emptyList()
    val result = call.toMutableMap()
    result["turns"] = JsonArray(turns)
    if ((call["startedUnix"]?.jsonPrimitive?.doubleOrNull ?: 0.0) <= cutoff) {
        listOf("openingQuestion", "openingDraft", "scenario").forEach { result[it] = JsonNull }
    }
    if (turns.isEmpty()) result["review"] = JsonNull
    return JsonObject(result)
}
