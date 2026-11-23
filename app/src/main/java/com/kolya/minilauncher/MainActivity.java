package com.kolya.minilauncher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Минималистичный текстовый лаунчер.
 *
 * Главный экран: часы, дата и до 8 избранных приложений текстом.
 * Свайп вверх — список всех приложений с мгновенным поиском.
 * Свайп вниз — шторка уведомлений. Долгое нажатие на пустом месте — настройки.
 */
public class MainActivity extends Activity {

    private static final int TEXT = Color.WHITE;
    private static final int TEXT_DIM = 0xB3FFFFFF;
    private static final int DRAWER_SCRIM = 0xE6000000;

    private Prefs prefs;
    private LauncherApps launcherApps;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private final List<AppEntry> allApps = new ArrayList<>();
    private final Map<String, AppEntry> byKey = new HashMap<>();
    private final List<AppEntry> filtered = new ArrayList<>();

    private FrameLayout root;
    private LinearLayout home;
    private TextClock dateView;
    private LinearLayout favoritesBox;
    private LinearLayout drawer;
    private EditText search;
    private ListView list;
    private final AppAdapter adapter = new AppAdapter();
    private GestureDetector gestures;

    private boolean drawerOpen;
    private OnBackInvokedCallback backCallback;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        launcherApps = getSystemService(LauncherApps.class);

        if (Build.VERSION.SDK_INT < 35) {
            // На Android 15+ edge-to-edge включён принудительно.
            enableEdgeToEdgeLegacy();
        }
        buildUi();
        setContentView(root);
        applyTheme();

        gestures = new GestureDetector(this, new SwipeListener());
        gestures.setIsLongpressEnabled(false);

