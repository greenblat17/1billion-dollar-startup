package com.eliteteam.speakingcoach.foundation

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.flywaydb.core.Flyway

internal val foundationJson = Json { encodeDefaults = true }

internal class FoundationFailure(val code: String) : RuntimeException(code)
internal fun reject(code: String): Nothing = throw FoundationFailure(code)
internal fun newId(): String = UUID.randomUUID().toString()
internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun JsonObject.text(key: String): String = optionalText(key) ?: reject("missing_$key")
internal fun JsonObject.optionalText(key: String): String? = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
internal fun JsonObject.number(key: String): Long = get(key)?.jsonPrimitive?.longOrNull ?: reject("invalid_$key")
internal fun JsonObject.obj(key: String): JsonObject = get(key)?.jsonObject ?: reject("missing_$key")
internal fun JsonObject.only(vararg keys: String) {
    if (this.keys.any { it !in keys }) reject("unknown_field")
}
internal fun json(vararg fields: Pair<String, Any?>): JsonObject = buildJsonObject {
    fields.forEach { (key, value) ->
        when (value) {
            null -> put(key, JsonNull)
            is JsonElement -> put(key, value)
            is Number -> put(key, JsonPrimitive(value))
            is Boolean -> put(key, value)
            else -> put(key, value.toString())
        }
    }
}
internal fun canonical(value: JsonElement): String = when (value) {
    is JsonObject -> value.entries.sortedBy { it.key }.joinToString(",", "{", "}") {
        "${JsonPrimitive(it.key)}:${canonical(it.value)}"
    }
    is kotlinx.serialization.json.JsonArray -> value.joinToString(",", "[", "]", transform = ::canonical)
    else -> value.toString()
}

/** Every callback is synchronous: a transaction cannot suspend across HTTP or AI calls. */
internal class FoundationDatabase(url: String, val clock: Clock = Clock.systemUTC()) : AutoCloseable {
    private val pool = HikariDataSource(HikariConfig().apply {
        jdbcUrl = if (url.startsWith("jdbc:")) url else "jdbc:$url"
        maximumPoolSize = 8
        poolName = "speaky-foundation"
    })

    init {
        Flyway.configure().dataSource(pool).load().migrate()
    }

    suspend fun <T> transaction(readOnly: Boolean = false, action: (Connection, Instant) -> T): T =
        withContext(Dispatchers.IO) {
            pool.connection.use { connection ->
                connection.autoCommit = false
                connection.isReadOnly = readOnly
                connection.transactionIsolation = if (readOnly) Connection.TRANSACTION_REPEATABLE_READ else Connection.TRANSACTION_READ_COMMITTED
                try {
                    val result = action(connection, clock.instant())
                    connection.commit()
                    result
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                }
            }
        }

    override fun close() = pool.close()
}

internal fun Connection.update(sql: String, vararg values: Any?): Int = prepareStatement(sql).use { statement ->
    values.forEachIndexed { index, value ->
        when (value) {
            is Instant -> statement.setTimestamp(index + 1, Timestamp.from(value))
            is JsonElement -> statement.setString(index + 1, value.toString())
            else -> statement.setObject(index + 1, value)
        }
    }
    statement.executeUpdate()
}

internal fun <T> Connection.rows(sql: String, vararg values: Any?, read: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value ->
            when (value) {
                is Instant -> statement.setTimestamp(index + 1, Timestamp.from(value))
                is JsonElement -> statement.setString(index + 1, value.toString())
                else -> statement.setObject(index + 1, value)
            }
        }
        statement.executeQuery().use { result -> buildList { while (result.next()) add(read(result)) } }
    }

internal fun Connection.documents(sql: String, vararg values: Any?): List<JsonObject> = rows(sql, *values) {
    foundationJson.parseToJsonElement(it.getString(1)).jsonObject
}
internal fun Connection.document(sql: String, vararg values: Any?): JsonObject? = documents(sql, *values).singleOrNull()
