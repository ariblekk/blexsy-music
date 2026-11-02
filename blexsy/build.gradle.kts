buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 uses built-in Kotlin. Pin the Kotlin Gradle plugin to the version
        // whose compiler plugins (Compose, serialization) we apply below.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
