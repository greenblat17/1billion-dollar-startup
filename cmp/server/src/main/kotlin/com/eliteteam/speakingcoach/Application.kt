package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.ai.HttpClipClient
import com.eliteteam.speakingcoach.speaking.SessionClipQueue
import com.eliteteam.speakingcoach.telegram.buildTelegramWebhookBehaviour
import com.eliteteam.speakingcoach.telegram.installTelegramWebhookRoute
import com.eliteteam.speakingcoach.telegram.registerTelegramWebhook
import com.eliteteam.speakingcoach.tls.TLS_KEY_ALIAS
import com.eliteteam.speakingcoach.tls.loadPemKeyStore
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

private val tlsStorePassword = "ktor".toCharArray()

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
    val telegramScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var webhookBehaviour: BehaviourContext? = null
    monitor.subscribe(ApplicationStopped) {
        webhookBehaviour?.cancel()
        telegramScope.cancel()
        aiHttp.close()
    }

    val sessionClipQueue = SessionClipQueue(
        processor = HttpClipClient(config.aiServiceBaseUrl, aiHttp),
        scope = telegramScope,
    )

    val token = config.telegramBotToken
    if (!token.isNullOrBlank()) {
        webhookBehaviour = runBlocking {
            buildTelegramWebhookBehaviour(token, sessionClipQueue)
        }
    }

    routing {
        get("/") {
            call.respondText(sayHello("Ktor"))
        }
        get("/health") {
            call.respondText("ok")
        }
        if (config.usesWebhook) {
            val webhookSecret = checkNotNull(config.telegramWebhookSecret)
            route("/telegram/webhook") {
                installTelegramWebhookRoute(webhookSecret, webhookBehaviour)
            }
        }
    }

    if (token.isNullOrBlank()) {
        log.warn("TELEGRAM_BOT_TOKEN is not set; Telegram bot will not start")
        return
    }
    val url = checkNotNull(config.telegramWebhookUrl)
    val secret = checkNotNull(config.telegramWebhookSecret)
    val behaviour = checkNotNull(webhookBehaviour)
    monitor.subscribe(ApplicationStarted) {
        telegramScope.launch {
            log.info("Starting Telegram webhook at {}", url)
            registerTelegramWebhook(
                bot = behaviour,
                webhookUrl = url,
                webhookSecret = secret,
                certificateFile = File(config.tlsCertPath),
            )
        }
    }
}
