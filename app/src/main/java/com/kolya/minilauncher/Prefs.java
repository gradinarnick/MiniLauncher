package com.kolya.minilauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Все настройки «Фокуса» (один файл SharedPreferences). */
final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("focus", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ ключ Google Authenticator

    String secret() { return sp.getString("secret", null); }
    void setSecret(String s) { sp.edit().putString("secret", s).commit(); }
    long lastStep() { return sp.getLong("last_step", 0); }
    void setLastStep(long s) { sp.edit().putLong("last_step", s).commit(); }

    // ------------------------------------------------------------ переключатели

    boolean videoBlock() { return sp.getBoolean("video_block", true); }
    void setVideoBlock(boolean v) { sp.edit().putBoolean("video_block", v).commit(); }

    /** Видео, встроенное в страницы Notion (детские песни). */
    boolean notionVideo() { return sp.getBoolean("notion_video", true); }
    void setNotionVideo(boolean v) { sp.edit().putBoolean("notion_video", v).commit(); }

    /** Закрывать экраны настроек, где «Фокус» можно отключить или удалить. */
    boolean protect() { return sp.getBoolean("protect", true); }
    void setProtect(boolean v) { sp.edit().putBoolean("protect", v).commit(); }

    boolean grayscale() { return sp.getBoolean("grayscale", true); }
    void setGrayscale(boolean v) { sp.edit().putBoolean("grayscale", v).commit(); }

    // ------------------------------------------------------------ ночной режим

    boolean nightMode() { return sp.getBoolean("night", true); }
    void setNightMode(boolean v) { sp.edit().putBoolean("night", v).commit(); }
    /** Минуты от полуночи. */
    int nightStart() { return sp.getInt("night_start", 20 * 60); }
    int nightEnd() { return sp.getInt("night_end", 9 * 60); }
    void setNightHours(int start, int end) {
        sp.edit().putInt("night_start", start).putInt("night_end", end).commit();
    }
    Set<String> nightAllowed() { return new HashSet<>(sp.getStringSet("night_allowed", new HashSet<>())); }
    void setNightAllowed(Set<String> s) { sp.edit().putStringSet("night_allowed", new HashSet<>(s)).commit(); }

    /** Приложения, которые ты сам закрыл на ночь (кроме закрытых по умолчанию). */
    Set<String> nightExtra() { return new HashSet<>(sp.getStringSet("night_extra", new HashSet<>())); }
    void setNightExtra(Set<String> s) { sp.edit().putStringSet("night_extra", new HashSet<>(s)).commit(); }

    // ------------------------------------------------------------ правила приложений

    String rule(String pkg) { return sp.getString("rule:" + pkg, "allow"); }

    void setRule(String pkg, String rule) {
        if (rule == null || "allow".equals(rule)) sp.edit().remove("rule:" + pkg).commit();
        else sp.edit().putString("rule:" + pkg, rule).commit();
    }

    Map<String, String> rules() {
        Map<String, String> m = new HashMap<>();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (e.getKey().startsWith("rule:") && e.getValue() instanceof String) {
                m.put(e.getKey().substring(5), (String) e.getValue());
            }
        }
        return m;
    }

    // ------------------------------------------------------------ временный доступ

    /** scope: "all", "video" или "app:<пакет>". */
    long until(String scope) { return sp.getLong("until:" + scope, 0); }
    void setUntil(String scope, long t) { sp.edit().putLong("until:" + scope, t).commit(); }

    Map<String, Long> activeUnlocks() {
        long now = System.currentTimeMillis();
        Map<String, Long> m = new HashMap<>();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (e.getKey().startsWith("until:") && e.getValue() instanceof Long && (Long) e.getValue() > now) {
                m.put(e.getKey().substring(6), (Long) e.getValue());
            }
        }
        return m;
    }

    // ------------------------------------------------------------ время в приложениях за сегодня

    private static int today() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
    }

    long usedToday(String pkg) {
        String v = sp.getString("use:" + pkg, null);
        if (v == null) return 0;
        try {
            int i = v.indexOf(':');
            if (Integer.parseInt(v.substring(0, i)) != today()) return 0;
            return Long.parseLong(v.substring(i + 1));
        } catch (Exception e) {
            return 0;
        }
    }

    void addUsage(String pkg, long ms) {
        sp.edit().putString("use:" + pkg, today() + ":" + (usedToday(pkg) + ms)).apply();
    }

    // ------------------------------------------------------------ банк

    /** Приложение банка, которому мешает служба «Специальных возможностей». */
    String bankApp() { return sp.getString("bank_app", null); }
    void setBankApp(String pkg) { sp.edit().putString("bank_app", pkg).commit(); }

    // ------------------------------------------------------------ первый запуск

    boolean presetDone() { return sp.getBoolean("preset_done", false); }
    void setPresetDone() { sp.edit().putBoolean("preset_done", true).commit(); }
}
