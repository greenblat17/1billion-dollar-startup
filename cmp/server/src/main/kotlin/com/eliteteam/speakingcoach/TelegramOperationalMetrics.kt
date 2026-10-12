package com.eliteteam.speakingcoach

import java.util.Locale

internal fun telegramMetricsScrapeAllowed(localPort: Int, monitoringPort: Int): Boolean =
    monitoringPort > 0 && localPort == monitoringPort

/** Process-local, bounded Prometheus metrics for the Telegram webhook. */
internal class TelegramOperationalMetrics {
    private val lock = Any()
    private val bounds = doubleArrayOf(0.1, 0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 10.0, 20.0, 30.0, 60.0, 120.0, 240.0)
    private val responseBuckets = LongArray(bounds.size + 1)
    private val queueBuckets = LongArray(bounds.size + 1)
    private var responseCount = 0L
    private var responseSum = 0.0
    private var queueCount = 0L
    private var queueSum = 0.0
    private var webhookRequests = 0L
    private val outcomes = mutableMapOf("delivered" to 0L, "failed" to 0L, "queue_full" to 0L)
    private val delivery = mutableMapOf("success" to 0L, "failure" to 0L)

    fun recordWebhookRequest() = synchronized(lock) {
        webhookRequests++
    }

    fun recordDelivered(responseSeconds: Double, queueSeconds: Double) = synchronized(lock) {
        observe(responseSeconds, responseBuckets)
        responseCount++
        responseSum += responseSeconds
        observe(queueSeconds, queueBuckets)
        queueCount++
        queueSum += queueSeconds
        outcomes["delivered"] = outcomes.getValue("delivered") + 1
    }

    fun recordOutcome(outcome: String) = synchronized(lock) {
        require(outcome == "failed" || outcome == "queue_full")
        outcomes[outcome] = outcomes.getValue(outcome) + 1
    }

    fun recordDelivery(success: Boolean) = synchronized(lock) {
        val outcome = if (success) "success" else "failure"
        delivery[outcome] = delivery.getValue(outcome) + 1
    }

    fun prometheus(): String = synchronized(lock) {
        buildString {
            histogram("telegram_voice_response_duration_seconds", responseBuckets, responseCount, responseSum)
            histogram("telegram_voice_queue_wait_seconds", queueBuckets, queueCount, queueSum)
            appendLine("# TYPE telegram_webhook_requests_total counter")
            appendLine("telegram_webhook_requests_total{client=\"telegram\"} $webhookRequests")
            appendLine("# TYPE telegram_voice_requests_total counter")
            for ((outcome, value) in outcomes) {
                appendLine("telegram_voice_requests_total{client=\"telegram\",outcome=\"$outcome\"} $value")
            }
            appendLine("# TYPE speaking_stage_attempts_total counter")
            for ((outcome, value) in delivery) {
                appendLine("speaking_stage_attempts_total{client=\"telegram\",stage=\"delivery\",outcome=\"$outcome\"} $value")
            }
        }
    }

    private fun observe(value: Double, buckets: LongArray) {
        if (!value.isFinite() || value < 0) return
        for (index in buckets.indices) {
            if (index == bounds.size || value <= bounds[index]) buckets[index]++
        }
    }

    private fun StringBuilder.histogram(name: String, buckets: LongArray, count: Long, sum: Double) {
        appendLine("# TYPE $name histogram")
        for (index in buckets.indices) {
            val le = if (index == bounds.size) "+Inf" else bounds[index].toString()
            appendLine("${name}_bucket{client=\"telegram\",le=\"$le\"} ${buckets[index]}")
        }
        appendLine("${name}_count{client=\"telegram\"} $count")
        appendLine("${name}_sum{client=\"telegram\"} ${String.format(Locale.ROOT, "%.6f", sum)}")
    }
}
