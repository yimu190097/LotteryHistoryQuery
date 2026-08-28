plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lottery.admin"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lottery.admin"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // 存在正式 release.keystore 才启用正式签名；否则（如 CI）回退到 AGP 内置 debug 签名，保证可构建
        val keystoreFile = rootProject.file("app/release.keystore")
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                storePassword = "Lottery2026"
                keyAlias = "lottery"
                keyPassword = "Lottery2026"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (rootProject.file("app/release.keystore").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.google.code.gson:gson:2.10.1")
}