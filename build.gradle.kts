// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // AGP 9 ships Kotlin support built in and pins Kotlin Gradle plugin
        // 2.2.10 at runtime. Pin the KGP to the same version as the Compose
        // compiler plugin below (the Compose compiler must match the Kotlin
        // compiler version). See https://kotl.in/gradle/agp-built-in-kotlin
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
