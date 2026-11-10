package com.eliteteam.speakingcoach.foundation

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

internal class LegacyVoiceStorage(private val database: FoundationDatabase) {
    suspend fun preferences(request: JsonObject): JsonObject = database.transaction { connection, now ->
        request.only("action", "sessionId", "args")
        val args = request.obj("args")
        val action = request.text("action")
        if (action == "rollout") {
            args.only()
            val tomorrow = now.atZone(ZoneId.of("Europe/Moscow")).toLocalDate().plusDays(1)
            connection.update("INSERT INTO legacy_speech_rollout(singleton,activation_day) VALUES (TRUE,?) ON CONFLICT DO NOTHING", tomorrow)
            return@transaction json("activationDay" to connection.rows("SELECT activation_day FROM legacy_speech_rollout") { it.getObject(1, LocalDate::class.java).toString() }.single())
        }
        val session = request.text("sessionId")
        if (session.isBlank() || session.length > 200 || session.any { it.isISOControl() }) reject("invalid_identity")
        when (action) {
            "read" -> {
                args.only()
                connection.document("SELECT jsonb_build_object('character',character,'speed',speech_speed) FROM legacy_voice_preferences WHERE session_id=?", session)
                    ?: json("character" to null, "speed" to null)
            }
            "set_character" -> {
                args.only("character")
                val character = args.text("character")
                if (character !in setOf("speaky", "kora_h")) reject("invalid_character")
                connection.update("INSERT INTO legacy_voice_preferences(session_id,character) VALUES (?,?) ON CONFLICT(session_id) DO UPDATE SET character=EXCLUDED.character", session, character)
                json("saved" to true)
            }
            "set_speed" -> {
                args.only("speed")
                val speed = args["speed"]?.jsonPrimitive?.doubleOrNull
                if (speed !in setOf(0.8, 0.9, 1.0)) reject("invalid_speed")
                connection.update("INSERT INTO legacy_voice_preferences(session_id,speech_speed) VALUES (?,?) ON CONFLICT(session_id) DO UPDATE SET speech_speed=EXCLUDED.speech_speed", session, speed)
                json("saved" to true)
            }
            else -> reject("unknown_action")
        }
    }

    suspend fun artifact(request: JsonObject): JsonObject = database.transaction { connection, now ->
        request.only("action", "attemptId", "field", "value", "receivedAt")
        val action = request.text("action")
        if (action == "prune") return@transaction json("count" to connection.update("DELETE FROM legacy_audit_artifact WHERE expires_at<=?", now))
        val attempt = request.text("attemptId")
        if (attempt.isBlank() || attempt.length > 200) reject("invalid_attempt")
        when (action) {
            "read" -> json("artifact" to (connection.document("SELECT jsonb_strip_nulls(jsonb_build_object('transcript',transcript,'reply',reply,'reply_draft',reply_draft,'conversation_plan',conversation_plan)) FROM legacy_audit_artifact WHERE attempt_id=? AND expires_at>?", attempt, now) ?: json()))
            "record" -> {
                val field = request.text("field")
                if (field !in setOf("transcript", "reply", "reply_draft", "conversation_plan")) reject("invalid_field")
                val received = request["receivedAt"]?.jsonPrimitive?.doubleOrNull?.takeIf(Double::isFinite) ?: reject("invalid_time")
                val deadline = Instant.ofEpochSecond((received + 7 * 86400).toLong())
                if (deadline > now) {
                    connection.update("""INSERT INTO legacy_audit_artifact(attempt_id,$field,expires_at) VALUES (?,?,?)
                        ON CONFLICT(attempt_id) DO UPDATE SET $field=EXCLUDED.$field,expires_at=LEAST(legacy_audit_artifact.expires_at,EXCLUDED.expires_at)""", attempt, request.text("value").take(16000), deadline)
                }
                json("saved" to (deadline > now))
            }
            else -> reject("unknown_action")
        }
    }
}
