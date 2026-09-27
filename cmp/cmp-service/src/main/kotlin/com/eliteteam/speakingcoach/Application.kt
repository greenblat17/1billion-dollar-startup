package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.app.AppApi
import com.eliteteam.speakingcoach.app.createAppApi
import com.eliteteam.speakingcoach.app.installAppPlugins
import com.eliteteam.speakingcoach.app.installAppRoutes
import com.eliteteam.speakingcoach.tls.TLS_KEY_ALIAS
import com.eliteteam.speakingcoach.tls.loadPemKeyStore
import com.eliteteam.speakingcoach.tls.pemTlsReady
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import kotlinx.coroutines.awaitCancellation
import org.slf4j.LoggerFactory
import java.io.File

private val tlsStorePassword = "ktor".toCharArray()

suspend fun main() {
    val config = AppConfig.fromEnv()
    if (pemTlsReady(config.tlsCertPath, config.tlsKeyPath)) {
        if (!config.jwtSecret.isNullOrBlank()) {
            require(!config.databaseUrl.isNullOrBlank()) {
                "DATABASE_URL is required when JWT_SECRET is set"
            }
        }
        startTlsServer(config)
    } else {
        embeddedServer(Netty, port = config.serverPort, host = "0.0.0.0") {
            module(config)
        }.start(wait = true)
    }
}

private suspend fun startTlsServer(config: AppConfig) {
    val log = LoggerFactory.getLogger("CmpService")
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
        module(config)
    }
    server.start(wait = false)
    log.info("cmp-service listening on {}", config.serverPort)
    try {
        awaitCancellation()
    } finally {
        server.stop()
    }
}

internal fun Application.module(
    config: AppConfig = AppConfig.fromEnv(),
    appApi: AppApi? = createAppApi(config),
) {
    if (appApi != null) {
        installAppPlugins(appApi)
    }
    installServiceHttp {
        if (appApi != null) {
            installAppRoutes(this@module, appApi)
        }
    }
}
