plugins {
    id("com.android.application")
}

android {
    namespace = "com.kolya.minilauncher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kolya.minilauncher"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Для личного использования релиз подписывается debug-ключом.
            // Для публикации замените на свой signingConfig.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }
}

// Никаких внешних библиотек: только Android SDK.
