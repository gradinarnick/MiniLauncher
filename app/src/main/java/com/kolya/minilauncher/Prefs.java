package com.kolya.minilauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Все настройки лаунчера хранятся локально в SharedPreferences. */
final class Prefs {
    /** Мест на рабочем столе: 4 колонки × 3 ряда. */
    static final int MAX_HOME = 12;

    private static final String FILE = "launcher";
    private static final String K_FAVORITES = "favorites";      // старый формат (до v2)
    private static final String K_HOME = "home_v2";             // "a:<ключ>" или "f:<id папки>"
    private static final String K_FOLDER_NAME = "folder_name:";
    private static final String K_FOLDER_APPS = "folder_apps:";
    private static final String K_HIDDEN = "hidden";
    private static final String K_RENAME = "rename:";
    private static final String K_BLACK = "black_theme";
    private static final String K_GRAY = "gray_icons";
    private static final String K_AUTO_LAUNCH = "auto_launch";
    private static final String K_KEYBOARD = "show_keyboard";
    private static final String K_SHOW_DATE = "show_date";
    private static final String K_WIDGETS = "widgets";
    private static final String K_DISTRACTING = "distracting";  // имена пакетов
    private static final String K_VIDEO_APPS = "video_apps";    // имена пакетов
    private static final String K_VIDEO_BLOCK = "video_block";
    private static final String K_VIDEO_UNBLOCK_AT = "video_unblock_at";
    private static final String K_NIGHT = "night_mode";
    private static final String K_NIGHT_ALLOWED = "night_allowed";
    private static final String K_SEEN = "seen_packages";
    private static final String K_BIBLE_PRESET = "bible_preset_done";

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---- Рабочий стол ----
    List<String> home() {
        if (!sp.contains(K_HOME)) {
            // Перенос избранного из прежней версии лаунчера.
            List<String> migrated = new ArrayList<>();
            for (String k : splitList(sp.getString(K_FAVORITES, ""))) {
                if (migrated.size() < MAX_HOME) migrated.add("a:" + k);
            }
            setHome(migrated);
            return migrated;
        }
        return splitList(sp.getString(K_HOME, ""));
    }

    void setHome(List<String> items) {
        sp.edit().putString(K_HOME, String.join("\n", items)).apply();
    }

