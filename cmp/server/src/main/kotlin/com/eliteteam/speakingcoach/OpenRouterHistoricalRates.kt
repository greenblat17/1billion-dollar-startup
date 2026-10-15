package com.eliteteam.speakingcoach

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val historicalRatesJson = Json { ignoreUnknownKeys = true }

/** Minute aggregates imported offline; the management key never reaches CMP. */
@Serializable
internal data class OpenRouterHistoricalRates(
    val schema: Int,
    val scope: String,
    @SerialName("from_utc") val fromUtc: String,
    @SerialName("to_utc") val toUtc: String,
    val models: List<String>,
    val minutes: List<OpenRouterRateMinute>,
) {
    companion object {
        fun load(path: Path): OpenRouterHistoricalRates {
            require(Files.size(path) <= 16_000_000) { "Historical rates file exceeds 16 MB" }
            val value = historicalRatesJson.decodeFromString<OpenRouterHistoricalRates>(Files.readString(path))
            require(value.schema == 1 && value.scope in setOf("test", "production")) {
                "Unsupported historical rates file"
            }
            return value
        }
    }
}

@Serializable
internal data class OpenRouterRateMinute(
    @SerialName("minute_utc") val minuteUtc: String,
    val calls: Long,
    @SerialName("timed_calls") val timedCalls: Long = 0,
    val tpm: Long,
    @SerialName("tps_milli") val tpsMilli: Long? = null,
)

internal data class OpenRouterRateDay(
    val day: LocalDate,
    val coveredMinutes: Long,
    val peakTpm: Long,
    val peakTpsMilli: Long?,
)

internal fun OpenRouterHistoricalRates.days(range: LlmRange): List<OpenRouterRateDay> {
    val zone = ZoneId.of("Europe/Moscow")
    val from = Instant.parse(fromUtc)
    val to = Instant.parse(toUtc)
    require(to > from) { "Empty historical rates interval" }
    val grouped = minutes.groupBy { Instant.parse(it.minuteUtc).atZone(zone).toLocalDate() }
    return generateSequence(range.from) { previous -> previous.plusDays(1).takeIf { it <= range.to } }
        .mapNotNull { day ->
            val start = day.atStartOfDay(zone).toInstant()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant()
            val overlapStart = maxOf(start, from)
            val overlapEnd = minOf(end, to)
            if (overlapStart >= overlapEnd) return@mapNotNull null
            val values = grouped[day].orEmpty()
            OpenRouterRateDay(
                day = day,
                coveredMinutes = ChronoUnit.MINUTES.between(overlapStart, overlapEnd),
                peakTpm = values.maxOfOrNull { it.tpm } ?: 0,
                peakTpsMilli = values.mapNotNull { it.tpsMilli }.maxOrNull()
                    ?: if (values.isEmpty()) 0 else null,
            )
        }.toList()
}
