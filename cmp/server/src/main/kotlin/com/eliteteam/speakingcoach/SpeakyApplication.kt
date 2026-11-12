package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.tls.TLS_KEY_ALIAS
import com.eliteteam.speakingcoach.tls.loadPemKeyStore
import io.ktor.server.engine.connector
import com.eliteteam.speakingcoach.ai.TutorClient
import com.eliteteam.speakingcoach.foundation.trialRoutes
import com.eliteteam.speakingcoach.foundation.FoundationChat
import com.eliteteam.speakingcoach.foundation.FoundationWorker
import com.eliteteam.speakingcoach.foundation.HttpLearningComputer
import com.eliteteam.speakingcoach.foundation.installFoundation
import com.eliteteam.speakingcoach.telegram.buildSpeakyTelegram
import com.eliteteam.speakingcoach.telegram.installSpeakingCoachWebhook
import com.eliteteam.speakingcoach.telegram.newTelegramWebhookScope
import com.eliteteam.speakingcoach.telegram.registerTelegramWebhook
import dev.inmo.tgbotapi.extensions.api.bot.setMyCommands
import dev.inmo.tgbotapi.types.BotCommand
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File

/** Shipping entry point: no construction of legacy calls, dashboards, reminders or mobile prototype. */
internal suspend fun startSpeakyServer(config: AppConfig) {
    val password = "ktor".toCharArray()
    val keyStore = if (config.usesWebhook) loadPemKeyStore(File(config.tlsCertPath), File(config.tlsKeyPath), password) else null
    embeddedServer(Netty, configure = {
        if (keyStore != null) {
            sslConnector(keyStore, TLS_KEY_ALIAS, { password }, { password }) {
                host = "0.0.0.0"
                port = config.serverPort
            }
        } else connector {
            host = "0.0.0.0"
            port = config.serverPort
        }
        if (config.monitoringPort > 0 && config.monitoringPort != config.serverPort) {
            connector {
                host = "127.0.0.1"
                port = config.monitoringPort
            }
        }
    }) { speakyModule(config) }.start(wait = true)
}

@OptIn(ExperimentalKtorApi::class)
internal fun Application.speakyModule(config: AppConfig) {
    val foundation = requireNotNull(installFoundation(config.databaseUrl, config.aiInternalToken)) { "DATABASE_URL required" }
    val http = speakingCoachAiHttpClient()
    val scope = newTelegramWebhookScope()
    val tutor = TutorClient(config.aiServiceBaseUrl, http, config.aiInternalToken).apply { this.foundation = FoundationChat(foundation) }
    com.eliteteam.speakingcoach.foundation.MockPaymentAdapter(foundation).launch(scope)
    FoundationWorker(foundation, HttpLearningComputer(http, config.aiServiceBaseUrl, config.aiInternalToken)).launch(scope)
    monitor.subscribe(ApplicationStopped) { scope.cancel(); http.close() }
    routing {
        get("/health") { call.respondText("ok") }
        trialRoutes(foundation, config.aiInternalToken, config.telegramBotToken ?: "")
    }
    val miniUrl = System.getenv("MINI_APP_URL")?.trim().orEmpty()
    if (miniUrl.startsWith("https://") && !config.telegramBotToken.isNullOrBlank()) {
        com.eliteteam.speakingcoach.foundation.TrialWorker(foundation, http, config.aiServiceBaseUrl,
            config.aiInternalToken, config.telegramBotToken, miniUrl).launch(scope)
    }
    if (config.usesWebhook) {
        val bot = runBlocking { buildSpeakyTelegram(requireNotNull(config.telegramBotToken), tutor, scope) }
        routing {
            route("/telegram/webhook") {
                installSpeakingCoachWebhook(requireNotNull(config.telegramWebhookSecret), bot, scope,
                    operationalMetrics = TelegramOperationalMetrics(), textIntake = foundation.telegramIntake, paymentHandler = com.eliteteam.speakingcoach.telegram.MockBillingTelegram(foundation, bot)::handle)
            }
        }
        runBlocking {
            registerTelegramWebhook(bot, requireNotNull(config.telegramWebhookUrl), requireNotNull(config.telegramWebhookSecret), File(config.tlsCertPath))
            bot.setMyCommands(listOf(BotCommand("start", "Начать разговор")))
        }
    }
}
