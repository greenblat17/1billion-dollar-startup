package com.eliteteam.speakingcoach

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class AdminProviderError(val at: String, val type: String, val code: String)

internal fun interface AdminLogSource {
    suspend fun recentProviderErrors(range: LlmRange): List<AdminProviderError>
}

/** Loki is used for bounded diagnosis, not for counting long-lived product totals. */
internal class LokiAdminLogSource : AdminLogSource {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val zone = ZoneId.of("Europe/Moscow")

    override suspend fun recentProviderErrors(range: LlmRange): List<AdminProviderError> = withContext(Dispatchers.IO) {
        val query = "{service_name=~\"speaking-coach|ai-service\"} |= \"provider_call\" |= \"\\\"source\\\":\\\"telegram\\\"\" |= \"\\\"result\\\":\\\"error\\\"\""
        val parameters = mapOf(
            "query" to query,
            "start" to range.from.atStartOfDay(zone).toInstant().toEpochMilli().times(1_000_000).toString(),
            "end" to range.to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli().times(1_000_000).toString(),
            "limit" to "20", "direction" to "backward",
        ).entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:3100/loki/api/v1/query_range?$parameters"))
            .timeout(Duration.ofSeconds(3)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Loki HTTP ${response.statusCode()}" }
        val streams = Json.parseToJsonElement(response.body()).jsonObject["data"]!!.jsonObject["result"]!!.jsonArray
        streams.flatMap { stream -> stream.jsonObject["values"]!!.jsonArray.mapNotNull { item ->
            val line = item.jsonArray.getOrNull(1)?.jsonPrimitive?.content.orEmpty()
            val json = line.substringAfter("provider_call ", "")
            if (json.isEmpty()) return@mapNotNull null
            val event = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return@mapNotNull null
            if (event["source"]?.jsonPrimitive?.content != "telegram" ||
                event["result"]?.jsonPrimitive?.content != "error") return@mapNotNull null
            AdminProviderError(
                at = event["timestamp"]?.jsonPrimitive?.content.orEmpty().take(25),
                type = event["type"]?.jsonPrimitive?.content.orEmpty().take(30),
                code = event["error_code"]?.jsonPrimitive?.content.orEmpty().take(80),
            )
        } }.take(8)
    }
}
