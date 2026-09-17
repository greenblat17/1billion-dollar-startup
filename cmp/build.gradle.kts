import dev.detekt.gradle.extensions.DetektExtension

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.stabilityAnalyzer) apply false
    alias(libs.plugins.koin.compiler) apply false
}

val detektPluginId = libs.plugins.detekt.get().pluginId

subprojects {
    pluginManager.apply(detektPluginId)
    extensions.configure<DetektExtension> {
        buildUponDefaultConfig.set(true)
        parallel.set(true)
        config.from(rootProject.files("config/detekt.yml"))
        source.setFrom(files("src"))
    }
}
