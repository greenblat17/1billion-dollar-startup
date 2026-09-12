package com.eliteteam.speakingcoach

data class AppConfig(
    val telegramBotToken: String?,
    val aiServiceBaseUrl: String,
    val serverPort: Int,
) {
    companion object {
        fun fromEnv(): AppConfig = AppConfig(
            telegramBotToken = env("TELEGRAM_BOT_TOKEN")?.takeIf { it.isNotBlank() },
            aiServiceBaseUrl = env("AI_SERVICE_BASE_URL") ?: "http://127.0.0.1:8090",
            serverPort = env("SERVER_PORT")?.toIntOrNull() ?: 8080,
        )

        private fun env(name: String): String? = System.getenv(name)?.trim()
    }
}