    void moveHome(String item, int delta) {
        List<String> list = home();
        int i = list.indexOf(item);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= list.size()) return;
        list.remove(i);
        list.add(j, item);
        setHome(list);
    }

    // ---- Папки ----
    String newFolderId() {
        return "f" + System.currentTimeMillis();
    }

    String folderName(String id) {
        return sp.getString(K_FOLDER_NAME + id, "Папка");
    }

    void setFolderName(String id, String name) {
        String n = name == null || name.trim().isEmpty() ? "Папка" : name.trim();
        sp.edit().putString(K_FOLDER_NAME + id, n).apply();
    }

    List<String> folderApps(String id) {
        return splitList(sp.getString(K_FOLDER_APPS + id, ""));
    }

    void setFolderApps(String id, List<String> keys) {
        sp.edit().putString(K_FOLDER_APPS + id, String.join("\n", keys)).apply();
    }

    void deleteFolderData(String id) {
        sp.edit().remove(K_FOLDER_NAME + id).remove(K_FOLDER_APPS + id).apply();
    }

    List<String> folderIds() {
        List<String> ids = new ArrayList<>();
        for (String item : home()) if (item.startsWith("f:")) ids.add(item.substring(2));
        return ids;
    }

    /** @return id папки, в которой лежит приложение, или null */
    String folderOf(String key) {
        for (String id : folderIds()) if (folderApps(id).contains(key)) return id;
        return null;
    }

    // ---- Виджеты ----
    List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        for (String s : splitList(sp.getString(K_WIDGETS, ""))) {
            try { out.add(Integer.parseInt(s)); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    void setWidgets(List<Integer> ids) {
        List<String> s = new ArrayList<>();
        for (Integer i : ids) s.add(String.valueOf(i));
        sp.edit().putString(K_WIDGETS, String.join("\n", s)).apply();
    }

    // ---- Скрытые приложения ----
    Set<String> hidden() { return getSet(K_HIDDEN); }

    void setHidden(String key, boolean hide) {
        Set<String> s = hidden();
        if (hide) s.add(key); else s.remove(key);
        sp.edit().putStringSet(K_HIDDEN, s).apply();
    }

    // ---- Переименование ----
    String rename(String key) {
        return sp.getString(K_RENAME + key, null);
    }

    void setRename(String key, String name) {
        SharedPreferences.Editor e = sp.edit();
        if (name == null || name.trim().isEmpty()) e.remove(K_RENAME + key);
        else e.putString(K_RENAME + key, name.trim());
        e.apply();
    }

    // ---- Защита от залипания ----
    Set<String> distracting() { return getSet(K_DISTRACTING); }
    void setDistracting(Set<String> s) { sp.edit().putStringSet(K_DISTRACTING, new HashSet<>(s)).apply(); }

    Set<String> videoApps() { return getSet(K_VIDEO_APPS); }
    void setVideoApps(Set<String> s) { sp.edit().putStringSet(K_VIDEO_APPS, new HashSet<>(s)).apply(); }

    boolean videoBlock() { return sp.getBoolean(K_VIDEO_BLOCK, false); }
    void setVideoBlock(boolean v) { sp.edit().putBoolean(K_VIDEO_BLOCK, v).apply(); }

    /** Время (мс), когда блок видео выключится сам; 0 — выключение не запрошено. */
    long videoUnblockAt() { return sp.getLong(K_VIDEO_UNBLOCK_AT, 0L); }
    void setVideoUnblockAt(long t) { sp.edit().putLong(K_VIDEO_UNBLOCK_AT, t).apply(); }

    boolean nightMode() { return sp.getBoolean(K_NIGHT, true); }
    void setNightMode(boolean v) { sp.edit().putBoolean(K_NIGHT, v).apply(); }

    Set<String> nightAllowed() { return getSet(K_NIGHT_ALLOWED); }
    void setNightAllowed(Set<String> s) { sp.edit().putStringSet(K_NIGHT_ALLOWED, new HashSet<>(s)).apply(); }

    Set<String> seenPackages() { return getSet(K_SEEN); }
    void setSeenPackages(Set<String> s) { sp.edit().putStringSet(K_SEEN, new HashSet<>(s)).apply(); }

    boolean biblePresetDone() { return sp.getBoolean(K_BIBLE_PRESET, false); }
    void setBiblePresetDone() { sp.edit().putBoolean(K_BIBLE_PRESET, true).apply(); }

    // ---- Флаги ----
    boolean blackTheme() { return sp.getBoolean(K_BLACK, false); }
    void setBlackTheme(boolean v) { sp.edit().putBoolean(K_BLACK, v).apply(); }

    boolean grayIcons() { return sp.getBoolean(K_GRAY, false); }
    void setGrayIcons(boolean v) { sp.edit().putBoolean(K_GRAY, v).apply(); }

    boolean autoLaunch() { return sp.getBoolean(K_AUTO_LAUNCH, false); }
    void setAutoLaunch(boolean v) { sp.edit().putBoolean(K_AUTO_LAUNCH, v).apply(); }

    boolean showKeyboard() { return sp.getBoolean(K_KEYBOARD, true); }
    void setShowKeyboard(boolean v) { sp.edit().putBoolean(K_KEYBOARD, v).apply(); }

    boolean showDate() { return sp.getBoolean(K_SHOW_DATE, true); }
    void setShowDate(boolean v) { sp.edit().putBoolean(K_SHOW_DATE, v).apply(); }

    // ---- helpers ----
    private Set<String> getSet(String key) {
        Set<String> s = sp.getStringSet(key, null);
        return s == null ? new HashSet<>() : new HashSet<>(s);
    }

    private static List<String> splitList(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return list;
        for (String s : Arrays.asList(raw.split("\n"))) if (!s.isEmpty()) list.add(s);
        return list;
    }
}
