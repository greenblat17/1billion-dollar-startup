plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
}

group = "com.eliteteam.speakingcoach"
version = "1.0.0"
application {
    mainClass = "com.eliteteam.speakingcoach.ApplicationKt"
}

ktor {
    fatJar {
        archiveFileName.set("server-all.jar")
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverAuthJwt)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.bcrypt)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.clientCio)
    implementation(libs.ktor.clientOkHttp)
    implementation(libs.ktor.clientContentNegotiation)
    implementation(libs.ktor.serializationJson)
    implementation(libs.ktor.serverRoutingOpenapi)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.slf4j)
    implementation(libs.tgbotapi)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.clientMock)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// PostgreSQL suites run only in foundationIntegrationTest. Missing DB configuration there is a failure, not a skip.
tasks.test {
    exclude("**/FoundationIntegrationTest*")
    exclude("**/TrialIntegrationTest*")
}
val foundationIntegrationTest by tasks.registering(Test::class) {
    description = "Run foundation and trial integration contracts against real PostgreSQL"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/FoundationIntegrationTest*")
    include("**/TrialIntegrationTest*")
    outputs.upToDateWhen { false }
}

val foundationHarness by tasks.registering(JavaExec::class) {
    description = "Start loopback-only SP-00 test backend (test classpath, never shipped)"
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.eliteteam.speakingcoach.foundation.FoundationHarnessKt")
}

val foundationImport by tasks.registering(JavaExec::class) {
    description = "Validate and import a frozen text-tutor manifest; never sends messages"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.eliteteam.speakingcoach.foundation.FoundationImportCliKt")
}

val foundationMaintenance by tasks.registering(JavaExec::class) {
    description = "Offline foundation diagnostics and deletion-ledger backup/restore"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.eliteteam.speakingcoach.foundation.FoundationMaintenanceCliKt")
}
