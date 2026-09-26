package com.eliteteam.speakingcoach.telegram

import java.time.LocalDate

private const val NAME_SLOT = "{name}"

internal data class ReminderTemplate(val id: String, val text: String)

internal val REMINDER_TEMPLATES = listOf(
    ReminderTemplate("day_went", "Hey$NAME_SLOT! 👋 Got two minutes? Send me a voice message and tell me how your day went."),
    ReminderTemplate("looking_forward", "Speaky here 🙂 Quick English warm-up? Tell me one thing you're looking forward to this week."),
    ReminderTemplate("ate_today", "Missed you$NAME_SLOT! Record a short voice note: what did you eat today, and was it good?"),
    ReminderTemplate("smile_today", "Hi$NAME_SLOT! Let's keep your English streak alive 🔥 What made you smile today?"),
    ReminderTemplate("last_movie", "Time for a tiny speaking break ☕ Tell me about the last movie or show you watched."),
    ReminderTemplate("travel_tomorrow", "Hey$NAME_SLOT! If you could travel anywhere tomorrow, where would you go? Tell me in a voice message ✈️"),
    ReminderTemplate("describe_room", "Your daily English minute is here ⏱️ Describe your room or the place where you are right now."),
    ReminderTemplate("learned_today", "Hi$NAME_SLOT! What's one thing you learned today? Even a small one counts 🙂"),
    ReminderTemplate("friend_meet", "Let's chat$NAME_SLOT! Tell me about a friend you'd like me to meet 👋"),
    ReminderTemplate("weekend_plan", "Hey$NAME_SLOT! Quick one: what's your plan for the weekend? Send me a voice message 🎧"),
    ReminderTemplate("annoyed_today", "Practice makes progress 💪 Tell me about something that annoyed you today, and let it out in English."),
    ReminderTemplate("song_stuck", "Hi$NAME_SLOT! What song is stuck in your head lately? Tell me why you like it 🎶"),
    ReminderTemplate("favourite_food", "Speaky is bored without you 😅 Tell me about your favourite food and how to cook it."),
    ReminderTemplate("free_day", "Hey$NAME_SLOT! Imagine you have a free day with no plans. What would you do? 🌤️"),
    ReminderTemplate("best_part", "Two minutes of English a day goes a long way 🚀 What was the best part of your day?"),
    ReminderTemplate("hobby", "Hi$NAME_SLOT! Tell me about a hobby you have, or one you'd like to try 🎨"),
    ReminderTemplate("proud_month", "Let's talk$NAME_SLOT! What's something you're proud of this month? 🏆"),
    ReminderTemplate("morning_routine", "Hey$NAME_SLOT! Describe your morning routine in a voice message. I'll help you sound natural ☀️"),
    ReminderTemplate("weather", "Quick English check-in 🙂 What's the weather like where you are, and how does it make you feel?"),
    ReminderTemplate("skill_overnight", "Hi$NAME_SLOT! If you could learn any skill overnight, what would it be? Tell me 🧠"),
    ReminderTemplate("city_place", "Hey$NAME_SLOT! Tell me about a place in your city you really like 🏙️"),
    ReminderTemplate("book_podcast", "Ready for today's chat? 🎙️ Tell me about a book, podcast, or video you enjoyed recently."),
    ReminderTemplate("perfect_evening", "Hi$NAME_SLOT! What would your perfect evening look like? Send me a voice message 🌙"),
    ReminderTemplate("week_goal", "Hey$NAME_SLOT! Tell me one goal you have for this week, and I'll cheer you on 🎯"),
)

internal fun reminderTemplate(chatId: Long, day: LocalDate): ReminderTemplate {
    val size = REMINDER_TEMPLATES.size
    return REMINDER_TEMPLATES[(day.toEpochDay() + chatId.mod(size)).mod(size)]
}

internal fun reminderTemplateById(id: String): ReminderTemplate? = REMINDER_TEMPLATES.firstOrNull { it.id == id }

internal fun renderReminder(template: ReminderTemplate, firstName: String?): String {
    val name = firstName?.trim().orEmpty()
    val slot = if (name.isNotEmpty()) ", $name" else ""
    return template.text.replace(NAME_SLOT, slot)
}

internal fun reminderFirstName(name: String?): String? =
    name?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotEmpty() }
