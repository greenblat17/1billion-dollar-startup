plugins {
    alias(libs.plugins.kotlinJvm)
}

group = "com.eliteteam.speakingcoach"
version = "1.0.0"

dependencies {
    api(project(":core"))
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverRoutingOpenapi)
    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.clientCio)
    implementation(libs.ktor.clientContentNegotiation)
    implementation(libs.ktor.serializationJson)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.testJunit)
}
