# Mini Launcher — правила для Claude Code

Лаунчер с рабочим столом как на iPhone и защитой от залипания для Android (target/compile SDK 37 = Android 17, minSdk 30).

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
- Иконки грузятся только для рабочего стола и папок (кэш по ключу, чистится при перезагрузке списка). Список всех приложений — текстовый.
- Ограничения (пауза 20 сек, ночной режим 20:00–9:00, блок видео) — только в `Guard`. Любой запуск — через `MainActivity.launch()`. Ослаблять ограничения ночью/при активном блоке нельзя.
- Перед коммитом: `./gradlew assembleDebug` должен проходить, проверка на реальном телефоне.
