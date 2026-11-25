package com.kolya.minilauncher;

import android.content.pm.LauncherActivityInfo;

/** Приложение из меню телефона. */
final class AppEntry {
    final String pkg;
    final String label;
    final LauncherActivityInfo info;

    AppEntry(String pkg, String label, LauncherActivityInfo info) {
        this.pkg = pkg;
        this.label = label;
        this.info = info;
    }
}
