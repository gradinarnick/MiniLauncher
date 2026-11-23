plugins {
    id("com.android.application")
}

// Номер сборки GitHub Actions — он же номер версии: каждая сборка новее предыдущей,
// поэтому Obtainium на телефоне видит обновление и ставит его.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
// Постоянный ключ подписи (в CI приходит из секрета SIGNING_KEYSTORE_BASE64).
// Локально можно указать путь к signing/minilauncher.p12 в переменной SIGNING_KEYSTORE_FILE.
val releaseKeystore = System.getenv("SIGNING_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.kolya.minilauncher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kolya.minilauncher"
        minSdk = 30
        targetSdk = 37
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storeType = "pkcs12"
                // Пароль открытый: секрет — сам файл ключа.
                storePassword = "minilauncher"
                keyAlias = "minilauncher"
                keyPassword = "minilauncher"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Без ключа (локальная сборка) — debug-подпись; такой APK не обновит версию с телефона.
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
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
