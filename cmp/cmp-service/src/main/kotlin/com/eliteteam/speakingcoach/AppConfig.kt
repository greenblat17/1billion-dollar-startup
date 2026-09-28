package com.eliteteam.speakingcoach

data class AppConfig(
    val aiServiceBaseUrl: String,
    val aiInternalToken: String,
    val serverPort: Int,
    val tlsCertPath: String,
    val tlsKeyPath: String,
    val jwtSecret: String?,
    val databaseUrl: String?,
) {
    companion object {
        const val DEFAULT_TLS_CERT_PATH = "/opt/cmp-service/tls.crt"
        const val DEFAULT_TLS_KEY_PATH = "/opt/cmp-service/tls.key"

        fun fromEnv(): AppConfig = AppConfig(
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
