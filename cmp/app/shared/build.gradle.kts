import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.stabilityAnalyzer)
    alias(libs.plugins.koin.compiler)
}

val generateApiConfig = tasks.register("generateApiConfig") {
    val outputDir = layout.buildDirectory.dir("generated/apiConfig")
    val clientLocalFile = rootProject.file("client.local.properties")
    val urlFromGradle = providers.gradleProperty("speakingCoach.apiBaseUrl").orElse("")
    val urlFromEnv = providers.environmentVariable("SPEAKING_COACH_API_BASE_URL").orElse("")
    inputs.property("urlFromGradle", urlFromGradle)
    inputs.property("urlFromEnv", urlFromEnv)
    inputs.files(clientLocalFile).optional()
    outputs.dir(outputDir)
    doLast {
        val fromClientLocal = clientLocalFile.let { file ->
            if (!file.isFile) {
                ""
            } else {
                Properties().apply { file.reader().use { load(it) } }
                    .getProperty("speakingCoach.apiBaseUrl")
                    ?.trim()
                    .orEmpty()
            }
        }
        val url = sequenceOf(
            urlFromGradle.get().trim(),
            urlFromEnv.get().trim(),
            fromClientLocal,
        ).firstOrNull { it.isNotEmpty() }.orEmpty()
        val dir = outputDir.get().asFile
        dir.mkdirs()
        fun String.toKotlinStringLiteral(): String = buildString {
            append('"')
            for (ch in this@toKotlinStringLiteral) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '$' -> append("\\\$")
                    else -> append(ch)
                }
            }
            append('"')
        }
        dir.resolve("ApiConfig.kt").writeText(
            """
            |package com.eliteteam.speakingcoach.data
            |
            |internal object ApiConfig {
            |    const val BAKED_API_BASE_URL = ${url.toKotlinStringLiteral()}
            |}
            |
            """.trimMargin(),
        )
    }
}

// shepeliev webrtc-kmp cinterop links `-framework WebRTC` but does not ship the binary.
// Gradle iOS tests / framework link need the xcframework on the search path (SPM only covers iosApp).
val webRtcIosSdkVersion = "125.6422.07"
val webRtcIosSdkSha256 = "827cc2f508c341367c9b0e4f6def46e5f6834251082047d5d1da5bc8fd379263"
val webRtcSdkRoot = File(System.getProperty("user.home"), ".gradle/caches/webrtc-sdk/$webRtcIosSdkVersion")
val fetchWebRtcXcframework = tasks.register("fetchWebRtcXcframework") {
    val version = webRtcIosSdkVersion
    val expectedSha = webRtcIosSdkSha256
    val root = webRtcSdkRoot
    outputs.dir(root.resolve("WebRTC.xcframework"))
    doLast {
        val frameworkDir = root.resolve("WebRTC.xcframework")
        if (!frameworkDir.isDirectory) {
            root.mkdirs()
            val zip = root.resolve("WebRTC.xcframework.zip")
            if (!zip.isFile) {
                URI("https://github.com/webrtc-sdk/Specs/releases/download/$version/WebRTC.xcframework.zip")
                    .toURL()
                    .openStream()
                    .use { input -> zip.outputStream().use { input.copyTo(it) } }
            }
            val digest = zip.inputStream().use { stream ->
                val md = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = stream.read(buf)
                    if (read <= 0) break
                    md.update(buf, 0, read)
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
            check(digest == expectedSha) {
                "WebRTC.xcframework.zip sha256=$digest expected=$expectedSha"
            }
            ZipFile(zip).use { zipFile ->
                zipFile.entries().asSequence().forEach { entry ->
                    val out = root.resolve(entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile.mkdirs()
                        zipFile.getInputStream(entry).use { input ->
                            out.outputStream().use { input.copyTo(it) }
                        }
                    }
                }
            }
        }
        check(frameworkDir.isDirectory) { "missing $frameworkDir" }
    }
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
        val slice = if (iosTarget.name.contains("Simulator", ignoreCase = true)) {
            "ios-arm64_x86_64-simulator"
        } else {
            "ios-arm64"
        }
        val webRtcFrameworkDir = File(webRtcSdkRoot, "WebRTC.xcframework/$slice")
        iosTarget.binaries.all {
            linkerOpts("-F${webRtcFrameworkDir.absolutePath}", "-rpath", webRtcFrameworkDir.absolutePath)
            linkTaskProvider.configure { dependsOn(fetchWebRtcXcframework) }
        }
    }

    jvm()

    android {
        namespace = "com.eliteteam.speakingcoach.app.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        androidMain {
            kotlin.srcDir("src/webrtcMain/kotlin")
            dependencies {
                implementation(libs.compose.uiToolingPreview)
                implementation(libs.compose.uiTooling)
                implementation(libs.ktor.kmp.client.okhttp)
                implementation(libs.webrtc.kmp)
            }
        }
        iosMain {
            kotlin.srcDir("src/webrtcMain/kotlin")
            dependencies {
                implementation(libs.ktor.kmp.client.darwin)
                implementation(libs.webrtc.kmp)
            }
        }
        jvmMain.dependencies {
            implementation(libs.ktor.kmp.client.cio)
            implementation(libs.webrtc.java)
            val osName = System.getProperty("os.name").lowercase()
            val osArch = System.getProperty("os.arch").lowercase()
            val webrtcNative = when {
                osName.contains("mac") && (osArch.contains("aarch64") || osArch.contains("arm64")) -> "macos-aarch64"
                osName.contains("mac") -> "macos-x86_64"
                osName.contains("linux") && (osArch.contains("aarch64") || osArch.contains("arm64")) -> "linux-aarch64"
                osName.contains("linux") -> "linux-x86_64"
                else -> "windows-x86_64"
            }
            implementation("dev.onvoid.webrtc:webrtc-java:${libs.versions.webrtc.java.get()}:$webrtcNative")
        }
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.androidx.lifecycle.viewmodelNavigation3)
            implementation(libs.androidx.navigation3.ui)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.koin.core)
            implementation(libs.koin.core.viewmodel)
            implementation(libs.koin.core.annotations)
            implementation(libs.koin.annotations)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.koin.compose.navigation3)
            implementation(libs.kermit)
            implementation(libs.kermit.koin)
            implementation(libs.ktor.kmp.client.core)
            implementation(libs.ktor.kmp.client.content.negotiation)
            implementation(libs.ktor.kmp.serialization.json)
            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.no.arg)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.koin.test)
            implementation(libs.kermit.test)
            implementation(libs.ktor.kmp.client.mock)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.multiplatform.settings.test)
        }
    }
}

kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(generateApiConfig)

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

// Compiler plugin stays for @TraceRecomposition. Do not let stabilityCheck ride
// on `check`: the 0.14.0 task graph does not understand AGP KMP library
// (`compileAndroidMain`) and fails Gradle 9 implicit-dependency validation.
// This module has no *.stability CI baseline.
afterEvaluate {
    tasks.named("check").configure {
        setDependsOn(
            dependsOn.filterNot { dep ->
                val name = when (dep) {
                    is Task -> dep.name
                    is TaskProvider<*> -> dep.name
                    else -> dep.toString()
                }
                name.contains("stabilityCheck")
            },
        )
    }
    tasks.named("stabilityCheck").configure { enabled = false }
    tasks.named("stabilityDump").configure { enabled = false }
}
