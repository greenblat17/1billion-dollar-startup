package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal class LegacyVoiceCost(private val database: FoundationDatabase) {
    suspend fun command(request: JsonObject): JsonObject = database.transaction { connection, now ->
        request.only("action", "args")
        val args = request.obj("args")
        when (request.text("action")) {
            "record" -> record(connection, args, now)
            "batch" -> {
                args.only("limit")
                json("events" to JsonArray(connection.documents("SELECT payload FROM legacy_voice_cost WHERE outbox AND expires_at>? ORDER BY outbox_at,call_id LIMIT ?",
                    now, (args.optionalText("limit")?.toInt() ?: 100).coerceIn(1, 100))))
            }
            "acknowledge" -> {
                args.only("receipts")
                val receipts = args["receipts"]?.jsonArray ?: reject("invalid_receipts")
                if (receipts.size > 100) reject("invalid_receipts")
                receipts.forEach { item ->
                    val receipt = item.jsonObject
                    receipt.only("callId", "revision")
                    connection.update("UPDATE legacy_voice_cost SET outbox=FALSE WHERE call_id=? AND revision=?", receipt.text("callId"), receipt.number("revision"))
                }
                json("acknowledged" to true)
            }
            "claim" -> {
                args.only()
                val jobs = connection.documents("""SELECT to_jsonb(c) FROM legacy_voice_cost c WHERE due_at<=? AND expires_at>?
                    AND (lease_until IS NULL OR lease_until<=?) ORDER BY due_at,call_id LIMIT 20 FOR UPDATE SKIP LOCKED""", now, now, now)
                json("events" to JsonArray(jobs.map { row ->
                    val fence = row.number("fence") + 1
                    connection.update("UPDATE legacy_voice_cost SET fence=?,lease_until=? WHERE call_id=?", fence, now.plusSeconds(120), row.text("call_id"))
                    JsonObject(row.obj("payload") + json("fence" to fence))
                }))
            }
            "settle" -> settle(connection, args, now)
            "prune" -> json("count" to connection.update("DELETE FROM legacy_voice_cost WHERE expires_at<=?", now))
            else -> reject("unknown_action")
        }
    }

    private fun record(connection: Connection, args: JsonObject, now: Instant): JsonObject {
        args.only("callId", "attemptId", "component", "provider", "model", "result", "cost", "generationId", "occurredAt")
        val id = args.text("callId")
        if (id.isBlank() || id.length > 200) reject("invalid_call")
        val generation = args.optionalText("generationId")?.takeIf { it.isNotBlank() }
        connection.rows("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "voice-cost:${args.text("provider")}:${generation ?: id}") { Unit }
        val fingerprint = digest(canonical(JsonObject(args - "callId" - "occurredAt")))
        val previous = connection.document("SELECT to_jsonb(c) FROM legacy_voice_cost c WHERE call_id=? OR (provider=? AND generation_id=?)",
            id, args.text("provider"), generation)
        if (previous != null) {
            if (previous.text("input_digest") != fingerprint) reject("operation_conflict")
            return json("callId" to previous.text("call_id"))
        }
        val cost = args["cost"]?.takeUnless { it == JsonNull }?.let { args.number("cost") }
        if (cost != null && cost < 0) reject("invalid_cost")
        val occurred = Instant.parse(args.text("occurredAt"))
        val pending = args.text("component") == "tts" && args.text("result") == "success" && args.text("provider") == "openrouter" && generation != null && cost == null
        val event = json("callId" to id, "attemptId" to args.text("attemptId"), "component" to args.text("component"),
            "provider" to args.text("provider"), "model" to args.text("model"), "result" to args.text("result"), "occurredAt" to occurred.toString(),
            "costMicroUsd" to cost, "priceStatus" to if (pending) "pending" else if (cost != null) "known" else "unavailable",
            "generationId" to if (pending) generation else null, "revision" to 1, "lookupStep" to 0, "createdEpoch" to occurred.unix())
        connection.update("""INSERT INTO legacy_voice_cost(call_id,provider,generation_id,input_digest,payload,outbox_at,due_at,expires_at)
            VALUES (?,?,?,?,?::jsonb,?,?,?)""", id, args.text("provider"), generation, fingerprint, event, now,
            if (pending) occurred.plusSeconds(15) else null, occurred.plusSeconds(200 * 86400))
        return json("callId" to id)
    }

    private fun settle(connection: Connection, args: JsonObject, now: Instant): JsonObject {
        args.only("callId", "revision", "fence", "cost", "retryable")
        val row = connection.document("SELECT to_jsonb(c) FROM legacy_voice_cost c WHERE call_id=? FOR UPDATE", args.text("callId")) ?: reject("not_owned")
        if (row.number("revision") != args.number("revision") || row.number("fence") != args.number("fence") ||
            row.optionalText("lease_until")?.let { Instant.parse(it) > now } != true || Instant.parse(row.text("expires_at")) <= now) reject("stale_job")
        val event = row.obj("payload")
        val cost = args["cost"]?.takeUnless { it == JsonNull }?.let { args.number("cost") }
        if (cost != null && cost < 0) reject("invalid_cost")
        val steps = listOf(15L, 60L, 120L, 600L, 3600L)
        val step = event.number("lookupStep").toInt() + 1
        val status = if (cost != null) "known" else if (args["retryable"] != JsonPrimitive(true) || step >= steps.size) "unavailable" else "pending"
        val revision = row.number("revision") + 1
        val updated = JsonObject(event + json("lookupStep" to step, "revision" to revision, "costMicroUsd" to cost, "priceStatus" to status))
        connection.update("UPDATE legacy_voice_cost SET payload=?::jsonb,revision=?,outbox=TRUE,outbox_at=?,due_at=?,lease_until=NULL WHERE call_id=?",
            updated, revision, now, if (status == "pending") Instant.parse(event.text("occurredAt")).plusSeconds(steps[step]) else null, args.text("callId"))
        return json("settled" to true)
    }
}
