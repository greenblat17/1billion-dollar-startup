package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.ai.HttpClipClient
import com.eliteteam.speakingcoach.speaking.SessionClipQueue
import com.eliteteam.speakingcoach.telegram.TELEGRAM_WEBHOOK_SECRET_HEADER
import com.eliteteam.speakingcoach.telegram.TelegramPollingBot
import com.eliteteam.speakingcoach.telegram.startTelegramWebhook
import com.eliteteam.speakingcoach.tls.TLS_KEY_ALIAS
import com.eliteteam.speakingcoach.tls.loadPemKeyStore
import dev.inmo.tgbotapi.types.update.abstracts.UpdateDeserializationStrategy
import dev.inmo.tgbotapi.updateshandlers.FlowsUpdatesFilter
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

private val tlsStorePassword = "ktor".toCharArray()
private val telegramUpdateJson = Json { ignoreUnknownKeys = true }

fun main() {
    val config = AppConfig.fromEnv()
    if (config.usesWebhook) {
        val keyStore = loadPemKeyStore(
            File(config.tlsCertPath),
            File(config.tlsKeyPath),
            tlsStorePassword,
        )
        embeddedServer(
            factory = Netty,
            configure = {
                sslConnector(
                    keyStore = keyStore,
                    keyAlias = TLS_KEY_ALIAS,
                    keyStorePassword = { tlsStorePassword },
                    privateKeyPassword = { tlsStorePassword },
                ) {
                    host = "0.0.0.0"
                    port = config.serverPort
                }
            },
        ) {
            module(config)
        }.start(wait = true)
    } else {
        embeddedServer(Netty, port = config.serverPort, host = "0.0.0.0") {
            module(config)
        }.start(wait = true)
    }
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

    val webhookUpdates = if (config.usesWebhook) FlowsUpdatesFilter() else null
    val webhookSecret = config.telegramWebhookSecret

    routing {
        get("/") {
            call.respondText(sayHello("Ktor"))
        }
        get("/health") {
            call.respondText("ok")
        }
        if (webhookUpdates != null && webhookSecret != null) {
            post("/telegram/webhook") {
                val provided = call.request.header(TELEGRAM_WEBHOOK_SECRET_HEADER)
                if (provided != webhookSecret) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@post
                }
                try {
                    val update = telegramUpdateJson.decodeFromString(
                        UpdateDeserializationStrategy,
                        call.receiveText(),
                    )
                    this@module.launch {
                        webhookUpdates.asUpdateReceiver(update)
                    }
                    call.respond(HttpStatusCode.OK)
                } catch (error: Throwable) {
                    log.error("Failed to handle Telegram webhook", error)
                    call.respond(HttpStatusCode.InternalServerError)
                }
            }
        }
    }

    val token = config.telegramBotToken
    if (token.isNullOrBlank()) {
        log.warn("TELEGRAM_BOT_TOKEN is not set; Telegram bot will not start")
        return
    }
    if (config.usesWebhook) {
        val url = checkNotNull(config.telegramWebhookUrl)
        val secret = checkNotNull(webhookSecret)
        val updates = checkNotNull(webhookUpdates)
        launch {
            log.info("Starting Telegram webhook at {}", url)
            startTelegramWebhook(
                token = token,
                webhookUrl = url,
                webhookSecret = secret,
                certificateFile = File(config.tlsCertPath),
                sessionClipQueue = sessionClipQueue,
                updates = updates,
                scope = this@module,
            )
        }
    } else {
        launch {
            log.info("Starting Telegram long polling")
            TelegramPollingBot(token, sessionClipQueue).startPolling()
        }
    }
}
