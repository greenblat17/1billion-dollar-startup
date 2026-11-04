package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.analytics.VocabularyFact
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

private val vocabularyZone = ZoneId.of("Europe/Moscow")
private fun VocabularyFact.day(): LocalDate = time.atZone(vocabularyZone).toLocalDate()
private fun ratio(numerator: Int, denominator: Int): String =
    if (denominator == 0) "—" else "$numerator / $denominator (${numerator * 100 / denominator}%)"
private fun percentile(sample: List<Long>, percent: Double): String =
    if (sample.isEmpty()) "—" else "${sample.sorted()[(sample.size * percent).toInt().coerceAtMost(sample.lastIndex)]} мс"

internal fun vocabularyDashboardPage(facts: List<VocabularyFact>, days: Int, format: Int?,
                                     offset: Int, writeFailures: Long, memoryOnly: Boolean,
                                     sourceHealthy: Boolean = true, lastSyncAt: Instant? = null,
                                     backlogAt: Instant? = null,
                                     now: Instant = Instant.now()): String {
    val today = now.atZone(vocabularyZone).toLocalDate()
    val from = today.minusDays((days - 1).toLong())
    val createdAll = facts.filter { it.kind == "lesson_created" }.associateBy { it.lessonId }
    val openings = facts.filter { it.kind == "opening_delivered" }.map { it.lessonId }.toSet()
    val attempts = facts.filter { it.kind == "start_pressed" && !it.day().isBefore(from) && !it.day().isAfter(today) }
    val created = createdAll.values.filter { !it.day().isBefore(from) && !it.day().isAfter(today) &&
        (format == null || it.format == format) }.sortedByDescending { it.time }
    val ids = created.map { it.lessonId }.toSet()
    val selectedFacts = facts.filter { it.lessonId in ids }
    val grouped = selectedFacts.groupBy { it.lessonId }
    val complete = created.count { lesson -> grouped[lesson.lessonId].orEmpty().any { it.kind == "lesson_finished" && it.category == "complete" } }
    val openingCount = created.count { it.lessonId in openings }
    val firstAnswer = created.count { lesson -> grouped[lesson.lessonId].orEmpty().any { it.kind == "answer_received" } }
    val selectedWords = created.flatMap { lesson -> lesson.words.map { lesson.lessonId to it.itemId } }.toSet()
    val usedWords = selectedFacts.filter { it.kind == "answer" && it.phase == "Use" && it.category == "used" }
        .map { it.lessonId to it.itemId }.toSet()
    val cardFacts = selectedFacts.filter { it.kind == "answer" && it.phase == "Word game" }
    val cardCorrect = cardFacts.filter { it.category == "correct" }.distinctBy { it.lessonId to it.itemId }
    val cardHinted = cardCorrect.count { it.hinted }
    val cardRevealed = cardFacts.filter { it.category == "give_up" }.groupBy { it.lessonId to it.itemId }
        .count { it.value.size >= 2 }
    val deliveredTimes = selectedFacts.filter { it.kind == "answer_delivered" && it.milliseconds > 0 }
        .map { it.milliseconds }
    val errors = facts.filter { it.kind in setOf("error", "telegram_error") &&
        (it.lessonId in ids || (it.lessonId.isEmpty() && !it.day().isBefore(from) && !it.day().isAfter(today))) }
    val resolved = selectedFacts.filter { it.kind in setOf("answer_delivered", "retry_succeeded") }
    val unresolved = errors.filter { error -> resolved.none { it.lessonId == error.lessonId &&
        (error.messageId == 0L || it.messageId == error.messageId) && it.time >= error.time } }
        .map { it.lessonId }.toSet()
    val countBySize = (1..5).joinToString("") { count ->
        val matching = created.filter { it.wordCount == count }
        "<tr><td>$count</td><td>${matching.size}</td><td>${matching.mapNotNull { it.chatId }.distinct().size}</td></tr>"
    }
    val stopped = linkedMapOf<String, Int>()
    for (lesson in created) {
        val events = grouped[lesson.lessonId].orEmpty()
        val final = events.firstOrNull { it.kind == "lesson_finished" }
        val lastActivity = events.maxOfOrNull { it.time } ?: lesson.time
        val lastError = events.lastOrNull { it.kind in setOf("error", "telegram_error", "answer_delivered") }
        val label = when {
            final?.category == "complete" -> "Полностью завершён"
            final != null && events.any { it.kind == "end_pressed" } -> "Завершён кнопкой · частично"
            final != null -> "Завершён после бездействия · частично"
            lastActivity.isAfter(now.minusSeconds(3_600)) -> "Ещё активен"
            lesson.lessonId !in openings -> "Открытие не доставлено"
            lastError?.kind in setOf("error", "telegram_error") -> "Сбой без доставленного ответа"
            events.none { it.kind == "answer_received" } -> "Ожидание первого ответа"
            else -> {
                val last = events.lastOrNull { it.kind == "answer" || it.kind == "transition" }
                "${last?.phase?.ifEmpty { "Неизвестно" } ?: "Неизвестно"}, слово ${last?.position?.plus(1) ?: 1}"
            }
        }
        stopped[label] = (stopped[label] ?: 0) + 1
    }
    val stopRows = stopped.entries.joinToString("") { (label, count) ->
        "<tr><td>${escapeHtml(label)}</td><td>$count</td><td>${ratio(count, created.size)}</td></tr>"
    }
    val saved = facts.filter { it.kind == "word_saved" && !it.day().isBefore(from) && !it.day().isAfter(today) }
    val savedManual = saved.filter { it.source == "manual" }
    val savedReview = saved.filter { it.source != "manual" }
    val addAttempts = facts.count { it.kind == "word_add_attempt" && !it.day().isBefore(from) && !it.day().isAfter(today) }
    val proposals = facts.count { it.kind == "word_proposed" && !it.day().isBefore(from) && !it.day().isAfter(today) }
    val selectedItems = created.flatMap { it.words.map { word -> it.chatId to word.itemId } }.toSet()
    val sourceRows = listOf("manual" to savedManual, "review" to savedReview).joinToString("") { (source, values) ->
        val used = values.count { (it.chatId to it.itemId) in selectedItems }
        "<tr><td>${if (source == "manual") "Вручную" else "Из звонка"}</td><td>${values.size}</td>" +
            "<td>${values.mapNotNull { it.chatId }.distinct().size}</td><td>${ratio(used, values.size)}</td></tr>"
    }
    val periodStarts = facts.filter { it.kind == "opening_delivered" && it.lessonId in createdAll }
    val repeatUsers = periodStarts.filter { !it.day().isBefore(from) && !it.day().isAfter(today) }
        .groupBy { it.chatId }.count { it.value.map { fact -> fact.lessonId }.distinct().size >= 2 }
    val firstByChat = periodStarts.groupBy { it.chatId }.mapValues { entry -> entry.value.minBy { it.time } }
    val retentionRows = listOf(1, 7, 30).joinToString("") { n ->
        val eligible = firstByChat.filterValues { !it.day().isBefore(from) && !it.day().isAfter(today) &&
            it.day().plusDays(n.toLong()).isBefore(today) }
        val returned = eligible.count { (chat, first) ->
            periodStarts.any { it.chatId == chat && it.day() == first.day().plusDays(n.toLong()) && it.lessonId != first.lessonId }
        }
        "<tr><td>D$n</td><td>${ratio(returned, eligible.size)}</td></tr>"
    }
    val cohortRows = firstByChat.entries.groupBy { (_, first) ->
        first.day().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }.toSortedMap(compareByDescending { it }).entries.take(8).joinToString("") { (week, members) ->
        val cells = listOf(1, 7, 30).joinToString("") { n ->
            val eligible = members.filter { (_, first) -> first.day().plusDays(n.toLong()).isBefore(today) }
            val returned = eligible.count { (chat, first) -> periodStarts.any {
                it.chatId == chat && it.lessonId != first.lessonId &&
                    it.day() == first.day().plusDays(n.toLong())
            } }
            "<td>${ratio(returned, eligible.size)}</td>"
        }
        "<tr><td>$week</td><td>${members.size}</td>$cells</tr>"
    }
    val due = facts.filter { it.kind == "word_due" && it.dueAt > 0 }
        .groupBy { it.chatId to it.itemId }.values.map { it.minBy { fact -> fact.time } }
        .filter {
        Instant.ofEpochMilli((it.dueAt * 1000).toLong()).atZone(vocabularyZone).toLocalDate() in from..today &&
        it.dueAt < now.epochSecond }
    val revisited = due.filter { dueFact -> createdAll.values.any { lesson ->
        lesson.chatId == dueFact.chatId && lesson.time.epochSecond >= dueFact.dueAt &&
            lesson.words.any { it.itemId == dueFact.itemId } } }
    val allUsed = facts.filter { it.kind == "answer" && it.phase == "Use" && it.category == "used" }
    val repeatUsed = revisited.count { dueFact -> allUsed.any { used ->
        val lessonId = used.lessonId
        val itemId = used.itemId
        itemId == dueFact.itemId && createdAll[lessonId]?.chatId == dueFact.chatId &&
            createdAll[lessonId]?.time?.epochSecond?.toDouble()?.let { it >= dueFact.dueAt } == true } }
    val errorRows = errors.groupBy { "${it.phase.ifEmpty { "telegram" }} / ${it.category.ifEmpty { "unknown" }}" }
        .entries.sortedByDescending { it.value.size }.joinToString("") { (label, values) ->
            "<tr><td>${escapeHtml(label)}</td><td>${values.size}</td><td>${values.map { it.lessonId }.distinct().size}</td></tr>"
        }
    val retry = selectedFacts.count { it.kind == "retry_pressed" }
    val retryOk = selectedFacts.count { it.kind == "retry_succeeded" }
    val selectedMenu = facts.count { it.kind == "word_selected" && it.category == "selected" &&
        !it.day().isBefore(from) && !it.day().isAfter(today) }
    val recent = created.drop(offset).take(25).joinToString("") { lesson ->
        val events = grouped[lesson.lessonId].orEmpty()
        val result = events.firstOrNull { it.kind == "lesson_finished" }?.category ?: "активен/неизвестно"
        "<tr><td><a href=\"$VOCABULARY_ANALYTICS_PATH/${lesson.lessonId}\">${escapeHtml(lesson.lessonId)}</a></td>" +
            "<td>${lesson.day()}</td><td>${lesson.chatId}</td><td>${lesson.format}</td>" +
            "<td>${lesson.wordCount}</td><td>${escapeHtml(result)}</td><td>${events.count { it.kind in setOf("error", "telegram_error") }}</td></tr>"
    }
    val next = if (offset + 25 < created.size) "<a href=\"$VOCABULARY_ANALYTICS_PATH?days=$days&format=${format ?: ""}&offset=${offset + 25}\">Следующие 25 →</a>" else ""
    return """
        <!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Vocabulary Builder · аналитика</title>${pageStyle()}</head><body>
        ${adminTabs(VOCABULARY_ANALYTICS_PATH)}<h1>Vocabulary Builder</h1>
        <p class="meta">$from — $today · Europe/Moscow. Сегодня предварительно. ${if (memoryOnly) "Данные в памяти процесса." else "Postgres."} Источник AI: ${if (sourceHealthy) "доступен" else "недоступен"}; последняя сверка ${lastSyncAt ?: "—"}; ${if (backlogAt == null) "отставание не обнаружено" else "есть очередь событий с $backlogAt"}. Ошибки записи: $writeFailures. История начинается с включения сбора.</p>
        <form class="inline" method="get"><label>Период <select name="days">${listOf(1, 7, 30).joinToString("") { "<option value=\"$it\"${if (days == it) " selected" else ""}>${if (it == 1) "Сегодня" else "$it дней"}</option>" }}</select></label>
        <label>Формат <select name="format"><option value="">Все</option>${listOf(3, 4, 5).joinToString("") { "<option value=\"$it\"${if (format == it) " selected" else ""}>$it</option>" }}</select></label><button>Показать</button></form>
        <dl>${card("Попытки / созданные уроки", "${attempts.size} / ${created.size}")}
        ${card("Людей / уроков", "${created.mapNotNull { it.chatId }.distinct().size} / ${created.size}")}
        ${card("Открытие доставлено", ratio(openingCount, created.size))}
        ${card("Первый ответ", ratio(firstAnswer, openingCount))}
        ${card("Полностью завершены", ratio(complete, created.size))}
        ${card("Собственные фразы", ratio(usedWords.size, selectedWords.size))}
        ${card("Карточки верно сразу / с подсказкой / раскрыты", "${cardCorrect.size - cardHinted} / $cardHinted / $cardRevealed")}
        ${card("Ожидание p50 / p95", "${percentile(deliveredTimes, 0.5)} / ${percentile(deliveredTimes, 0.95)} · n=${deliveredTimes.size}")}
        ${card("Нерешённые ошибки", ratio(unresolved.size, created.size))}</dl>
        <h2>Где остановились</h2><table><tr><th>Этап</th><th>Уроков</th><th>Доля</th></tr>$stopRows</table>
        <h2>Сколько слов берут</h2><table><tr><th>Слов</th><th>Уроков</th><th>Людей</th></tr>$countBySize</table>
        <p class="meta">Выборов слова в меню: $selectedMenu. Выбор без запуска не считается практикой.</p>
        <h2>Откуда слова</h2><table><tr><th>Источник</th><th>Сохранено</th><th>Людей</th><th>Попало в урок</th></tr>$sourceRows</table>
        <p class="meta">Ручной ввод: попыток $addAttempts, карточек предложено $proposals, сохранено ${savedManual.size}. Из review: примеры ${savedReview.count { it.source == "review_example" }}, предложения ${savedReview.count { it.source == "review_suggestion" }}.</p>
        <h2>Возвращаемость к урокам</h2><p class="meta">Первый доставленный урок в доступной истории; возврат ровно через N московских дней. Только зрелые когорты.</p>
        <table><tr><th>День</th><th>Вернулись / могли вернуться</th></tr>$retentionRows</table>
        <h3>Недельные когорты первого урока</h3><table><tr><th>Неделя</th><th>Людей</th><th>D1</th><th>D7</th><th>D30</th></tr>$cohortRows</table>
        <p class="meta">Первая неделя в доступной истории может включать людей, занимавшихся до начала сбора.</p>
        <p class="meta">Людей с двумя и более доставленными уроками за период: $repeatUsers.</p>
        <h2>Повторение слов</h2><dl>${card("Вернулись к слову после 24 ч", ratio(revisited.size, due.size))}
        ${card("Употребили при повторе", ratio(repeatUsed, revisited.size))}</dl>
        <h2>Ошибки</h2><table><tr><th>Этап / тип</th><th>Событий</th><th>Уроков</th></tr>$errorRows</table>
        <p class="meta">Успешные Retry / нажатия: $retryOk / $retry. Повтор одного Telegram сообщения не увеличивает число уроков.</p>
        <h2>Последние уроки</h2><table><tr><th>ID</th><th>Дата</th><th>Чат</th><th>Формат</th><th>Слов</th><th>Итог</th><th>Ошибок</th></tr>$recent</table><p>$next</p>
        <p class="meta">Категория used — оценка репетитора. Карточка и повторный урок показаны отдельно. Доставка означает приём Telegram API, не прослушивание.</p>
        </body></html>
    """.trimIndent()
}

internal fun vocabularyLessonPage(id: String, facts: List<VocabularyFact>): String {
    val rows = facts.sortedBy { it.time }.take(500).joinToString("") { fact ->
        "<tr><td>${fact.time.atZone(vocabularyZone)}</td><td>${escapeHtml(fact.kind)}</td>" +
            "<td>${escapeHtml(fact.phase)}</td><td>${fact.position}</td>" +
            "<td>${escapeHtml(fact.category)}</td></tr>"
    }
    return """<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">${pageStyle()}</head><body>
        ${adminTabs(VOCABULARY_ANALYTICS_PATH)}<h1>Урок ${escapeHtml(id)}</h1><p><a href="$VOCABULARY_ANALYTICS_PATH">← Все уроки</a></p>
        <table><tr><th>Время</th><th>Событие</th><th>Этап</th><th>Позиция</th><th>Категория</th></tr>$rows</table></body></html>"""
}
