package com.kolya.minilauncher;

import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Process;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Список приложений телефона (по алфавиту) и их иконки. */
final class AppRepository {
    private static final Map<String, Drawable> ICONS = new HashMap<>();

    private AppRepository() {}

    static List<AppEntry> load(Context c) {
        Map<String, AppEntry> map = new LinkedHashMap<>();
        try {
            LauncherApps la = c.getSystemService(LauncherApps.class);
            for (LauncherActivityInfo i : la.getActivityList(null, Process.myUserHandle())) {
                String pkg = i.getComponentName().getPackageName();
                if (pkg.equals(c.getPackageName()) || map.containsKey(pkg)) continue;
                CharSequence l = i.getLabel();
                map.put(pkg, new AppEntry(pkg, l == null ? pkg : l.toString().trim(), i));
            }
        } catch (Exception ignored) {}
        List<AppEntry> list = new ArrayList<>(map.values());
        Collator col = Collator.getInstance(Locale.forLanguageTag("ru"));
        col.setStrength(Collator.PRIMARY);
        list.sort((a, b) -> col.compare(a.label, b.label));
        return list;
    }

    static Drawable icon(Context c, AppEntry a) {
        Drawable d = ICONS.get(a.pkg);
        if (d == null) {
            try {
                d = a.info.getIcon(0);
            } catch (Exception e) {
                d = c.getPackageManager().getDefaultActivityIcon();
            }
            ICONS.put(a.pkg, d);
        }
        return d;
    }

    static Drawable icon(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationIcon(pkg);
        } catch (Exception e) {
            return c.getPackageManager().getDefaultActivityIcon();
        }
    }

    static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }
}
