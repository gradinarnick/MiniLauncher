package com.kolya.minilauncher;

import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Загружает список запускаемых приложений через LauncherApps (включая рабочий профиль). */
final class AppRepository {

    private AppRepository() {}

    static List<AppEntry> load(Context context, Prefs prefs) {
        LauncherApps launcherApps = context.getSystemService(LauncherApps.class);
        UserManager userManager = context.getSystemService(UserManager.class);
        UserHandle me = Process.myUserHandle();
        List<AppEntry> result = new ArrayList<>();
        String self = context.getPackageName();

        for (UserHandle user : launcherApps.getProfiles()) {
            // Пропускаем профили, которые сейчас выключены/заблокированы
            // (например, рабочий профиль на паузе или закрытый Private Space).
            if (userManager.isQuietModeEnabled(user)) continue;
            long serial = userManager.getSerialNumberForUser(user);
            boolean work = !user.equals(me);
            List<LauncherActivityInfo> infos;
            try {
                infos = launcherApps.getActivityList(null, user);
            } catch (SecurityException e) {
                continue;
            }
            for (LauncherActivityInfo info : infos) {
                if (self.equals(info.getComponentName().getPackageName())) continue;
                CharSequence l = info.getLabel();
                String label = l == null ? info.getComponentName().getPackageName() : l.toString().trim();
                String key = info.getComponentName().flattenToShortString() + "#" + serial;
                result.add(new AppEntry(key, label, info.getComponentName(), user, work));
            }
        }
        applyNames(result, prefs);
        return result;
    }

    /** Применяет переименования и сортирует по алфавиту (с учётом русского языка). */
    static void applyNames(List<AppEntry> apps, Prefs prefs) {
        for (AppEntry a : apps) {
            String custom = prefs.rename(a.key);
            String base = custom != null ? custom : a.originalLabel;
            a.label = a.workProfile ? base + " ·раб" : base;
            a.searchKey = base.toLowerCase(Locale.getDefault());
        }
        Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.PRIMARY);
        apps.sort((x, y) -> collator.compare(x.label, y.label));
    }
}
