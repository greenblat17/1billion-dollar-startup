package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.ReminderReport
import com.eliteteam.speakingcoach.ai.ReminderSendResult
import com.eliteteam.speakingcoach.ai.ReminderTarget
import dev.inmo.tgbotapi.bot.exceptions.RequestException
import dev.inmo.tgbotapi.bot.exceptions.TooMuchRequestsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.slf4j.LoggerFactory
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val TELEGRAM_FORBIDDEN = 403
private const val STATUS_SENT = "sent"
private const val STATUS_BLOCKED = "blocked"
private const val STATUS_FAILED = "failed"

private val log = LoggerFactory.getLogger("ReminderRunner")

internal enum class ReminderMode(val wire: String) {
    AUTO("auto"),
    MANUAL("manual"),
}

internal data class RoundOutcome(
    val claimed: Int,
    val sent: Int,
    val blocked: Int,
    val failed: Int,
)

internal sealed interface SendFailure {
    data object Blocked : SendFailure
    data class RetryAfter(val wait: Duration) : SendFailure
    data object Failed : SendFailure
}

internal fun telegramSendFailure(error: Throwable): SendFailure = when {
    error is TooMuchRequestsException -> SendFailure.RetryAfter(error.retryAfter.seconds.seconds)
    error is RequestException && error.response.errorCode == TELEGRAM_FORBIDDEN -> SendFailure.Blocked
    else -> SendFailure.Failed
}

internal interface ReminderAdmin {
    fun startAll(): Boolean

    suspend fun sendTest(chatId: Long, templateId: String?): Boolean
}

internal class ReminderRunner(
    private val claim: suspend () -> List<ReminderTarget>,
    private val report: suspend (ReminderReport) -> Unit,
    private val send: suspend (chatId: Long, text: String) -> Unit,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(REMINDER_ZONE) },
    private val pause: Duration = 50.milliseconds,
    private val classify: (Throwable) -> SendFailure = ::telegramSendFailure,
) {
    private val mutex = Mutex()

    val isRunning: Boolean
        get() = mutex.isLocked

    suspend fun runRound(mode: ReminderMode): RoundOutcome? {
        if (!mutex.tryLock()) {
            return null
        }
        try {
            val started = now()
            val day = started.toLocalDate()
            val targets = claim()
            val results = mutableListOf<ReminderSendResult>()
            for (target in targets) {
                val chatId = reminderChatId(target.sessionId) ?: continue
                val template = reminderTemplate(chatId, day)
                val status = deliver(chatId, renderReminder(template, reminderFirstName(target.name)))
                results += ReminderSendResult(target.sessionId, template.id, status)
                delay(pause)
            }
            val outcome = RoundOutcome(
                claimed = targets.size,
                sent = results.count { it.status == STATUS_SENT },
                blocked = results.count { it.status == STATUS_BLOCKED },
                failed = results.count { it.status == STATUS_FAILED },
            )
            try {
                report(
                    ReminderReport(
                        mode = mode.wire,
                        startedAt = isoSeconds(started),
                        finishedAt = isoSeconds(now()),
                        claimed = targets.size,
                        results = results,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.warn("Failed to report reminder round: {}", error.message)
            }
            log.info("Reminder round {} {}: {}", mode.wire, day, outcome)
            return outcome
        } finally {
            mutex.unlock()
        }
    }

    suspend fun sendTest(chatId: Long, templateId: String?): Boolean {
        val template = if (templateId == null) {
            reminderTemplate(chatId, now().toLocalDate())
        } else {
            reminderTemplateById(templateId) ?: return false
        }
        return deliver(chatId, renderReminder(template, null)) == STATUS_SENT
    }

    private suspend fun deliver(chatId: Long, text: String): String {
        var retried = false
        while (true) {
            try {
                send(chatId, text)
                return STATUS_SENT
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                when (val failure = classify(error)) {
                    SendFailure.Blocked -> return STATUS_BLOCKED
                    is SendFailure.RetryAfter -> {
                        if (retried) {
                            return STATUS_FAILED
                        }
                        retried = true
                        delay(failure.wait)
                    }
                    SendFailure.Failed -> {
                        log.warn("Failed to send reminder to tg-{}: {}", chatId, error.message)
                        return STATUS_FAILED
                    }
                }
            }
        }
    }

    private fun now(): ZonedDateTime = clock().withZoneSameInstant(REMINDER_ZONE)
}

internal class RunnerReminderAdmin(
    private val runner: ReminderRunner,
    private val scope: CoroutineScope,
) : ReminderAdmin {
    override fun startAll(): Boolean {
        if (runner.isRunning) {
            return false
        }
        scope.launch {
            try {
                runner.runRound(ReminderMode.MANUAL)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.error("Manual reminder round failed", error)
            }
        }
        return true
    }

    override suspend fun sendTest(chatId: Long, templateId: String?): Boolean = runner.sendTest(chatId, templateId)
}

private fun isoSeconds(moment: ZonedDateTime): String =
    moment.truncatedTo(ChronoUnit.SECONDS).toOffsetDateTime().toString()
