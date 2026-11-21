package com.eliteteam.speakingcoach

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory

internal class DiagnosticOutput : AutoCloseable {
    private val logger = LoggerFactory.getLogger("RuntimeDiagnostics") as Logger
    private val appender = ListAppender<ILoggingEvent>().apply { start() }

    init { logger.addAppender(appender) }

    fun lines(): List<String> = appender.list.map { it.formattedMessage }
    fun events(): List<JsonObject> = lines().map { Json.parseToJsonElement(it.substringAfter(' ')).jsonObject }

    override fun close() {
        logger.detachAppender(appender)
        appender.stop()
    }
}
