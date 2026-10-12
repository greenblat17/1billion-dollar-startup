package com.eliteteam.speakingcoach

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class TelegramOperationalMetricsTest {
    @Test
    fun scrapeIsAvailableOnlyOnTheLocalMonitoringConnector() {
        assertEquals(true, telegramMetricsScrapeAllowed(8081, 8081))
        assertEquals(false, telegramMetricsScrapeAllowed(443, 8081))
        assertEquals(false, telegramMetricsScrapeAllowed(443, 0))
    }

    @Test
    fun exportsCumulativeBucketsAndBoundedOutcomes() {
        val metrics = TelegramOperationalMetrics()
        metrics.recordDelivered(2.5, 0.2)
        metrics.recordDelivered(12.0, 1.5)
        metrics.recordDelivery(true)
        metrics.recordDelivery(false)
        metrics.recordOutcome("failed")
        metrics.recordOutcome("queue_full")
        metrics.recordWebhookRequest()
        metrics.recordWebhookRequest()

        val output = metrics.prometheus()
        assertContains(output, "telegram_voice_response_duration_seconds_bucket{client=\"telegram\",le=\"3.0\"} 1")
        assertContains(output, "telegram_voice_response_duration_seconds_bucket{client=\"telegram\",le=\"+Inf\"} 2")
        assertContains(output, "telegram_voice_queue_wait_seconds_bucket{client=\"telegram\",le=\"0.25\"} 1")
        assertContains(output, "telegram_voice_response_duration_seconds_count{client=\"telegram\"} 2")
        assertContains(output, "speaking_stage_attempts_total{client=\"telegram\",stage=\"delivery\",outcome=\"failure\"} 1")
        assertContains(output, "telegram_voice_requests_total{client=\"telegram\",outcome=\"queue_full\"} 1")
        assertContains(output, "telegram_webhook_requests_total{client=\"telegram\"} 2")
        assertEquals(false, output.contains("session="))
    }
}
