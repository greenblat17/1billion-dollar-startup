package com.eliteteam.speakingcoach.foundation

import com.eliteteam.speakingcoach.RuntimeDiagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Stores the authenticated envelope before webhook ACK; routing still belongs to Telegram handlers. */
internal class TelegramTextIntake(private val database: FoundationDatabase) {
    suspend fun accept(update: JsonObject): Boolean {
        val message = (update["message"] as? JsonObject) ?: return false
        val chat = (message["chat"] as? JsonObject) ?: return false
        val sender = (message["from"] as? JsonObject) ?: return false
        if (chat.optionalText("type") != "private" || message.optionalText("text") == null) return false
        if (sender.number("id") != chat.number("id")) reject("invalid_identity")
        database.transaction { connection, now ->
            connection.lockTelegramChat(chat.number("id"))
            val hash = digest(canonical(update))
            val inserted = connection.update("""INSERT INTO telegram_text_intake(update_id,chat_id,message_id,payload,input_digest,received_at,expires_at)
                VALUES (?,?,?,?::jsonb,?,?,?) ON CONFLICT(chat_id,message_id) DO NOTHING""",
                update.number("update_id"), chat.number("id"), message.number("message_id"), update, hash, now, now.plusSeconds(7 * 86400))
            val existing = connection.document("SELECT to_jsonb(i) FROM telegram_text_intake i WHERE chat_id=? AND message_id=?", chat.number("id"), message.number("message_id")) ?: reject("invalid_input")
            if (existing.text("input_digest") != hash) reject("operation_conflict")
            // Delayed pre-reset updates must never enter the fresh relationship.
            connection.update("""UPDATE telegram_text_intake SET payload=NULL,status='done',lease_until=NULL
                WHERE chat_id=? AND message_id=? AND message_id<(SELECT message_id FROM telegram_start_reset WHERE chat_id=?)""",
                chat.number("id"), message.number("message_id"), chat.number("id"))
            if (inserted == 1) connection.update("""UPDATE telegram_introduction SET status='cancelled'
                WHERE chat_id=? AND message_id<? AND status='active'""", chat.number("id"), message.number("message_id"))
        }
        return true
    }

    suspend fun complete(chatId: Long, messageId: Long) = database.transaction { connection, _ ->
        connection.update("UPDATE telegram_text_intake SET status='done',lease_until=NULL WHERE chat_id=? AND message_id=?", chatId, messageId)
    }

    suspend fun claim(): JsonObject? = database.transaction { connection, now ->
        connection.update("UPDATE telegram_text_intake SET payload=NULL,status=CASE WHEN status='done' THEN 'done' ELSE 'failed' END WHERE expires_at<=? AND payload IS NOT NULL", now)
        connection.update("UPDATE telegram_text_intake SET status='failed' WHERE status='running' AND lease_until<=? AND attempts>=3", now)
        val row = connection.document("""SELECT to_jsonb(i) FROM telegram_text_intake i WHERE payload IS NOT NULL AND expires_at>?
            AND (status='pending' OR (status='running' AND lease_until<=? AND attempts<3))
            AND NOT EXISTS (SELECT 1 FROM telegram_text_intake earlier WHERE earlier.chat_id=i.chat_id
                AND earlier.message_id<i.message_id AND earlier.status IN ('pending','running') AND earlier.expires_at>?)
            ORDER BY received_at,update_id LIMIT 1 FOR UPDATE SKIP LOCKED""", now, now, now)
        row?.also { connection.update("UPDATE telegram_text_intake SET status='running',attempts=attempts+1,lease_until=? WHERE update_id=?", now.plusSeconds(180), row.number("update_id")) }
    }

    fun launch(scope: CoroutineScope, dispatch: suspend (JsonObject) -> Unit) = scope.launch {
        while (isActive) {
            try {
                val row = claim()
                if (row == null) delay(500) else dispatch(row["payload"]!!.jsonObject)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                RuntimeDiagnostics.record("telegram_text_intake_dispatch_failed", "dispatch", error)
                delay(1000)
            }
        }
    }
}
