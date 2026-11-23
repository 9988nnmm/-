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
        versionCode = 33
        versionName = "2.9.2-arc-only"
    }
    // 固定签名：keystore 就在模块目录里。签名稳定之后才能覆盖安装，
    // 否则每次云端构建的 debug keystore 都不同，装新包必须先卸载（会丢掉设备 UUID）。
    signingConfigs {
        create("mags") {
            storeFile = file("mags.jks")
            storePassword = "123456"
            keyAlias = "mags"
            keyPassword = "123456"
        }
    }
    buildTypes {
        release { isMinifyEnabled = false }
        debug { signingConfig = signingConfigs.getByName("mags") }
    }
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
    implementation("androidx.graphics:graphics-shapes:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
