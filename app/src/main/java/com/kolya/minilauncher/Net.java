package com.kolya.minilauncher;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

/** Системные настройки, которые «Фокус» держит сам (нужно разрешение WRITE_SECURE_SETTINGS через adb). */
final class Net {
    private Net() {}

    /** «Частный DNS» с адресом сервера обходит фильтр — переключаем обратно в «Автоматически». */
    static void enforcePrivateDns(Context c) {
        if (!Guard.canControlSystem(c)) return;
        try {
            ContentResolver cr = c.getContentResolver();
            if ("hostname".equals(Settings.Global.getString(cr, "private_dns_mode"))) {
                Settings.Global.putString(cr, "private_dns_mode", "opportunistic");
            }
        } catch (Exception ignored) {}
    }

    static boolean privateDnsBypass(Context c) {
        try {
            return "hostname".equals(Settings.Global.getString(c.getContentResolver(), "private_dns_mode"));
        } catch (Exception e) {
            return false;
        }
    }

    /** Если защиту в «Специальных возможностях» выключили — включает её обратно. */
    static void ensureAccessibility(Context c) {
        if (!Guard.canControlSystem(c)) return;
        try {
            ContentResolver cr = c.getContentResolver();
            String full = new ComponentName(c, GuardService.class).flattenToString();
            String shortName = new ComponentName(c, GuardService.class).flattenToShortString();
            String cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null) cur = "";
            if (!cur.contains(full) && !cur.contains(shortName)) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        cur.isEmpty() ? full : cur + ":" + full);
            }
            if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
        } catch (Exception ignored) {}
    }
}
