package com.eliteteam.speakingcoach

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.eliteteam.speakingcoach.speaking.AudioClip
import com.eliteteam.speakingcoach.speaking.ClipReply
import com.eliteteam.speakingcoach.speaking.SessionClipQueue
import com.eliteteam.speakingcoach.speaking.SessionId
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestLogTest {

    @Test
    fun warningKeepsTheSameRequestAsThePrecedingInfo() = runTest {
        val logger = LoggerFactory.getLogger("RequestLogTest") as Logger
        val lines = mutableListOf<String>()
        val layout = PatternLayout().apply {
            pattern = "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} session=%X{session} request=%X{request} - %msg%n"
            context = logger.loggerContext
            start()
        }
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(eventObject: ILoggingEvent) {
                lines += layout.doLayout(eventObject)
            }
        }.apply {
            context = logger.loggerContext
            start()
        }
        logger.addAppender(appender)
        logger.level = Level.INFO
        logger.isAdditive = false
        val token = "123456:AA-test_token"
        val transcript = "I go to school yesterday"
        try {
            withRequestLog(session = "tg-1", request = "message:10") {
                logger.info("Submitting clip")
                logger.warn("Clip poll failed")
            }
            assertEquals(2, lines.size, lines.joinToString(" | "))
            assertTrue(lines.all { it.contains("session=tg-1") && it.contains("request=message:10") })
            assertTrue(lines[0].contains("INFO") && lines[0].contains("Submitting clip"))
            assertTrue(lines[1].contains("WARN") && lines[1].contains("Clip poll failed"))
            assertTrue(lines.none { it.contains(token) || it.contains(transcript) })
            assertEquals(null, MDC.get(LOG_REQUEST))
        } finally {
            logger.detachAppender(appender)
            MDC.clear()
        }
    }

    @Test
    fun voiceDownloadFailureKeepsTheRequestOnTheWorker() = runTest {
        val seen = mutableListOf<String?>()
        val queue = SessionClipQueue(
            processor = { _, _ -> ClipReply(emptyList(), AudioClip(byteArrayOf(), "audio/ogg", "voice.ogg")) },
            scope = this,
        )
        withRequestLog(session = "tg-1", request = "message:10") {
            try {
                queue.submit(SessionId("tg-1"), source = {
                    seen += MDC.get(LOG_REQUEST)
                    error("download failed")
                })
            } catch (_: IllegalStateException) {
                seen += MDC.get(LOG_REQUEST)
            }
        }
        assertEquals(listOf<String?>("message:10", "message:10"), seen)
        assertFalse(seen.any { it?.contains("download failed") == true })
    }

    @Test
    fun logbackUsesTheStandardPattern() {
        val xml = checkNotNull(javaClass.classLoader.getResource("logback.xml")).readText()
        assertTrue(xml.contains("%d{yyyy-MM-dd HH:mm:ss.SSS}"))
        assertTrue(xml.contains("session=%X{session}"))
        assertTrue(xml.contains("request=%X{request}"))
        assertFalse(xml.contains("YYYY"))
    }
}
