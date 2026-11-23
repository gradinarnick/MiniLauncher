# Mini Launcher — правила для Claude Code

Минималистичный текстовый лаунчер для Android (target/compile SDK 37 = Android 17, minSdk 30).

## Стек
- Java 17, чистый Android SDK, **без AndroidX и сторонних библиотек**.
- UI собирается кодом (без XML-разметки), настройки в SharedPreferences (`Prefs`).
- AGP 9.3.2, Gradle 9.7.1 (wrapper).

## Команды
- Сборка: `./gradlew assembleDebug`  → `app/build/outputs/apk/debug/app-debug.apk`
- Релиз (R8 + shrink): `./gradlew assembleRelease`
- Установка: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Логи: `adb logcat --pid=$(adb shell pidof com.kolya.minilauncher)`

## Правила
- НЕ добавлять разрешение INTERNET и любые сетевые библиотеки.
- НЕ использовать AccessibilityService (ломает банки, проблемы с модерацией).
- Список приложений — только через `LauncherApps` (учитывает рабочий профиль).
- Учитывать edge-to-edge (Android 15+) и отступы клавиатуры через WindowInsets.
- «Назад» — через OnBackInvokedCallback (API 33+), onBackPressed только для 30–32.
- Не держать в памяти битмапы/иконки: лаунчер текстовый (лимиты памяти Android 17).
- Перед коммитом: `./gradlew assembleDebug` должен проходить, проверка на реальном телефоне.
