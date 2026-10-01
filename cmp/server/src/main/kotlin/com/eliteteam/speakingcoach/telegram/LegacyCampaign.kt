package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.HttpClipClient
import com.eliteteam.speakingcoach.ai.LegacyCampaignStatus
import dev.inmo.tgbotapi.extensions.utils.types.buttons.dataButton
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.message.textsources.TextSourcesList
import dev.inmo.tgbotapi.utils.bold
import dev.inmo.tgbotapi.utils.buildEntities
import dev.inmo.tgbotapi.utils.regular
import dev.inmo.tgbotapi.utils.row
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

internal const val LEGACY_CAMPAIGN_BEFORE =
    "Привет! Это Саша, создатель Speaky 👋\n\n" +
            "За последние дни мы сильно обновили Speaky 🚀\n\n" +
            "Теперь твой English Buddy лучше понимает уровень языка, точнее подбирает сложность разговора " +
            "и даёт более полезный разбор речи ✨\n\n" +
            "А ещё мы полностью переделали то, как Speaky знакомится с тобой и понимает, " +
            "как лучше подстраиваться под твой английский.\n\n" +
            "Чтобы всё это работало корректно "
internal const val LEGACY_CAMPAIGN_BOLD =
    "именно для тебя, очень важно пройти новый onboarding и ещё раз познакомиться со Speaky 🎙️"
internal const val LEGACY_CAMPAIGN_AFTER =
    ".\n\n" +
            "Он займёт около 2 минут: ты немного поговоришь со Speaky, а он определит твой текущий уровень " +
            "и поймёт, как лучше вести дальнейшие разговоры\n\n" +
            "Если пропустить onboarding, Speaky просто будет знать о твоём английском меньше, " +
            "поэтому персонализация будет хуже\n\n" +
            "И если после него что-то покажется странным, неудобным или, наоборот, понравится — " +
            "напиши мне: @alexgusev93. Я читаю каждое сообщение и отвечаю сам 🙌"

internal fun legacyCampaignMessage(): TextSourcesList = buildEntities {
    regular(LEGACY_CAMPAIGN_BEFORE)
    bold(LEGACY_CAMPAIGN_BOLD)
    regular(LEGACY_CAMPAIGN_AFTER)
}

internal fun legacyCampaignKeyboard(): InlineKeyboardMarkup = inlineKeyboard {
    row { dataButton("🎙 Пройти onboarding", LEGACY_ONBOARDING_CALLBACK) }
}

internal interface LegacyCampaignAdmin {
    suspend fun status(): LegacyCampaignStatus

    fun startAll(): Boolean

    suspend fun sendTest(chatId: Long): Boolean
}

internal class LegacyCampaignRunner(
    private val ai: HttpClipClient,
    private val scope: CoroutineScope,
    private val send: suspend (Long) -> Unit,
) : LegacyCampaignAdmin {
    private val log = LoggerFactory.getLogger("LegacyCampaignRunner")
    private val mutex = Mutex()

    override suspend fun status(): LegacyCampaignStatus = ai.legacyCampaignStatus()

    override fun startAll(): Boolean {
        if (!mutex.tryLock()) return false
        scope.launch {
            try {
                runCampaign()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.error("Legacy onboarding campaign stopped", error)
            } finally {
                mutex.unlock()
            }
        }
        return true
    }

    override suspend fun sendTest(chatId: Long): Boolean = try {
        send(chatId)
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        log.warn("Legacy onboarding test send failed for tg-{}", chatId, error)
        false
    }

    private suspend fun runCampaign() {
        if (!ai.legacyCampaignStatus().ready) return
        while (true) {
            val batch = ai.claimLegacyCampaign()
            if (batch.isEmpty()) break
            for (chatId in batch) {
                val result = deliver(chatId)
                try {
                    ai.reportLegacyCampaign(chatId, result)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    log.warn("Failed to report legacy campaign delivery for tg-{}", chatId, error)
                }
                delay(120.milliseconds)
            }
        }
    }

    private suspend fun deliver(chatId: Long): String {
        var retried = false
        while (true) {
            try {
                send(chatId)
                return "sent"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                when (val failure = telegramSendFailure(error)) {
                    SendFailure.Blocked -> return "blocked"
                    is SendFailure.RetryAfter -> {
                        if (retried) return "failed"
                        retried = true
                        delay(failure.wait)
                    }
                    SendFailure.Failed -> {
                        log.warn("Failed to send legacy campaign to tg-{}", chatId, error)
                        return "failed"
                    }
                }
            }
        }
    }
}
