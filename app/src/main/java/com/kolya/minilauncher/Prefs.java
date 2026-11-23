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

    // ---- Рабочий стол: свободная сетка COLS × ROWS, как в Pixel Launcher ----
    static final int COLS = 4;
    static final int ROWS = 6;
    static final int CELLS = COLS * ROWS;
    private static final String K_GRID = "home_grid_v3"; // строки "клетка|элемент"

    /** Клетка → элемент ("a:<ключ>" или "f:<id папки>"). Пустые клетки отсутствуют. */
    java.util.TreeMap<Integer, String> grid() {
        java.util.TreeMap<Integer, String> g = new java.util.TreeMap<>();
        if (!sp.contains(K_GRID)) {
            // Перенос из прежних версий: элементы подряд, прижаты к низу экрана.
            List<String> old = new ArrayList<>();
            if (sp.contains(K_HOME)) old.addAll(splitList(sp.getString(K_HOME, "")));
            else for (String k : splitList(sp.getString(K_FAVORITES, ""))) old.add("a:" + k);
            int n = Math.min(old.size(), MAX_HOME);
            int rows = (n + COLS - 1) / COLS;
            int first = (ROWS - rows) * COLS;
            for (int i = 0; i < n; i++) g.put(first + i, old.get(i));
            setGrid(g);
            return g;
        }
        for (String line : splitList(sp.getString(K_GRID, ""))) {
            int bar = line.indexOf('|');
            if (bar <= 0) continue;
            try {
                int cell = Integer.parseInt(line.substring(0, bar));
                if (cell >= 0 && cell < CELLS) g.put(cell, line.substring(bar + 1));
            } catch (NumberFormatException ignored) {}
        }
        return g;
    }

    void setGrid(java.util.Map<Integer, String> g) {
        List<String> lines = new ArrayList<>();
        for (java.util.Map.Entry<Integer, String> e : new java.util.TreeMap<>(g).entrySet()) {
            lines.add(e.getKey() + "|" + e.getValue());
        }
        sp.edit().putString(K_GRID, String.join("\n", lines)).apply();
    }

    /** Элементы рабочего стола по порядку клеток (только для чтения). */
    List<String> home() {
        return new ArrayList<>(grid().values());
    }

    int cellOf(String item) {
        for (java.util.Map.Entry<Integer, String> e : grid().entrySet()) {
            if (e.getValue().equals(item)) return e.getKey();
        }
        return -1;
    }

    void removeItem(String item) {
        java.util.TreeMap<Integer, String> g = grid();
        g.values().removeIf(item::equals);
        setGrid(g);
    }

    void replaceItem(String oldItem, String newItem) {
        java.util.TreeMap<Integer, String> g = grid();
        for (java.util.Map.Entry<Integer, String> e : g.entrySet()) {
            if (e.getValue().equals(oldItem)) e.setValue(newItem);
        }
        setGrid(g);
    }

    /**
     * Ставит элемент в клетку preferred, если она свободна, иначе — в первую свободную,
     * начиная с нижнего ряда. @return false, если места нет или лимит MAX_HOME.
     */
    boolean placeItem(String item, int preferred) {
        java.util.TreeMap<Integer, String> g = grid();
        if (g.containsValue(item)) return true;
        if (g.size() >= MAX_HOME) return false;
        int cell = -1;
        if (preferred >= 0 && preferred < CELLS && !g.containsKey(preferred)) cell = preferred;
        for (int r = ROWS - 1; r >= 0 && cell < 0; r--) {
            for (int c = 0; c < COLS; c++) {
                if (!g.containsKey(r * COLS + c)) { cell = r * COLS + c; break; }
            }
        }
        if (cell < 0) return false;
        g.put(cell, item);
        setGrid(g);
        return true;
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
