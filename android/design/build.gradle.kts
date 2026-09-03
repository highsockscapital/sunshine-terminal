plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "sunshine.design"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
    }

    // Kotlin JVM toolchain: ensure Kotlin compilation targets JVM 17
    // to match the consistent version with javac compilation.
    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val bom = platform(libs.compose.bom)
    implementation(bom)
    androidTestImplementation(bom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
}