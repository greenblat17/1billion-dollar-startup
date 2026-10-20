package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.analytics.createOnboardingAnalytics
import com.eliteteam.speakingcoach.analytics.createVoiceAttemptStore
import com.eliteteam.speakingcoach.analytics.VoiceAttemptRecorder
import com.eliteteam.speakingcoach.analytics.VoiceCostReceipt
import com.eliteteam.speakingcoach.analytics.VoiceCostStore
import com.eliteteam.speakingcoach.analytics.InteractionAudit
import com.eliteteam.speakingcoach.analytics.CallEventRecorder
import com.eliteteam.speakingcoach.analytics.createCallEventStore
import com.eliteteam.speakingcoach.ai.HttpClipClient
import com.eliteteam.speakingcoach.ai.HttpMetricsSource
import com.eliteteam.speakingcoach.load.installLoadTestRoutes
import com.eliteteam.speakingcoach.app.AppApi
import com.eliteteam.speakingcoach.app.createAppApi
import com.eliteteam.speakingcoach.app.installAppPlugins
import com.eliteteam.speakingcoach.app.installAppRoutes
import com.eliteteam.speakingcoach.speaking.SessionClipQueue
import com.eliteteam.speakingcoach.speaking.SessionId
import com.eliteteam.speakingcoach.telegram.TELEGRAM_WEBHOOK_SECRET_HEADER
import com.eliteteam.speakingcoach.telegram.buildTelegramWebhookBehaviour
import com.eliteteam.speakingcoach.telegram.installSpeakingCoachWebhook
import com.eliteteam.speakingcoach.telegram.ReminderAdmin
import com.eliteteam.speakingcoach.telegram.ReminderRunner
import com.eliteteam.speakingcoach.telegram.ReminderMode
import com.eliteteam.speakingcoach.telegram.LegacyCampaignAdmin
import com.eliteteam.speakingcoach.telegram.LegacyCampaignRunner
import com.eliteteam.speakingcoach.telegram.RunnerReminderAdmin
import com.eliteteam.speakingcoach.telegram.launchDailyReminder
import com.eliteteam.speakingcoach.telegram.FollowupRunner
import com.eliteteam.speakingcoach.telegram.FollowupStore
import com.eliteteam.speakingcoach.telegram.ReminderButton
import com.eliteteam.speakingcoach.telegram.ReminderButtonStore
import com.eliteteam.speakingcoach.telegram.PracticeReminderCopy
import com.eliteteam.speakingcoach.telegram.practiceOpeningQuestion
import com.eliteteam.speakingcoach.telegram.noInlineKeyboard
import com.eliteteam.speakingcoach.telegram.followupKeyboard
import com.eliteteam.speakingcoach.telegram.practiceReminderCopy
import com.eliteteam.speakingcoach.telegram.launchFollowups
import com.eliteteam.speakingcoach.telegram.SoftNudgeStore
import com.eliteteam.speakingcoach.telegram.SoftNudgeRunner
import com.eliteteam.speakingcoach.telegram.softNudgeText
import com.eliteteam.speakingcoach.telegram.softNudgeKeyboard
import com.eliteteam.speakingcoach.telegram.launchSoftNudges
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.bot.exceptions.RequestException
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.MessageId
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
import io.ktor.client.engine.okhttp.OkHttp
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
import okhttp3.ConnectionPool
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.UUID

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
    val followupStore = config.databaseUrl?.takeIf { it.isNotBlank() }?.let(::FollowupStore)
    val reminderButtons = config.databaseUrl?.takeIf { it.isNotBlank() }?.let(::ReminderButtonStore)
    val softNudges = config.databaseUrl?.takeIf { it.isNotBlank() }?.let(::SoftNudgeStore)
    val voiceAttempts = VoiceAttemptRecorder(createVoiceAttemptStore(config.databaseUrl), webhookScope)
    val voiceCost = config.databaseUrl?.let(::VoiceCostStore)
    if (voiceCost != null) webhookScope.launch {
        while (true) {
            try {
                for (batch in 1..20) {
                    val events = ai.voiceCostEvents().events
                    if (events.isEmpty()) break
                    voiceCost.upsert(events)
                    ai.acknowledgeVoiceCost(events.map { VoiceCostReceipt(it.callId, it.revision) })
                    if (events.size < 100) break
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.warn("Voice cost sync failed", error)
            }
            delay(10_000)
        }
    }
    if (voiceCost != null) webhookScope.launch {
        while (true) {
            delay(24 * 60 * 60 * 1_000L)
            try { voiceCost.prune() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { log.warn("Voice cost pruning failed", error) }
        }
    }
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
        token, ai, sessionClipQueue, webhookScope, onboardingAnalytics, voiceAttempts, callEvents, voiceCost,
        telegramMetrics, audit, config.aiInternalToken, followupStore, reminderButtons,
    )
    suspend fun clearOldReminderButton(button: ReminderButton) {
        try {
            behaviourContext.editMessageReplyMarkup(ChatId(RawChatId(button.chatId)),
                MessageId(button.messageId), replyMarkup = noInlineKeyboard)
            reminderButtons?.markCleared(button.token)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (error is RequestException && error.response.errorCode == 400) {
                log.warn("Telegram cannot edit superseded reminder button {}", button.token, error)
                reminderButtons?.markCleared(button.token)
            } else log.warn("Could not clear superseded reminder button {}", button.token, error)
        }
    }
    suspend fun sendReminderWithButton(sessionId: String, chatId: Long, text: String,
                                       action: String, runId: String = "", stage: String? = null,
                                       openingQuestion: String? = null, source: String? = null,
                                       copyKind: String? = null): Long {
        val token = UUID.randomUUID()
        val message = behaviourContext.sendMessage(ChatId(RawChatId(chatId)), text,
            replyMarkup = followupKeyboard(action, runId, stage,
                reminderToken = if (reminderButtons != null) token else null))
        if (reminderButtons != null) {
            val previous = try {
                reminderButtons.activate(sessionId, ReminderButton(token, chatId,
                    message.messageId.long, openingQuestion, source, copyKind))
            } catch (error: Throwable) {
                try { behaviourContext.editMessageReplyMarkup(ChatId(RawChatId(chatId)),
                    message.messageId, replyMarkup = noInlineKeyboard) }
                catch (editError: Throwable) { log.warn("Could not clear unregistered reminder button", editError) }
                throw error
            }
            if (previous != null) clearOldReminderButton(previous)
        }
        return message.messageId.long
    }
    val followupRunner = followupStore?.let { store ->
        FollowupRunner(
            store = store,
            state = { candidate ->
                val current = ai.onboardingState(SessionId(candidate.sessionId), "followup:check:${candidate.runId}")
                current.runId to current.status
            },
            reminderTime = { sessionId -> ai.reminderTime(SessionId(sessionId)) },
            send = { claim ->
                val copy = if (claim.candidate.stage == "practice" && !claim.finalMessage)
                    practiceReminderCopy(claim.candidate.sessionId, claim.day, claim.copy.text,
                        practiceOpeningQuestion(claim.copy.id)) {
                        ai.personalizedReminder(SessionId(it))
                    } else PracticeReminderCopy(claim.copy.text, "general",
                        if (claim.action == "practice") practiceOpeningQuestion(claim.copy.id) else "")
                val messageId = sendReminderWithButton(claim.candidate.sessionId,
                    claim.candidate.chatId, copy.text, claim.action, claim.candidate.runId,
                    claim.candidate.stage, copy.openingQuestion.takeIf { claim.action == "practice" },
                    if (claim.candidate.stage == "practice") "inferred_practice" else "onboarding", copy.kind)
                com.eliteteam.speakingcoach.telegram.FollowupSend(messageId, copy.kind)
            },
        )
    }
    val reminderRunner = ReminderRunner(
        claim = { mode -> ai.claimReminders(mode.wire) },
        report = ai::reportReminders,
        send = { sessionId, chatId, copy, mode -> sendReminderWithButton(sessionId, chatId,
            copy.text, "practice", openingQuestion = copy.openingQuestion,
            source = if (mode == ReminderMode.AUTO) "configured_practice" else null,
            copyKind = copy.kind) },
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
        followup = { sessionId -> followupRunner?.forDailyReminder(sessionId) },
        personalize = { sessionId -> ai.personalizedReminder(SessionId(sessionId)) },
        recordAutoAttempt = { sessionId, at, delivery ->
            reminderButtons?.recordConfiguredAttempt(sessionId, at.toLocalDate(), at.toInstant(),
                delivery.status, delivery.errorCode)
        },
    )
    val softNudgeRunner = softNudges?.let { store ->
        SoftNudgeRunner(store,
            active = { candidate ->
                val session = SessionId(candidate.sessionId)
                if (candidate.kind == "onboarding") {
                    val state = ai.onboardingState(session, "soft-nudge:check:${candidate.flowId}")
                    state.runId == candidate.flowId && state.status == "active"
                } else {
                    val state = ai.callStatus(session)
                    state.active && state.callId == candidate.flowId
                }
            },
            send = { candidate ->
                behaviourContext.sendMessage(ChatId(RawChatId(candidate.chatId)), softNudgeText(candidate.kind),
                    replyMarkup = softNudgeKeyboard(candidate))
            })
    }
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
        val adminUsage = if (config.metricsPassword.isNullOrBlank()) null else
            config.databaseUrl?.let(::PostgresAdminUsageStore)
        adminUsage?.let { store -> monitor.subscribe(ApplicationStopped) { store.close() } }
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
                usage = adminUsage,
                voiceCost = voiceCost,
                softNudges = softNudges,
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
            if (config.loadTestEnabled) {
                installLoadTestRoutes(config, ai, sessionClipQueue)
            }
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
    if (followupRunner != null) webhookScope.launchFollowups(followupRunner)
    if (softNudgeRunner != null) webhookScope.launchSoftNudges(softNudgeRunner)
    if (reminderButtons != null) webhookScope.launch {
        while (true) {
            try { reminderButtons.inactiveToClear().forEach { clearOldReminderButton(it) } }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { log.warn("Reminder button cleanup failed", error) }
            delay(60_000)
        }
    }
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
        followupStore?.close()
        reminderButtons?.close()
        softNudges?.close()
        audit?.close()
        voiceCost?.close()
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
    val adminUsage = if (config.metricsPassword.isNullOrBlank()) null else
        config.databaseUrl?.let(::PostgresAdminUsageStore)
    val softNudges = if (config.metricsPassword.isNullOrBlank()) null else
        config.databaseUrl?.let(::SoftNudgeStore)
    monitor.subscribe(ApplicationStopped) {
        runBlocking { withTimeoutOrNull(2_000) { voiceAttempts?.close() } }
        runBlocking { withTimeoutOrNull(2_000) { callEvents?.close() } }
        voiceScope.cancel()
        adminUsage?.close()
        softNudges?.close()
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
            usage = adminUsage,
            softNudges = softNudges,
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
    usage: AdminUsageStore? = null,
    voiceCost: VoiceCostStore? = null,
    softNudges: SoftNudgeStore? = null,
): MetricsDashboard? {
    val password = config.metricsPassword?.takeIf { it.isNotBlank() } ?: return null
    if (source == null) {
        return null
    }
    return MetricsDashboard(password, source, secureCookie, reminders, campaign, onboarding, voiceAttempts, callEvents, usage,
        config.openRouterRatesFile?.let(Path::of), voiceCost, softNudges)
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

internal fun speakingCoachAiHttpClient(): HttpClient = HttpClient(OkHttp) {
    expectSuccess = false
    engine {
        config {
            // AI closes idle connections after 5 seconds; retire them on CMP first.
            connectionPool(ConnectionPool(5, 3, TimeUnit.SECONDS))
        }
    }
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
            },
        )
    }
}
