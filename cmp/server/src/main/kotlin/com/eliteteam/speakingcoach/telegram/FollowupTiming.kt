package com.eliteteam.speakingcoach.telegram

import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

internal data class PreferredVisitTime(val utcMinute: Int, val visitCount: Int)

/** One visit starts at the first inbound event after a 30-minute gap. */
internal fun preferredVisitTime(events: List<Instant>): PreferredVisitTime? {
    if (events.isEmpty()) return null
    val visits = mutableListOf<Instant>()
    var previous: Instant? = null
    for (event in events.sorted()) {
        if (previous == null || java.time.Duration.between(previous, event).toMinutes() >= 30) visits += event
        previous = event
    }
    val minutes = visits.map { it.atZone(ZoneOffset.UTC).let { utc -> utc.hour * 60 + utc.minute } }
    if (minutes.size == 1) return PreferredVisitTime(minutes.single(), 1)
    val windows = visits.indices.map { anchor ->
        val anchorMinute = minutes[anchor]
        val members = minutes.indices.filter { index ->
            (minutes[index] - anchorMinute + 1440) % 1440 < 120
        }
        anchor to members
    }
    val (anchor, selected) = windows.maxWithOrNull(compareBy<Pair<Int, List<Int>>> { it.second.size }
        .thenBy { pair -> pair.second.maxOf { visits[it] } })!!
    if (selected.size == 1) return PreferredVisitTime(minutes.last(), visits.size)
    val origin = minutes[anchor]
    val ordered = selected.sortedWith(compareBy<Int> { (minutes[it] - origin + 1440) % 1440 }
        .thenBy { visits[it] })
    val central = if (ordered.size % 2 == 1) ordered[ordered.size / 2] else {
        val left = ordered[ordered.size / 2 - 1]
        val right = ordered[ordered.size / 2]
        if (visits[left] >= visits[right]) left else right
    }
    return PreferredVisitTime(minutes[central], visits.size)
}

internal fun nextFollowupDue(preferredUtcMinute: Int, lowerBound: Instant): Instant {
    require(preferredUtcMinute in 0..1439)
    val utcDay = lowerBound.atZone(ZoneOffset.UTC).toLocalDate()
    val sameDay = utcDay.atStartOfDay(ZoneOffset.UTC).toInstant().plus(preferredUtcMinute.toLong(), ChronoUnit.MINUTES)
    if (sameDay >= lowerBound) return sameDay
    if (lowerBound < sameDay.plus(1, ChronoUnit.MINUTES)) return lowerBound
    return sameDay.plus(1, ChronoUnit.DAYS)
}

internal fun nextFollowupDay(preferredUtcMinute: Int, after: Instant): Instant =
    nextFollowupDue(preferredUtcMinute, after.plus(1, ChronoUnit.MINUTES))
