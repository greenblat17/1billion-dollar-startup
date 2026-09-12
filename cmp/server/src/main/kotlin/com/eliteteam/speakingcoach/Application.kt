package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.ai.HttpClipClient
import com.eliteteam.speakingcoach.speaking.SessionClipQueue
import com.eliteteam.speakingcoach.telegram.TelegramPollingBot
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun main() {
    val config = AppConfig.fromEnv()
    embeddedServer(Netty, port = config.serverPort, host = "0.0.0.0") {
        module(config)
    }.start(wait = true)
}

fun Application.module(config: AppConfig = AppConfig.fromEnv()) {
    val log = LoggerFactory.getLogger("Application")
    val aiHttp = HttpClient(CIO) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                },
            )
        }
    }
    monitor.subscribe(ApplicationStopped) {
        aiHttp.close()
    }

    val sessionClipQueue = SessionClipQueue(
        processor = HttpClipClient(config.aiServiceBaseUrl, aiHttp),
        scope = this,
    )

    routing {
        get("/") {
            call.respondText(sayHello("Ktor"))
        }
        get("/health") {
            call.respondText("ok")
        }
    }

    val token = config.telegramBotToken
    if (token.isNullOrBlank()) {
        log.warn("TELEGRAM_BOT_TOKEN is not set; long polling will not start")
    } else {
        launch {
            log.info("Starting Telegram long polling")
            TelegramPollingBot(token, sessionClipQueue).startPolling()
        }
    }
}
