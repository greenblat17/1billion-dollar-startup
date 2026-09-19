package com.eliteteam.speakingcoach

data class AppConfig(
    val telegramBotToken: String?,
    val telegramWebhookUrl: String?,
    val telegramWebhookSecret: String?,
    val aiServiceBaseUrl: String,
    val aiInternalToken: String,
    val serverPort: Int,
    val tlsCertPath: String,
    val tlsKeyPath: String,
    val jwtSecret: String?,
    val databaseUrl: String?,
) {
    val usesWebhook: Boolean
        get() = !telegramWebhookUrl.isNullOrBlank()

    init {
        if (!telegramBotToken.isNullOrBlank()) {
            require(!telegramWebhookUrl.isNullOrBlank()) {
                "TELEGRAM_WEBHOOK_URL is required when TELEGRAM_BOT_TOKEN is set"
            }
        }
        if (usesWebhook) {
            require(!telegramWebhookSecret.isNullOrBlank()) {
                "TELEGRAM_WEBHOOK_SECRET is required when TELEGRAM_WEBHOOK_URL is set"
            }
            require(aiInternalToken.isNotBlank()) {
                "AI_INTERNAL_TOKEN is required when TELEGRAM_WEBHOOK_URL is set"
            }
        }
        if (usesWebhook && !jwtSecret.isNullOrBlank()) {
            require(!databaseUrl.isNullOrBlank()) {
                "DATABASE_URL is required when JWT_SECRET is set"
            }
        }
    }

    companion object {
        const val DEFAULT_TLS_CERT_PATH = "/opt/speaking-coach/tls.crt"
        const val DEFAULT_TLS_KEY_PATH = "/opt/speaking-coach/tls.key"

        fun fromEnv(): AppConfig = AppConfig(
            telegramBotToken = env("TELEGRAM_BOT_TOKEN")?.takeIf { it.isNotBlank() },
            telegramWebhookUrl = env("TELEGRAM_WEBHOOK_URL")?.takeIf { it.isNotBlank() },
            telegramWebhookSecret = env("TELEGRAM_WEBHOOK_SECRET")?.takeIf { it.isNotBlank() },
            aiServiceBaseUrl = env("AI_SERVICE_BASE_URL") ?: "http://127.0.0.1:8090",
            aiInternalToken = env("AI_INTERNAL_TOKEN")?.takeIf { it.isNotBlank() } ?: "",
            serverPort = env("SERVER_PORT")?.toIntOrNull() ?: 8080,
            tlsCertPath = env("TLS_CERT_PATH")?.takeIf { it.isNotBlank() } ?: DEFAULT_TLS_CERT_PATH,
            tlsKeyPath = env("TLS_KEY_PATH")?.takeIf { it.isNotBlank() } ?: DEFAULT_TLS_KEY_PATH,
            jwtSecret = env("JWT_SECRET")?.takeIf { it.isNotBlank() },
            databaseUrl = env("DATABASE_URL")?.takeIf { it.isNotBlank() },
        )

        private fun env(name: String): String? = System.getenv(name)?.trim()
    }
}
