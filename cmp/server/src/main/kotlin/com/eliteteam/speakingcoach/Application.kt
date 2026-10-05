package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.analytics.createOnboardingAnalytics
import com.eliteteam.speakingcoach.analytics.createVoiceAttemptStore
import com.eliteteam.speakingcoach.analytics.VoiceAttemptRecorder
import com.eliteteam.speakingcoach.analytics.InteractionAudit
import com.eliteteam.speakingcoach.analytics.CallEventRecorder
import com.eliteteam.speakingcoach.analytics.createCallEventStore
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
import com.eliteteam.speakingcoach.telegram.LegacyCampaignAdmin
import com.eliteteam.speakingcoach.telegram.LegacyCampaignRunner
import com.eliteteam.speakingcoach.telegram.RunnerReminderAdmin
import com.eliteteam.speakingcoach.telegram.launchDailyReminder
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.RawChatId
import com.eliteteam.speakingcoach.telegram.newTelegramWebhookScope
import com.eliteteam.speakingcoach.telegram.registerBotCommands
import com.eliteteam.speakingcoach.telegram.registerTelegramWebhook
import com.eliteteam.speakingcoach.telegram.telegramSessionId
import com.eliteteam.speakingcoach.telegram.legacyCampaignMessage
import com.eliteteam.speakingcoach.telegram.legacyCampaignKeyboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.eliteteam.speakingcoach.tls.TLS_KEY_ALIAS
import com.eliteteam.speakingcoach.tls.loadPemKeyStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

private val tlsStorePassword = "ktor".toCharArray()

