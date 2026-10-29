import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.routerhub.extension.demo"
    compileSdk = 36

    defaultConfig {
        // Must equal the namespace: the manifest's relative entry class is resolved against it.
        applicationId = "dev.routerhub.extension.demo"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // The host finds the entry point by its name in the manifest meta-data, and R8 does not
            // treat meta-data as a keep root, so extensions are not minified.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Supplied by the host at runtime. compileOnly = compile against it, do not package it.
    compileOnly(libs.routerhub.api)
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.kotlinx.coroutines.core)

    // Unit tests run on the plain JVM, where no host supplies the platform.
    testImplementation(libs.routerhub.api)
    testImplementation(libs.kotlin.stdlib)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
