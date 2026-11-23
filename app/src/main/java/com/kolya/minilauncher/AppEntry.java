package com.kolya.minilauncher;

import android.content.ComponentName;
import android.content.pm.LauncherActivityInfo;
import android.os.UserHandle;

/** Одно запускаемое приложение (activity) в конкретном профиле пользователя. */
final class AppEntry {
    final String key;          // уникальный ключ: component + серийный номер профиля
    final String originalLabel;
    final ComponentName component;
    final UserHandle user;
    final boolean workProfile;
    final LauncherActivityInfo info; // нужен для иконки (грузится по требованию)
    String label;              // отображаемое имя (с учётом переименования)
    String searchKey;          // имя в нижнем регистре для поиска

    AppEntry(String key, String originalLabel, ComponentName component,
             UserHandle user, boolean workProfile, LauncherActivityInfo info) {
        this.key = key;
        this.originalLabel = originalLabel;
        this.component = component;
        this.user = user;
        this.workProfile = workProfile;
        this.info = info;
        this.label = originalLabel;
        this.searchKey = originalLabel.toLowerCase();
    }

    String packageName() {
        return component.getPackageName();
    }
}