        launcherApps.registerCallback(packageCallback, main);
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
        f.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        f.addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED);
        if (Build.VERSION.SDK_INT >= 34) {
            f.addAction(Intent.ACTION_PROFILE_AVAILABLE);
            f.addAction(Intent.ACTION_PROFILE_UNAVAILABLE);
        }
        registerReceiver(profileReceiver, f, Context.RECEIVER_EXPORTED);

        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::closeDrawer;
        }
        reloadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadApps();
        maybeAskDefaultLauncher();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Нажатие «Домой», когда лаунчер уже на экране: возвращаемся к главному.
        if (Intent.ACTION_MAIN.equals(intent.getAction())) closeDrawer();
    }

    @Override
    protected void onStop() {
        super.onStop();
        closeDrawerNow();
    }

    @Override
    protected void onDestroy() {
        launcherApps.unregisterCallback(packageCallback);
        try { unregisterReceiver(profileReceiver); } catch (IllegalArgumentException ignored) {}
        io.shutdownNow();
        super.onDestroy();
    }

    /** Android 11–12: системная «Назад». На 13+ используется OnBackInvokedCallback. */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (drawerOpen) closeDrawer();
        // На главном экране «Назад» ничего не делает — это лаунчер.
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        gestures.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        root = new FrameLayout(this);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsets.CONSUMED;
        });

        // ---- Главный экран
        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        home.setPadding(dp(32), dp(40), dp(32), dp(56));
        home.setOnLongClickListener(v -> { showSettings(); return true; });

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("h:mm");
        style(clock, 64, TEXT);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setOnClickListener(v -> safeStart(new Intent(AlarmClock.ACTION_SHOW_ALARMS)));
        home.addView(clock, wrap());

        dateView = new TextClock(this);
        dateView.setFormat24Hour("EEEE, d MMMM");
        dateView.setFormat12Hour("EEEE, d MMMM");
        style(dateView, 18, TEXT_DIM);
        dateView.setOnClickListener(v -> safeStart(Intent.makeMainSelectorActivity(
                Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)));
        home.addView(dateView, wrap());

        View spacer = new View(this);
        home.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        favoritesBox = new LinearLayout(this);
        favoritesBox.setOrientation(LinearLayout.VERTICAL);
        home.addView(favoritesBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(home, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- Список всех приложений
        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setPadding(dp(24), dp(16), dp(24), 0);
        drawer.setVisibility(View.GONE);

        search = new EditText(this);
        search.setHint("Поиск");
        search.setHintTextColor(0x80FFFFFF);
        search.setTextColor(TEXT);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        search.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setPadding(dp(8), dp(12), dp(8), dp(12));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { onQueryChanged(); }
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                if (!filtered.isEmpty()) launch(filtered.get(0), v);
                return true;
            }
            return false;
        });
        drawer.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        list.setFastScrollEnabled(true);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> launch(filtered.get(pos), v));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            showAppMenu(filtered.get(pos), false);
            return true;
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {
                // При прокрутке убираем клавиатуру, чтобы видеть весь список.
                if (state == SCROLL_STATE_TOUCH_SCROLL) hideKeyboard();
            }
            @Override public void onScroll(AbsListView v, int a, int b, int c) {}
        });
        drawer.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(drawer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void applyTheme() {
        boolean black = prefs.blackTheme();
        root.setBackgroundColor(black ? Color.BLACK : Color.TRANSPARENT);
        drawer.setBackgroundColor(black ? Color.BLACK : DRAWER_SCRIM);
        dateView.setVisibility(prefs.showDate() ? View.VISIBLE : View.GONE);
    }

    private void renderFavorites() {
        favoritesBox.removeAllViews();
        List<String> keys = prefs.favorites();
        int shown = 0;
        for (String key : keys) {
            AppEntry app = byKey.get(key);
            if (app == null) continue; // приложение удалено или профиль на паузе
            TextView tv = new TextView(this);
            tv.setText(app.label);
            style(tv, 28, TEXT);
            tv.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            tv.setPadding(0, dp(10), 0, dp(10));
            tv.setOnClickListener(v -> launch(app, v));
            tv.setOnLongClickListener(v -> { showAppMenu(app, true); return true; });
            favoritesBox.addView(tv, wrap());
            shown++;
        }
        if (shown == 0 && !allApps.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("Свайп вверх — все приложения.\n"
                    + "Долгое нажатие на приложение — добавить сюда.\n"
                    + "Долгое нажатие на пустом месте — настройки.");
            style(hint, 15, TEXT_DIM);
            favoritesBox.addView(hint, wrap());
        }
    }

    // ------------------------------------------------------------------ apps

    private void reloadApps() {
        io.execute(() -> {
            List<AppEntry> loaded = AppRepository.load(getApplicationContext(), prefs);
            main.post(() -> {
                if (isDestroyed()) return;
                allApps.clear();
                allApps.addAll(loaded);
                byKey.clear();
                for (AppEntry a : allApps) byKey.put(a.key, a);
                renderFavorites();
                onQueryChanged();
            });
        });
    }

    private void refreshNames() {
        AppRepository.applyNames(allApps, prefs);
        renderFavorites();
        onQueryChanged();
    }

    private void onQueryChanged() {
        String q = search.getText().toString().trim().toLowerCase(Locale.getDefault());
        Set<String> hidden = prefs.hidden();
        filtered.clear();
        if (q.isEmpty()) {
            for (AppEntry a : allApps) if (!hidden.contains(a.key)) filtered.add(a);
        } else {
            // Ранжирование: начало имени → начало слова → вхождение.
            // Скрытые приложения находятся поиском, но не видны в общем списке.
            List<AppEntry> starts = new ArrayList<>();
            List<AppEntry> word = new ArrayList<>();
            List<AppEntry> contains = new ArrayList<>();
            for (AppEntry a : allApps) {
                String s = a.searchKey;
                if (s.startsWith(q)) starts.add(a);
                else if (s.contains(" " + q) || s.contains("-" + q)) word.add(a);
                else if (s.contains(q)) contains.add(a);
            }
            filtered.addAll(starts);
            filtered.addAll(word);
            filtered.addAll(contains);
        }
        adapter.notifyDataSetChanged();
        if (drawerOpen && !q.isEmpty() && prefs.autoLaunch() && filtered.size() == 1) {
            launch(filtered.get(0), list);
        }
    }

    private void launch(AppEntry app, View from) {
        try {
            Rect bounds = new Rect();
            from.getGlobalVisibleRect(bounds);
            Bundle opts = ActivityOptions.makeScaleUpAnimation(
                    from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
            launcherApps.startMainActivity(app.component, app.user, bounds, opts);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть " + app.label, Toast.LENGTH_SHORT).show();
            reloadApps();
        }
    }

    // ------------------------------------------------------------------ drawer

    private void openDrawer() {
        if (drawerOpen) return;
        drawerOpen = true;
        search.setText("");
        list.setSelection(0);
        drawer.setVisibility(View.VISIBLE);
        drawer.setAlpha(0f);
        drawer.setTranslationY(dp(48));
        drawer.animate().alpha(1f).translationY(0).setDuration(160).start();
        home.animate().alpha(0f).setDuration(120).start();
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        if (prefs.showKeyboard()) showKeyboard();
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        hideKeyboard();
        unregisterBack();
        drawer.animate().alpha(0f).translationY(dp(48)).setDuration(140)
                .withEndAction(() -> { if (!drawerOpen) drawer.setVisibility(View.GONE); })
                .start();
        home.animate().alpha(1f).setDuration(160).start();
    }

    /** Мгновенно, без анимации — когда лаунчер уходит с экрана. */
    private void closeDrawerNow() {
        if (!drawerOpen) return;
        drawerOpen = false;
        hideKeyboard();
        unregisterBack();
        drawer.animate().cancel();
        drawer.setVisibility(View.GONE);
        home.animate().cancel();
        home.setAlpha(1f);
        search.setText("");
    }

    private void unregisterBack() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
    }

    private void showKeyboard() {
        search.requestFocus();
        search.post(() -> {
            WindowInsetsController c = search.getWindowInsetsController();
            if (c != null) c.show(WindowInsets.Type.ime());
        });
    }

    private void hideKeyboard() {
        WindowInsetsController c = search.getWindowInsetsController();
        if (c != null) c.hide(WindowInsets.Type.ime());
        search.clearFocus();
    }

    private final class SwipeListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
            if (Math.abs(vy) < Math.abs(vx) * 1.5f || Math.abs(vy) < dp(300)) return false;
            if (!drawerOpen) {
                if (vy < 0) openDrawer(); else expandNotifications();
                return true;
            }
            // В списке: свайп вниз, когда он уже прокручен до самого верха, — закрыть.
            if (vy > 0 && !list.canScrollVertically(-1)) {
                closeDrawer();
                return true;
            }
            return false;
        }
    }

    @SuppressWarnings({"WrongConstant", "JavaReflectionMemberAccess"})
    private void expandNotifications() {
        try {
            Object sb = getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager")
                    .getMethod("expandNotificationsPanel").invoke(sb);
        } catch (Exception ignored) {
            // На некоторых прошивках метод недоступен — просто ничего не делаем.
        }
    }

    // ------------------------------------------------------------------ menus

    private void showAppMenu(AppEntry app, boolean fromHome) {
        hideKeyboard();
        boolean fav = prefs.isFavorite(app.key);
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (fromHome) {
            items.add("Убрать с главного экрана");
            actions.add(() -> { prefs.removeFavorite(app.key); renderFavorites(); });
            items.add("Переместить выше");
            actions.add(() -> { prefs.moveFavorite(app.key, -1); renderFavorites(); });
            items.add("Переместить ниже");
            actions.add(() -> { prefs.moveFavorite(app.key, 1); renderFavorites(); });
        } else {
            items.add(fav ? "Убрать с главного экрана" : "На главный экран");
            actions.add(() -> {
                if (fav) {
                    prefs.removeFavorite(app.key);
                } else if (!prefs.addFavorite(app.key)) {
                    toast("На главном экране максимум " + Prefs.MAX_FAVORITES + " приложений");
                }
                renderFavorites();
            });
            boolean hidden = prefs.hidden().contains(app.key);
            items.add(hidden ? "Показывать в списке" : "Скрыть из списка");
            actions.add(() -> { prefs.setHidden(app.key, !hidden); onQueryChanged(); });
        }
        items.add("Переименовать");
        actions.add(() -> showRename(app));
        items.add("О приложении");
        actions.add(() -> {
            try {
                launcherApps.startAppDetailsActivity(app.component, app.user, null, null);
            } catch (Exception e) {
                toast("Не удалось открыть сведения");
            }
        });
        if (!app.workProfile) {
            items.add("Удалить");
            actions.add(() -> safeStart(new Intent(Intent.ACTION_DELETE,
                    Uri.fromParts("package", app.packageName(), null))));
        }

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(app.label)
                .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void showRename(AppEntry app) {
        EditText input = new EditText(this);
        String current = prefs.rename(app.key);
        input.setText(current != null ? current : app.originalLabel);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input);

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Переименовать")
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    prefs.setRename(app.key, input.getText().toString());
                    refreshNames();
                })
                .setNeutralButton("Исходное имя", (d, w) -> {
                    prefs.setRename(app.key, null);
                    refreshNames();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showSettings() {
        String[] items = {
                "Фон: " + (prefs.blackTheme() ? "чёрный (OLED)" : "обои"),
                "Клавиатура при открытии списка: " + onOff(prefs.showKeyboard()),
                "Автозапуск при одном совпадении: " + onOff(prefs.autoLaunch()),
                "Показывать дату: " + onOff(prefs.showDate()),
                "Скрытые приложения (" + countHidden() + ")",
                "Сменить обои",
                "Сделать лаунчером по умолчанию",
                "Настройки Android",
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Mini Launcher")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: prefs.setBlackTheme(!prefs.blackTheme()); applyTheme(); showSettings(); break;
                        case 1: prefs.setShowKeyboard(!prefs.showKeyboard()); showSettings(); break;
                        case 2: prefs.setAutoLaunch(!prefs.autoLaunch()); showSettings(); break;
                        case 3: prefs.setShowDate(!prefs.showDate()); applyTheme(); showSettings(); break;
                        case 4: showHiddenApps(); break;
                        case 5: safeStart(Intent.createChooser(
                                new Intent(Intent.ACTION_SET_WALLPAPER), "Обои")); break;
                        case 6: requestDefaultLauncher(); break;
                        case 7: safeStart(new Intent(Settings.ACTION_SETTINGS)); break;
                        default: break;
                    }
                })
                .show();
    }

    private void showHiddenApps() {
        Set<String> hidden = prefs.hidden();
        List<AppEntry> apps = new ArrayList<>();
        for (AppEntry a : allApps) if (hidden.contains(a.key)) apps.add(a);
        if (apps.isEmpty()) {
            toast("Скрытых приложений нет. Скрыть: долгое нажатие в списке.");
            return;
        }
        String[] names = new String[apps.size()];
        boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) { names[i] = apps.get(i).label; checked[i] = true; }

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Скрытые (снимите галочку, чтобы вернуть)")
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Готово", (d, w) -> {
                    for (int i = 0; i < apps.size(); i++) {
                        if (!checked[i]) prefs.setHidden(apps.get(i).key, false);
                    }
                    onQueryChanged();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private int countHidden() {
        Set<String> hidden = prefs.hidden();
        int n = 0;
        for (AppEntry a : allApps) if (hidden.contains(a.key)) n++;
        return n;
    }

    // ------------------------------------------------------------------ default launcher

    private boolean isDefaultLauncher() {
        RoleManager rm = getSystemService(RoleManager.class);
        return rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)
                && rm.isRoleHeld(RoleManager.ROLE_HOME);
    }

    @SuppressWarnings("deprecation")
    private void requestDefaultLauncher() {
        RoleManager rm = getSystemService(RoleManager.class);
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
            try {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 42);
                return;
            } catch (ActivityNotFoundException ignored) {}
        }
        safeStart(new Intent(Settings.ACTION_HOME_SETTINGS));
    }

    private void maybeAskDefaultLauncher() {
        SharedPreferences sp = getSharedPreferences("launcher", MODE_PRIVATE);
        if (sp.getBoolean("asked_default", false) || isDefaultLauncher()) return;
        sp.edit().putBoolean("asked_default", true).apply();
        main.postDelayed(this::requestDefaultLauncher, 600);
    }

    // ------------------------------------------------------------------ system events

    private final LauncherApps.Callback packageCallback = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String p, UserHandle u) { reloadApps(); }
        @Override public void onPackageAdded(String p, UserHandle u) { reloadApps(); }
        @Override public void onPackageChanged(String p, UserHandle u) { reloadApps(); }
        @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean r) { reloadApps(); }
        @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) { reloadApps(); }
        @Override public void onPackagesSuspended(String[] p, UserHandle u) { reloadApps(); }
        @Override public void onPackagesUnsuspended(String[] p, UserHandle u) { reloadApps(); }
    };

    private final BroadcastReceiver profileReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reloadApps(); }
    };

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("deprecation")
    private void enableEdgeToEdgeLegacy() {
        getWindow().setDecorFitsSystemWindows(false);
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return filtered.size(); }
        @Override public Object getItem(int i) { return filtered.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv = convertView instanceof TextView ? (TextView) convertView : null;
            if (tv == null) {
                tv = new TextView(MainActivity.this);
                style(tv, 21, TEXT);
                tv.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                tv.setPadding(dp(8), dp(13), dp(8), dp(13));
                tv.setSingleLine(true);
            }
            AppEntry a = filtered.get(position);
            tv.setText(a.label);
            tv.setAlpha(prefs.hidden().contains(a.key) ? 0.5f : 1f);
            return tv;
        }
    }

    private void style(TextView tv, int sp, int color) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setGravity(Gravity.START);
        // Лёгкая тень, чтобы текст читался на светлых обоях.
        tv.setShadowLayer(6f, 0f, 1f, 0x66000000);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void safeStart(Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            toast("Нет подходящего приложения");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String onOff(boolean b) {
        return b ? "вкл" : "выкл";
    }
}
