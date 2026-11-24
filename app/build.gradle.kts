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
        versionCode = 67
        versionName = "5.3-chick-wallpaper"
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
    // 两个版本功能完全一致，只有「投光鸭」的落盘目录不同：
    //   self   → 用服务端 cloud.json 里配的路径（默认 /光鸭/来自：云添加）
    //   friend → 投到 /云盘
    flavorDimensions += "edition"
    productFlavors {
        create("self") {
            dimension = "edition"
            buildConfigField("String", "ADD_PATH", "\"\"")
        }
        create("friend") {
            dimension = "edition"
            applicationIdSuffix = ".friend"      // 独立包名，能和自己的版本共存
            versionNameSuffix = "-friend"
            buildConfigField("String", "ADD_PATH", "\"/光鸭/云盘\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
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
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
