import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.routerhub.extension.tenda"
    compileSdk = 36

    defaultConfig {
        // Must equal `namespace`: the manifest's relative entry class (".TendaAcExtension") is
        // resolved by the host against the package name.
        applicationId = "dev.routerhub.extension.tenda"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // The host finds the entry point by its name in the manifest meta-data, and R8 doesn't
            // treat meta-data as a keep root. Extensions are small, so we simply don't minify.
            isMinifyEnabled = false
            // signingConfig = signingConfigs.getByName("official")  // the key the host trusts
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
    // Supplied by the host at runtime (ExtensionClassLoader.SHARED_PACKAGES). compileOnly means
    // "compile against it, don't package it". Bundling these would only add dead weight, because
    // the host's copies always win.
    compileOnly(libs.routerhub.api)
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.okhttp)

    // Unit tests run on the plain JVM, where no host supplies the platform.
    testImplementation(libs.routerhub.api)
    testImplementation(libs.kotlin.stdlib)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.okhttp)
    testImplementation(libs.junit)
}
