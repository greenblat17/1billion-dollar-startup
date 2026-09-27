package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.ai.HttpClipClient
import com.eliteteam.speakingcoach.ai.HttpMetricsSource
import com.eliteteam.speakingcoach.app.AppApi
import com.eliteteam.speakingcoach.app.createAppApi
import com.eliteteam.speakingcoach.app.installAppPlugins
import com.eliteteam.speakingcoach.app.installAppRoutes
import com.eliteteam.speakingcoach.speaking.SessionClipQueue
import com.eliteteam.speakingcoach.telegram.TELEGRAM_WEBHOOK_SECRET_HEADER
import com.eliteteam.speakingcoach.telegram.buildTelegramWebhookBehaviour
import com.eliteteam.speakingcoach.telegram.installSpeakingCoachWebhook
import com.eliteteam.speakingcoach.telegram.ReminderAdmin
import com.eliteteam.speakingcoach.telegram.ReminderRunner
import com.eliteteam.speakingcoach.telegram.RunnerReminderAdmin
import com.eliteteam.speakingcoach.telegram.launchDailyReminder
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.RawChatId
import com.eliteteam.speakingcoach.telegram.newTelegramWebhookScope
import com.eliteteam.speakingcoach.telegram.registerBotCommands
import com.eliteteam.speakingcoach.telegram.registerTelegramWebhook
import com.eliteteam.speakingcoach.telegram.telegramSessionId
import kotlinx.coroutines.CancellationException
import com.eliteteam.speakingcoach.tls.TLS_KEY_ALIAS
import com.eliteteam.speakingcoach.tls.loadPemKeyStore
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
import io.ktor.server.response.respond
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

private val tlsStorePassword = "ktor".toCharArray()

suspend fun main() {
    val config = AppConfig.fromEnv()
    if (config.usesWebhook) {
        startWebhookServer(config)
    } else {
        embeddedServer(Netty, port = config.serverPort, host = "0.0.0.0") {
            module(config)
        }.start(wait = true)
    }
}

@OptIn(ExperimentalKtorApi::class)
private suspend fun startWebhookServer(config: AppConfig) {
    val log = LoggerFactory.getLogger("Application")
    val token = checkNotNull(config.telegramBotToken) {
        "TELEGRAM_BOT_TOKEN is required when TELEGRAM_WEBHOOK_URL is set"
    }
    val webhookUrl = checkNotNull(config.telegramWebhookUrl)
    val webhookSecret = checkNotNull(config.telegramWebhookSecret)
    val aiHttp = speakingCoachAiHttpClient()
    val webhookScope = newTelegramWebhookScope()
    val ai = HttpClipClient(config.aiServiceBaseUrl, aiHttp, internalToken = config.aiInternalToken)
    val sessionClipQueue = SessionClipQueue(
        processor = ai,
        scope = webhookScope,
    )
    val behaviourContext = buildTelegramWebhookBehaviour(token, ai, sessionClipQueue, webhookScope)
    val reminderRunner = ReminderRunner(
        claim = ai::claimReminders,
        report = ai::reportReminders,
        send = { chatId, text -> behaviourContext.sendTextMessage(ChatId(RawChatId(chatId)), text) },
        streakOf = { chatId ->
            try {
                ai.streakProfile(telegramSessionId(chatId)).current
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.warn("Failed to load streak for tg-{}", chatId, error)
                0
            }
        },
    )
    val appApi = createAppApi(config, aiHttp)
    val keyStore = loadPemKeyStore(
        File(config.tlsCertPath),
        File(config.tlsKeyPath),
        tlsStorePassword,
    )
    val server = embeddedServer(
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
        val ktorApp = this
        if (appApi != null) {
            installAppPlugins(appApi)
        }
        installSpeakingCoachHttp(
            metrics = metricsDashboard(
                config,
                HttpMetricsSource(ai),
                secureCookie = true,
                reminders = RunnerReminderAdmin(reminderRunner, webhookScope),
            ),
        ) {
            route("/telegram/webhook") {
                installSpeakingCoachWebhook(webhookSecret, behaviourContext, webhookScope)
            }.hide()
            if (appApi != null) {
                installAppRoutes(ktorApp, appApi)
            }
        }
    }
    server.start(wait = false)
    registerTelegramWebhook(
        bot = behaviourContext,
        webhookUrl = webhookUrl,
        webhookSecret = webhookSecret,
        certificateFile = File(config.tlsCertPath),
    )
    log.info("Telegram webhook registered at {}", webhookUrl)
    try {
        registerBotCommands(behaviourContext)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        log.error("Failed to register bot commands", error)
    }
    webhookScope.launchDailyReminder(reminderRunner)
    try {
        awaitCancellation()
    } finally {
        behaviourContext.cancel()
        webhookScope.cancel()
        aiHttp.close()
        server.stop()
    }
}

@OptIn(ExperimentalKtorApi::class)
internal fun Application.module(
    config: AppConfig = AppConfig.fromEnv(),
    appApi: AppApi? = createAppApi(config),
    metricsSource: MetricsSource? = null,
    reminderAdmin: ReminderAdmin? = null,
) {
    if (appApi != null) {
        installAppPlugins(appApi)
    }
    installSpeakingCoachHttp(
        metrics = metricsDashboard(
            config,
            metricsSource ?: ownedMetricsSource(config),
            secureCookie = false,
            reminders = reminderAdmin,
        ),
    ) {
        if (config.usesWebhook) {
            val webhookSecret = checkNotNull(config.telegramWebhookSecret)
            post("/telegram/webhook") {
                val provided = call.request.header(TELEGRAM_WEBHOOK_SECRET_HEADER)
                if (provided != webhookSecret) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@post
                }
                call.respond(HttpStatusCode.ServiceUnavailable)
            }.hide()
        }
        if (appApi != null) {
            installAppRoutes(this@module, appApi)
        }
    }
}

private fun Application.metricsDashboard(
    config: AppConfig,
    source: MetricsSource?,
    secureCookie: Boolean,
    reminders: ReminderAdmin? = null,
): MetricsDashboard? {
    val password = config.metricsPassword?.takeIf { it.isNotBlank() } ?: return null
    if (source == null) {
        return null
    }
    return MetricsDashboard(password, source, secureCookie, reminders)
}

private fun Application.ownedMetricsSource(config: AppConfig): MetricsSource? {
    if (config.metricsPassword.isNullOrBlank()) {
        return null
    }
    val http = speakingCoachAiHttpClient()
    monitor.subscribe(ApplicationStopped) {
        http.close()
    }
    return HttpMetricsSource(
        HttpClipClient(config.aiServiceBaseUrl, http, internalToken = config.aiInternalToken),
    )
}

internal fun speakingCoachAiHttpClient(): HttpClient = HttpClient(CIO) {
    expectSuccess = false
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
            },
        )
    }
}
