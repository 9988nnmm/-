plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.fish.mags.glass"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fish.mags.glass"
        minSdk = 33
        targetSdk = 36
        versionCode = 4
        versionName = "0.4-search"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("io.github.kyant0:backdrop:2.0.0-alpha03")
    implementation("org.jetbrains.compose.ui:ui:1.10.1")
    implementation("org.jetbrains.compose.foundation:foundation:1.10.1")
    implementation("org.jetbrains.compose.ui:ui-graphics:1.10.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
