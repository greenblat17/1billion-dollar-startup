package com.eliteteam.speakingcoach

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.FileAppender
import java.io.File

internal class LogFileAppender : FileAppender<ILoggingEvent>() {
    override fun start() {
        file?.let { path -> File(path).parentFile?.mkdirs() }
        super.start()
    }
}