suspend fun main() {
    val config = AppConfig.fromEnv()
    if (config.usesWebhook) {
        startWebhookServer(config)
    } else {
        embeddedServer(Netty, configure = {
            connector {
                host = "0.0.0.0"
                port = config.serverPort
            }
            if (config.monitoringPort > 0) {
                connector {
                    host = "127.0.0.1"
                    port = config.monitoringPort
                }
            }
        }) {
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
    val onboardingAnalytics = createOnboardingAnalytics(config.databaseUrl)
    val voiceAttempts = VoiceAttemptRecorder(createVoiceAttemptStore(config.databaseUrl), webhookScope)
    val audit = config.databaseUrl?.let { InteractionAudit(it, Path.of(config.auditAudioDir)) }
    if (audit == null) log.warn("Interaction audit disabled: DATABASE_URL is not configured")
    else webhookScope.launch {
        while (true) {
            try {
                audit.prune()
                for (voice in audit.pendingArtifacts()) {
                    val attemptId = voice.attemptId ?: continue
                    try {
                        val artifacts = ai.auditAttempt(attemptId)
                        for ((field, kind, status, prefix) in listOf(
                            listOf("transcript", "transcript", "ready", "stt"),
                            listOf("reply", "reply", "generated", "generated"),
                        )) {
                            val value = artifacts[field]?.takeIf { it.isNotBlank() } ?: continue
                            audit.record(com.eliteteam.speakingcoach.analytics.InteractionEvent(
                                id = "$prefix:$attemptId", chatId = voice.chatId,
                                direction = "internal", kind = kind, status = status,
                                receivedAt = voice.receivedAt, messageId = voice.messageId,
                                attemptId = attemptId, content = value,
                            ))
                        }
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        log.warn("AI audit artifact reconciliation failed attempt_id={}", attemptId, error)
                    } finally {
                        audit.markReconciled(voice.id)
                    }
                }
            }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Throwable) { log.warn("Interaction audit pruning failed", error) }
            delay(60 * 60 * 1_000L)
        }
    }
    val callEvents = CallEventRecorder(createCallEventStore(config.databaseUrl), webhookScope)
    val sessionClipQueue = SessionClipQueue(
        processor = ai,
        scope = webhookScope,
    )
    val telegramMetrics = TelegramOperationalMetrics()
    val behaviourContext = buildTelegramWebhookBehaviour(
        token, ai, sessionClipQueue, webhookScope, onboardingAnalytics, voiceAttempts, callEvents, telegramMetrics, audit,
    )
    val reminderRunner = ReminderRunner(
        claim = { mode -> ai.claimReminders(mode.wire) },
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
    val campaignRunner = LegacyCampaignRunner(ai, webhookScope) { chatId ->
        behaviourContext.sendMessage(
            ChatId(RawChatId(chatId)), legacyCampaignMessage(), replyMarkup = legacyCampaignKeyboard(),
        )
    }
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
            if (config.monitoringPort > 0) {
                connector {
                    host = "127.0.0.1"
                    port = config.monitoringPort
                }
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
                campaign = campaignRunner,
                onboarding = onboardingAnalytics,
                voiceAttempts = voiceAttempts,
                callEvents = callEvents,
            ),
            monitoring = MonitoringDashboard(
                source = HttpMetricsSource(ai),
                reminders = RunnerReminderAdmin(reminderRunner, webhookScope),
                campaign = campaignRunner,
                monitoringPort = config.monitoringPort,
                voiceAttempts = voiceAttempts,
                audit = audit,
                onboarding = onboardingAnalytics,
            ),
        ) {
            get("/internal/telegram-metrics/prometheus") {
                if (!telegramMetricsScrapeAllowed(call.request.local.localPort, config.monitoringPort)) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respondText(telegramMetrics.prometheus(), ContentType.parse("text/plain; version=0.0.4"))
                }
            }.hide()
            route("/telegram/webhook") {
                installSpeakingCoachWebhook(webhookSecret, behaviourContext, webhookScope, audit, telegramMetrics)
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
        server.stop()
        withContext(NonCancellable) { withTimeoutOrNull(2_000) { voiceAttempts.close() } }
        withContext(NonCancellable) { withTimeoutOrNull(2_000) { callEvents.close() } }
        behaviourContext.cancel()
        webhookScope.cancel()
        aiHttp.close()
        onboardingAnalytics?.close()
        audit?.close()
    }
}

@OptIn(ExperimentalKtorApi::class)
internal fun Application.module(
    config: AppConfig = AppConfig.fromEnv(),
    appApi: AppApi? = createAppApi(config),
    metricsSource: MetricsSource? = null,
    reminderAdmin: ReminderAdmin? = null,
    campaignAdmin: LegacyCampaignAdmin? = null,
) {
    val onboardingAnalytics = createOnboardingAnalytics(config.databaseUrl)
    onboardingAnalytics?.let { store ->
        monitor.subscribe(ApplicationStopped) { store.close() }
    }
    if (appApi != null) {
        installAppPlugins(appApi)
    }
    val source = metricsSource ?: ownedMetricsSource(config)
    val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val voiceAttempts = source?.let { VoiceAttemptRecorder(createVoiceAttemptStore(config.databaseUrl), voiceScope) }
    val callEvents = source?.let { CallEventRecorder(createCallEventStore(config.databaseUrl), voiceScope) }
    monitor.subscribe(ApplicationStopped) {
        runBlocking { withTimeoutOrNull(2_000) { voiceAttempts?.close() } }
        runBlocking { withTimeoutOrNull(2_000) { callEvents?.close() } }
        voiceScope.cancel()
    }
    installSpeakingCoachHttp(
        metrics = metricsDashboard(
            config,
            source,
            secureCookie = false,
            reminders = reminderAdmin,
            campaign = campaignAdmin,
            onboarding = onboardingAnalytics,
            voiceAttempts = voiceAttempts,
            callEvents = callEvents,
        ),
        monitoring = source?.let {
            MonitoringDashboard(
                source = it,
                reminders = reminderAdmin,
                campaign = campaignAdmin,
                monitoringPort = config.monitoringPort,
                voiceAttempts = voiceAttempts,
                onboarding = onboardingAnalytics,
            )
        },
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
    campaign: LegacyCampaignAdmin? = null,
    onboarding: com.eliteteam.speakingcoach.analytics.OnboardingAnalytics? = null,
    voiceAttempts: VoiceAttemptRecorder? = null,
    callEvents: CallEventRecorder? = null,
): MetricsDashboard? {
    val password = config.metricsPassword?.takeIf { it.isNotBlank() } ?: return null
    if (source == null) {
        return null
    }
    return MetricsDashboard(password, source, secureCookie, reminders, campaign, onboarding, voiceAttempts, callEvents)
}

private fun Application.ownedMetricsSource(config: AppConfig): MetricsSource? {
    if (config.metricsPassword.isNullOrBlank() && config.monitoringPort == 0) {
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
