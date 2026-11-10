package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Compatibility operations for the old voice profile; never merged with Student automatically. */
internal class LegacyLearners(private val database: FoundationDatabase) {
    suspend fun command(request: JsonObject): JsonObject = database.transaction { connection, now ->
        request.only("action", "sessionId", "args")
        val action = request.text("action")
        val args = request.obj("args")
        if (action.startsWith("trace.")) return@transaction trace(connection, action, args, now)
        if (action == "prune") {
            args.only()
            val rows = connection.documents("SELECT jsonb_build_object('sessionId',session_id,'state',onboarding) FROM legacy_learner WHERE onboarding IS NOT NULL FOR UPDATE")
            rows.forEach { row -> connection.update("UPDATE legacy_learner SET onboarding=?::jsonb WHERE session_id=?", cleanOnboarding(row.obj("state"), now), row.text("sessionId")) }
            connection.update("DELETE FROM legacy_vocabulary_trace WHERE expires_at<=?", now)
            return@transaction json("count" to rows.size)
        }
        val session = request.text("sessionId")
        if (session.isBlank() || session.length > 200 || session.any { it.isISOControl() }) reject("invalid_identity")
        connection.update("INSERT INTO legacy_learner(session_id) VALUES (?) ON CONFLICT DO NOTHING", session)
        val stored = connection.document("SELECT to_jsonb(l) FROM legacy_learner l WHERE session_id=? FOR UPDATE", session) ?: reject("not_owned")
        when (action) {
            "read" -> {
                args.only()
                val onboarding = stored["onboarding"]?.takeUnless { it == JsonNull }?.jsonObject?.let { cleanOnboarding(it, now) }
                json("onboarding" to onboarding, "assessment" to stored["assessment"], "memory" to stored["memory"], "minutes" to stored["practice_minutes"])
            }
            "save_assessment" -> {
                args.only("assessment")
                connection.update("UPDATE legacy_learner SET assessment=?::jsonb,revision=revision+1 WHERE session_id=?", args.obj("assessment"), session)
                json("saved" to true)
            }
            "save_memory" -> {
                args.only("memory", "expected", "compare")
                val compare = args["compare"] == JsonPrimitive(true)
                if (compare && stored["memory"] != (args["expected"] ?: JsonNull)) json("saved" to false)
                else {
                    connection.update("UPDATE legacy_learner SET memory=?::jsonb,revision=revision+1 WHERE session_id=?", args.obj("memory"), session)
                    json("saved" to true)
                }
            }
            "set_goal" -> {
                args.only("minutes")
                val minutes = args.number("minutes")
                if (minutes !in 1..1440) reject("invalid_goal")
                connection.update("UPDATE legacy_learner SET practice_minutes=?,revision=revision+1 WHERE session_id=?", minutes.toInt(), session)
                json("saved" to true)
            }
            else -> reject("unknown_action")
        }
    }

    private fun trace(connection: Connection, action: String, args: JsonObject, now: Instant): JsonObject {
        args.only("flow", "reviewId", "entries", "receivedAt")
        val flow = args.text("flow")
        val review = args.text("reviewId")
        if (flow !in setOf("onboarding", "call") || !review.matches(Regex("[0-9a-f]{32}"))) reject("invalid_trace")
        return when (action) {
            "trace.read" -> json("trace" to connection.document("SELECT payload FROM legacy_vocabulary_trace WHERE flow=? AND review_id=? AND expires_at>?", flow, review, now))
            "trace.save" -> {
                val received = args["receivedAt"]?.jsonArray?.mapNotNull { it.jsonPrimitive.doubleOrNull?.takeIf(Double::isFinite) } ?: emptyList()
                val deadline = received.minOrNull()?.let { Instant.ofEpochMilli((it * 1000).toLong()).plusSeconds(7 * 86400) }
                if (deadline != null && deadline > now) {
                    val payload = json("flow" to flow, "reviewId" to review, "entries" to args["entries"])
                    connection.update("""INSERT INTO legacy_vocabulary_trace(flow,review_id,payload,expires_at) VALUES (?,?,?::jsonb,?)
                        ON CONFLICT(flow,review_id) DO UPDATE SET payload=EXCLUDED.payload,expires_at=LEAST(legacy_vocabulary_trace.expires_at,EXCLUDED.expires_at)""", flow, review, payload, deadline)
                }
                json("saved" to (deadline != null && deadline > now))
            }
            else -> reject("unknown_action")
        }
    }
}

internal fun cleanOnboarding(state: JsonObject, now: Instant): JsonObject {
    val turns = state["turns"]?.jsonArray?.filter { item ->
        (item as? JsonObject)?.get("receivedAt")?.jsonPrimitive?.doubleOrNull?.let { it > now.epochSecond - 7 * 86400 } == true
    } ?: emptyList()
    val result = state.toMutableMap()
    result["turns"] = JsonArray(turns)
    if (turns.isEmpty()) {
        listOf("continueQuestion", "review", "assessment", "resultText").forEach(result::remove)
        result["question"] = JsonPrimitive("")
    }
    return JsonObject(result)
}
