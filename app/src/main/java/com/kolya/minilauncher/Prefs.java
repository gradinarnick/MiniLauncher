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
    static final int MAX_FAVORITES = 8;

    private static final String FILE = "launcher";
    private static final String K_FAVORITES = "favorites";
    private static final String K_HIDDEN = "hidden";
    private static final String K_RENAME = "rename:";
    private static final String K_BLACK = "black_theme";
    private static final String K_AUTO_LAUNCH = "auto_launch";
    private static final String K_KEYBOARD = "show_keyboard";
    private static final String K_SHOW_DATE = "show_date";

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---- Избранное (упорядоченный список) ----
    List<String> favorites() {
        String raw = sp.getString(K_FAVORITES, "");
        List<String> list = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return list;
        list.addAll(Arrays.asList(raw.split("\n")));
        return list;
    }

    void setFavorites(List<String> keys) {
        sp.edit().putString(K_FAVORITES, String.join("\n", keys)).apply();
    }

    boolean isFavorite(String key) {
        return favorites().contains(key);
    }

    /** @return false, если лимит избранного уже достигнут */
    boolean addFavorite(String key) {
        List<String> list = favorites();
        if (list.contains(key)) return true;
        if (list.size() >= MAX_FAVORITES) return false;
        list.add(key);
        setFavorites(list);
        return true;
    }

    void removeFavorite(String key) {
        List<String> list = favorites();
        list.remove(key);
        setFavorites(list);
    }

    void moveFavorite(String key, int delta) {
        List<String> list = favorites();
        int i = list.indexOf(key);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= list.size()) return;
        list.remove(i);
        list.add(j, key);
        setFavorites(list);
    }

    // ---- Скрытые приложения ----
    Set<String> hidden() {
        Set<String> s = sp.getStringSet(K_HIDDEN, null);
        return s == null ? new HashSet<>() : new HashSet<>(s);
    }

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

    // ---- Флаги ----
    boolean blackTheme() { return sp.getBoolean(K_BLACK, false); }
    void setBlackTheme(boolean v) { sp.edit().putBoolean(K_BLACK, v).apply(); }

    boolean autoLaunch() { return sp.getBoolean(K_AUTO_LAUNCH, false); }
    void setAutoLaunch(boolean v) { sp.edit().putBoolean(K_AUTO_LAUNCH, v).apply(); }

    boolean showKeyboard() { return sp.getBoolean(K_KEYBOARD, true); }
    void setShowKeyboard(boolean v) { sp.edit().putBoolean(K_KEYBOARD, v).apply(); }

    boolean showDate() { return sp.getBoolean(K_SHOW_DATE, true); }
    void setShowDate(boolean v) { sp.edit().putBoolean(K_SHOW_DATE, v).apply(); }
}
