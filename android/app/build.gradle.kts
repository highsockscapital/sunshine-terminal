plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "sunshine.terminal"
    compileSdk = 36

    defaultConfig {
        applicationId = "sunshine.terminal"
        minSdk = 34
        targetSdk = 36
        // Pre-release: no versioning scheme yet.
        versionCode = 1
        versionName = "1.0.0"
    }

    // Kotlin JVM toolchain: ensure Kotlin compilation targets JVM 17
    // to match the consistent version with javac compilation.
    kotlin {
        jvmToolchain(17)
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":design"))
    val bom = platform(libs.compose.bom)
    implementation(bom)
    androidTestImplementation(bom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.kotlinx.coroutines)
    testImplementation("junit:junit:4.13.2")
}