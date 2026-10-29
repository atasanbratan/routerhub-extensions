import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.routerhub.extension.openwrt"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.routerhub.extension.openwrt"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false // see extensions/tplink-adsl/build.gradle.kts
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
    // Host-provided platform.
    compileOnly(libs.routerhub.api)
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.kotlinx.coroutines.core)

    // Private: the maintained JSch fork (modern KEX and ciphers), bundled and resolved child-first.
    implementation(libs.jsch)

    testImplementation(libs.routerhub.api)
    testImplementation(libs.kotlin.stdlib)
    testImplementation(libs.junit)
}
