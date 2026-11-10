package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal class LegacyDialogue(private val database: FoundationDatabase) {
    suspend fun command(request: JsonObject): JsonObject = database.transaction { connection, now ->
        request.only("action", "sessionId", "args")
        val args = request.obj("args")
        val action = request.text("action")
        if (action == "prune") {
            connection.update("DELETE FROM legacy_dialogue_turn WHERE expires_at<=?", now)
            connection.update("DELETE FROM legacy_dialogue WHERE expires_at<=?", now)
            val rows = connection.documents("SELECT to_jsonb(d) FROM legacy_dialogue d FOR UPDATE")
            rows.forEach { connection.update("UPDATE legacy_dialogue SET messages=?::jsonb WHERE session_id=?", cleanDialogue(it["messages"]!!.jsonArray, now), it.text("session_id")) }
            return@transaction json("count" to rows.size)
        }
        val base = request.text("sessionId").trim()
        if (base.isEmpty() || base.length > 200) reject("invalid_identity")
        val shared = action in setOf("shared_history", "record_shared")
        val session = if (shared) "$base:free:v1" else base
        val ttl = (args.optionalText("ttlSeconds")?.toLong() ?: 2592000).coerceIn(1, 365 * 86400)
        val limit = (args.optionalText("maxMessages")?.toInt() ?: 40).coerceIn(1, 1000)
        // This advisory lock also orders create/expiry/recreation when no row exists yet.
        connection.rows("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "dialogue:$session") { Unit }
        var row = connection.document("SELECT to_jsonb(d) FROM legacy_dialogue d WHERE session_id=? FOR UPDATE", session)
        if (row != null && Instant.parse(row.text("expires_at")) <= now) {
            connection.update("DELETE FROM legacy_dialogue WHERE session_id=?", session)
            row = null
        }
        if (shared && row == null) {
            val originals = connection.documents("SELECT to_jsonb(d) FROM legacy_dialogue d WHERE session_id IN (?,?) AND expires_at> ? ORDER BY session_id",
                base, "$base:kora_h", now)
            val messages = originals.flatMap { cleanDialogue(it["messages"]!!.jsonArray, now).map { item -> item.jsonObject } }
                .filter { it.double("at") <= now.unix() }.distinctBy { Triple(it.double("at"), it.text("role"), it.text("content")) }
                .sortedBy { it.double("at") }.takeLast(limit)
            connection.update("INSERT INTO legacy_dialogue(session_id,messages,expires_at) VALUES (?,?::jsonb,?)", session, JsonArray(messages), now.plusSeconds(ttl))
            connection.update("INSERT INTO legacy_dialogue_rollout(singleton,dual_write_until) VALUES (TRUE,?) ON CONFLICT DO NOTHING", now.plusSeconds(7 * 86400))
            row = connection.document("SELECT to_jsonb(d) FROM legacy_dialogue d WHERE session_id=?", session)
        }
        when (action) {
            "exists" -> json("exists" to (row != null))
            "history", "shared_history" -> json("messages" to (row?.get("messages")?.jsonArray?.let { cleanDialogue(it, now) } ?: JsonArray(emptyList())))
            "create" -> {
                connection.update("INSERT INTO legacy_dialogue(session_id,expires_at) VALUES (?,?) ON CONFLICT(session_id) DO UPDATE SET expires_at=EXCLUDED.expires_at", session, now.plusSeconds(ttl))
                json("sessionId" to session)
            }
            "claim" -> {
                if (row?.optionalText("lease_until")?.let { Instant.parse(it) > now } == true) reject("conversation_busy")
                connection.update("INSERT INTO legacy_dialogue(session_id,expires_at) VALUES (?,?) ON CONFLICT DO NOTHING", session, now.plusSeconds(ttl))
                connection.update("UPDATE legacy_dialogue SET lease_until=?,fence=fence+1 WHERE session_id=?", now.plusSeconds(90), session)
                val claimed = connection.document("SELECT to_jsonb(d) FROM legacy_dialogue d WHERE session_id=?", session) ?: reject("invalid_state")
                json("messages" to cleanDialogue(claimed["messages"]!!.jsonArray, now), "fence" to claimed.number("fence"), "revision" to claimed.number("revision"), "generation" to claimed.text("generation"))
            }
            "release" -> {
                connection.update("UPDATE legacy_dialogue SET lease_until=NULL WHERE session_id=? AND fence=? AND generation=?", session, args.number("fence"), args.text("generation"))
                json("released" to true)
            }
            "record", "record_shared" -> {
                val changed = record(connection, session, row, args, ttl, limit, now, chronological = shared)
                if (shared && changed) {
                    val personality = args.text("personality")
                    if (personality !in setOf("speaky", "kora_h")) reject("invalid_character")
                    val until = connection.rows("SELECT dual_write_until FROM legacy_dialogue_rollout") { it.getTimestamp(1).toInstant() }.single()
                    if (now < until) {
                        val mirror = if (personality == "speaky") base else "$base:kora_h"
                        connection.rows("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "dialogue:$mirror") { Unit }
                        var old = connection.document("SELECT to_jsonb(d) FROM legacy_dialogue d WHERE session_id=? FOR UPDATE", mirror)
                        if (old != null && Instant.parse(old.text("expires_at")) <= now) {
                            connection.update("DELETE FROM legacy_dialogue WHERE session_id=?", mirror)
                            old = null
                        }
                        record(connection, mirror, old, args, ttl, limit, now)
                    }
                }
                json("recorded" to true)
            }
            else -> reject("unknown_action")
        }
    }

    private fun record(connection: Connection, session: String, row: JsonObject?, args: JsonObject, ttl: Long, limit: Int, now: Instant, chronological: Boolean = false): Boolean {
        args.only("userText", "reply", "turnId", "at", "fence", "revision", "generation", "personality", "ttlSeconds", "maxMessages")
        val turn = args.text("turnId")
        val hash = digest(canonical(json("userText" to args.text("userText"), "reply" to args.text("reply"), "personality" to args["personality"])))
        val receipt = connection.document("SELECT to_jsonb(t) FROM legacy_dialogue_turn t WHERE session_id=? AND turn_id=? AND expires_at>?", session, turn, now)
        if (receipt != null) {
            if (receipt.text("input_digest") != hash) reject("operation_conflict")
            return false
        }
        val fence = args.optionalText("fence")?.toLong()
        if (fence != null) {
            if (row == null || row.text("generation") != args.text("generation") || row.number("fence") != fence || row.number("revision") != args.number("revision") || row.optionalText("lease_until")?.let { Instant.parse(it) > now } != true) reject("stale_run")
        } else if (row?.optionalText("lease_until")?.let { Instant.parse(it) > now } == true) reject("conversation_busy")
        val at = args.double("at")
        val history = row?.get("messages")?.jsonArray?.let { cleanDialogue(it, now) } ?: JsonArray(emptyList())
        val pair = listOf(json("role" to "user", "content" to args.text("userText"), "at" to at), json("role" to "assistant", "content" to args.text("reply"), "at" to at))
        val current = cleanDialogue(JsonArray(history + pair), now).map { it.jsonObject }
        val combined = (if (chronological) current.sortedBy { it.double("at") } else current).takeLast(limit)
        connection.update("""INSERT INTO legacy_dialogue(session_id,messages,expires_at,revision) VALUES (?,?::jsonb,?,1)
            ON CONFLICT(session_id) DO UPDATE SET messages=EXCLUDED.messages,expires_at=EXCLUDED.expires_at,revision=legacy_dialogue.revision+1,lease_until=NULL""", session, JsonArray(combined), now.plusSeconds(ttl))
        connection.update("""INSERT INTO legacy_dialogue_turn(session_id,turn_id,input_digest,expires_at) VALUES (?,?,?,?)
            ON CONFLICT(session_id,turn_id) DO UPDATE SET input_digest=EXCLUDED.input_digest,expires_at=EXCLUDED.expires_at""", session, turn, hash, now.plusSeconds(ttl))
        return true
    }
}

private fun cleanDialogue(messages: JsonArray, now: Instant): JsonArray = JsonArray(messages.filter {
    val item = it as? JsonObject
    item != null && item["at"] != null && item["at"] != JsonNull && item.double("at") > now.unix() - 7 * 86400
})
