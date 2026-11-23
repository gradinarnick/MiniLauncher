package com.kolya.minilauncher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Лаунчер с рабочим столом как на iPhone и защитой от залипания.
 *
 * Рабочий стол: часы, дата, виджеты и сетка 4×3 из иконок (до 12 мест, папки) в нижней части.
 * Свайп вверх — список всех приложений с поиском (полупрозрачный фон).
 * Свайп вниз — шторка уведомлений. Долгое нажатие на пустом месте — настройки.
 * Правила (пауза, ночной режим, блок видео) — в {@link Guard}.
 */
public class MainActivity extends Activity {

    private static final int TEXT = Color.WHITE;
    private static final int TEXT_DIM = 0xB3FFFFFF;
    /** Лёгкое затемнение списка приложений, чтобы текст читался поверх обоев. */
    private static final int DRAWER_SCRIM = 0x4D000000;
    private static final int COLS = 4;
    private static final int HOST_ID = 1024;
    private static final int MAX_WIDGETS = 4;
    private static final int REQ_DEFAULT = 42;
    private static final int REQ_BIND = 101;
    private static final int REQ_CONFIG = 102;

    private Prefs prefs;
    private LauncherApps launcherApps;
    private AppWidgetManager widgetManager;
    private AppWidgetHost widgetHost;
    private int pendingWidgetId = -1;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private final List<AppEntry> allApps = new ArrayList<>();
    private final Map<String, AppEntry> byKey = new HashMap<>();
    private final List<AppEntry> filtered = new ArrayList<>();
    private final Map<String, Drawable> icons = new HashMap<>();

    private FrameLayout root;
    private LinearLayout home;
    private TextClock dateView;
    private TextView statusLine;
    private LinearLayout widgetsBox;
    private LinearLayout grid;
    private LinearLayout drawer;
    private EditText search;
    private ListView list;
    private final AppAdapter adapter = new AppAdapter();
    private GestureDetector gestures;

    private boolean drawerOpen;
    private OnBackInvokedCallback backCallback;
    private AlertDialog delayDialog;
    private boolean lastNight;
    /** Перетаскивание на рабочем столе: что тащим и чем закончилось. */
    private String dragItem;          // "a:…"/"f:…" с рабочего стола или "new:<ключ>" из списка
    private boolean dropOnSelf;
    private boolean dropDone;
    private float dragStartX, dragStartY, dragMaxMove;
    /** Снимок сетки на время перетаскивания (чтобы не читать настройки на каждое движение). */
    private java.util.TreeMap<Integer, String> dragGrid = new java.util.TreeMap<>();
    private TextView removeZone;
    private TextView emptyHint;
    private boolean lastVideo;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        launcherApps = getSystemService(LauncherApps.class);
        widgetManager = AppWidgetManager.getInstance(this);
        widgetHost = new AppWidgetHost(this, HOST_ID);
        Guard.refreshSystemApps(this);

        if (Build.VERSION.SDK_INT < 35) {
            // На Android 15+ edge-to-edge включён принудительно.
            enableEdgeToEdgeLegacy();
        }
        buildUi();
        setContentView(root);
        applyTheme();
        renderWidgets();

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

        IntentFilter t = new IntentFilter();
        t.addAction(Intent.ACTION_TIME_TICK);
        t.addAction(Intent.ACTION_TIME_CHANGED);
        t.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, t, Context.RECEIVER_EXPORTED);

        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::closeDrawer;
        }
        lastNight = Guard.nightActive(prefs);
        lastVideo = Guard.videoActive(prefs);
        reloadApps();
    }

    @Override
    protected void onStart() {
        super.onStart();
        try { widgetHost.startListening(); } catch (Exception ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        Guard.refreshSystemApps(this);
        reloadApps();
        updateGuards(true);
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
        if (delayDialog != null) {
            delayDialog.dismiss();
            delayDialog = null;
        }
        try { widgetHost.stopListening(); } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        launcherApps.unregisterCallback(packageCallback);
        try { unregisterReceiver(profileReceiver); } catch (IllegalArgumentException ignored) {}
        try { unregisterReceiver(timeReceiver); } catch (IllegalArgumentException ignored) {}
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

        // ---- Рабочий стол
        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        home.setPadding(dp(16), dp(32), dp(16), dp(28));
        home.setOnLongClickListener(v -> { showSettings(); return true; });

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("h:mm");
        style(clock, 64, TEXT);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setPadding(dp(8), 0, dp(8), 0);
        clock.setOnClickListener(v -> safeStart(new Intent(AlarmClock.ACTION_SHOW_ALARMS)));
        home.addView(clock, wrap());

        dateView = new TextClock(this);
        dateView.setFormat24Hour("EEEE, d MMMM");
        dateView.setFormat12Hour("EEEE, d MMMM");
        style(dateView, 18, TEXT_DIM);
        dateView.setPadding(dp(8), 0, dp(8), 0);
        dateView.setOnClickListener(v -> {
            if (Guard.nightActive(prefs)) { toast("Ночной режим до 9:00"); return; }
            safeStart(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR));
        });
        home.addView(dateView, wrap());

        statusLine = new TextView(this);
        style(statusLine, 14, 0xFFFFD27A);
        statusLine.setPadding(dp(8), dp(6), dp(8), 0);
        statusLine.setVisibility(View.GONE);
        home.addView(statusLine, wrap());

        widgetsBox = new LinearLayout(this);
        widgetsBox.setOrientation(LinearLayout.VERTICAL);
        home.addView(widgetsBox, matchWrap());

        emptyHint = new TextView(this);
        emptyHint.setText("Свайп вверх — все приложения. Зажми приложение и перетащи сюда.\n"
                + "Долгое нажатие на пустом месте — настройки.");
        style(emptyHint, 14, TEXT_DIM);
        emptyHint.setPadding(dp(8), dp(12), dp(8), 0);
        emptyHint.setVisibility(View.GONE);
        home.addView(emptyHint, wrap());

        // Свободная сетка 4×6 на всё оставшееся место — как в Pixel Launcher.
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        glp.topMargin = dp(8);
        home.addView(grid, glp);

        root.addView(home, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- Список всех приложений
        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setPadding(dp(24), dp(16), dp(24), 0);
        drawer.setVisibility(View.GONE);

        search = new EditText(this);
        search.setHint("Поиск");
        search.setHintTextColor(0x99FFFFFF);
        search.setTextColor(TEXT);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        search.setShadowLayer(6f, 0f, 1f, 0x99000000);
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
        drawer.addView(search, matchWrap());

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        list.setFastScrollEnabled(true);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> launch(filtered.get(pos), v));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            startDrawerDrag(filtered.get(pos), v);
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

        // ---- «✕ Убрать» сверху, пока тащишь иконку с рабочего стола (как в Pixel Launcher)
        removeZone = new TextView(this);
        removeZone.setText("✕  Убрать");
        removeZone.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        removeZone.setTextColor(TEXT);
        removeZone.setShadowLayer(8f, 0f, 1f, 0xAA000000);
        removeZone.setGravity(Gravity.CENTER);
        removeZone.setVisibility(View.GONE);
        removeZone.setOnDragListener((v, e) -> {
            switch (e.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return dragItem != null && !dragItem.startsWith("new:");
                case DragEvent.ACTION_DRAG_ENTERED:
                    removeZone.setTextColor(0xFFFF6B6B);
                    removeZone.setScaleX(1.1f);
                    removeZone.setScaleY(1.1f);
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    removeZone.setTextColor(TEXT);
                    removeZone.setScaleX(1f);
                    removeZone.setScaleY(1f);
                    return true;
                case DragEvent.ACTION_DROP:
                    dropDone = true;
                    removeDragged(dragItem);
                    return true;
                default:
                    return true;
            }
        });
        root.addView(removeZone, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56), Gravity.TOP));

        // Конец перетаскивания (куда бы ни отпустили): вернуть экран в обычный вид.
        home.setOnDragListener((v, e) -> {
            switch (e.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    dragStartX = e.getX();
                    dragStartY = e.getY();
                    dragMaxMove = 0f;
                    return true;
                case DragEvent.ACTION_DRAG_LOCATION:
                    dragMaxMove = Math.max(dragMaxMove,
                            Math.abs(e.getX() - dragStartX) + Math.abs(e.getY() - dragStartY));
                    return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    onDragFinished();
                    return true;
                default:
                    return true;
            }
        });
    }

    private void applyTheme() {
        boolean black = prefs.blackTheme();
        root.setBackgroundColor(black ? Color.BLACK : Color.TRANSPARENT);
        drawer.setBackgroundColor(black ? Color.BLACK : DRAWER_SCRIM);
        dateView.setVisibility(prefs.showDate() ? View.VISIBLE : View.GONE);
    }

    private void updateStatus() {
        List<String> parts = new ArrayList<>();
        if (Guard.nightActive(prefs)) parts.add("🌙 Ночной режим до 9:00");
        if (Guard.videoActive(prefs)) {
            parts.add(prefs.videoUnblockAt() > 0 ? "Видео заблокировано до 9:00" : "Видео заблокировано");
        }
        statusLine.setText(TextUtils.join(" · ", parts));
        statusLine.setVisibility(parts.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Пересчитывает ограничения; при смене состояния перерисовывает экран. */
    private void updateGuards(boolean force) {
        boolean n = Guard.nightActive(prefs);
        boolean v = Guard.videoActive(prefs);
        if (force || n != lastNight || v != lastVideo) {
            lastNight = n;
            lastVideo = v;
            renderHome();
            onQueryChanged();
        }
        updateStatus();
    }

    // ------------------------------------------------------------------ рабочий стол

    private void renderHome() {
        grid.removeAllViews();
        if (allApps.isEmpty()) return; // приложения ещё загружаются
        java.util.TreeMap<Integer, String> g = prefs.grid();
        emptyHint.setVisibility(g.isEmpty() ? View.VISIBLE : View.GONE);
        for (int r = 0; r < Prefs.ROWS; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            for (int c = 0; c < Prefs.COLS; c++) {
                int cell = r * Prefs.COLS + c;
                String item = g.get(cell);
                View v = item == null ? null : homeCell(item);
                if (v == null) v = new View(this);
                FrameLayout slot = new FrameLayout(this);
                slot.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
                slot.setOnDragListener(new SlotDrag(cell));
                row.addView(slot, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            }
            grid.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
    }

    /** Вид элемента рабочего стола или null, если приложения сейчас нет. */
    private View homeCell(String item) {
        if (item.startsWith("a:")) {
            AppEntry app = byKey.get(item.substring(2));
            // Две зоны: отвлекающие приложения на рабочем столе не показываются.
            if (app == null || Guard.isDistracting(prefs, app.packageName())) return null;
            return appCell(app, null);
        }
        if (item.startsWith("f:")) {
            String id = item.substring(2);
            List<AppEntry> apps = folderEntries(id);
            return apps.isEmpty() ? null : folderCell(id, apps);
        }
        return null;
    }

    // ------------------------------------------------------------------ перетаскивание

    private void startDrag(String item, View cell, View icon) {
        dragItem = item;
        dragGrid = prefs.grid();
        dropOnSelf = false;
        dropDone = false;
        cell.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        ClipData data = ClipData.newPlainText("launcher", item);
        // «Убрать» показываем ДО начала перетаскивания: иначе зона не получит событий.
        removeZone.setVisibility(View.VISIBLE);
        if (cell.startDragAndDrop(data, new View.DragShadowBuilder(icon), item, 0)) {
            cell.setAlpha(0.3f);
        } else {
            dragItem = null;
            removeZone.setVisibility(View.GONE);
            showMenuFor(item);
        }
    }

    /** Из общего списка: зажал — список уезжает, тащишь иконку прямо на рабочий стол. */
    private void startDrawerDrag(AppEntry app, View row) {
        if (Guard.isDistracting(prefs, app.packageName())) {
            // Две зоны: отвлекающие на рабочий стол нельзя — сразу меню.
            showDrawerAppMenu(app);
            return;
        }
        View icon = row instanceof ViewGroup && ((ViewGroup) row).getChildCount() > 0
                ? ((ViewGroup) row).getChildAt(0) : row;
        dragItem = "new:" + app.key;
        dragGrid = prefs.grid();
        dropOnSelf = false;
        dropDone = false;
        row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        hideKeyboard();
        home.animate().cancel();
        home.setAlpha(1f);
        ClipData data = ClipData.newPlainText("launcher", dragItem);
        if (row.startDragAndDrop(data, new View.DragShadowBuilder(icon), dragItem, 0)) {
            drawerOpen = false;
            unregisterBack();
            drawer.animate().cancel();
            drawer.setVisibility(View.GONE);
        } else {
            dragItem = null;
            showDrawerAppMenu(app);
        }
    }

    /** Подсветка и приём «броска» на клетку сетки. */
    private final class SlotDrag implements View.OnDragListener {
        private final int cell;

        SlotDrag(int cell) { this.cell = cell; }

        @Override
        public boolean onDrag(View v, DragEvent e) {
            switch (e.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return dragItem != null;
                case DragEvent.ACTION_DRAG_ENTERED:
                case DragEvent.ACTION_DRAG_LOCATION:
                    v.setBackground(highlight(v, e, cell));
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    v.setBackground(null);
                    return true;
                case DragEvent.ACTION_DROP:
                    v.setBackground(null);
                    dropDone = true;
                    dropOn(cell, overIcon(v, e));
                    return true;
                default:
                    return true;
            }
        }
    }

    /** Палец над самой иконкой (а не у края клетки) — «положить в папку». */
    private boolean overIcon(View v, DragEvent e) {
        float cx = v.getWidth() / 2f, cy = v.getHeight() / 2f;
        return Math.abs(e.getX() - cx) < dp(26) && Math.abs(e.getY() - cy) < dp(30);
    }

    private Drawable highlight(View v, DragEvent e, int cell) {
        String target = dragGrid.get(cell);
        if (dragItem == null || dragItem.equals(target)) return null;
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(18));
        boolean merge = target != null && !dragItem.startsWith("f:") && overIcon(v, e);
        d.setColor(merge ? 0x55FFFFFF : 0x22FFFFFF);
        return d;
    }

    private void dropOn(int cell, boolean merge) {
        String src = dragItem;
        if (src == null) return;
        boolean fromList = src.startsWith("new:");
        String item = fromList ? "a:" + src.substring(4) : src;
        java.util.TreeMap<Integer, String> g = prefs.grid();
        String target = g.get(cell);
        if (item.equals(target)) {
            if (!fromList) dropOnSelf = true; // подержал и отпустил на месте — меню
            return;
        }
        int from = -1;
        for (Map.Entry<Integer, String> e : g.entrySet()) if (e.getValue().equals(item)) from = e.getKey();

        if (target != null && merge && item.startsWith("a:")) {
            String key = item.substring(2);
            if (fromList) removeFromFolders(key);
            g = prefs.grid();
            if (from >= 0) g.remove(from);
            if (target.startsWith("f:")) {
                String id = target.substring(2);
                List<String> apps = prefs.folderApps(id);
                if (!apps.contains(key)) apps.add(key);
                prefs.setFolderApps(id, apps);
            } else {
                // Иконка на иконку — папка, как в Pixel Launcher.
                String id = prefs.newFolderId();
                prefs.setFolderName(id, "Папка");
                List<String> apps = new ArrayList<>();
                apps.add(target.substring(2));
                apps.add(key);
                prefs.setFolderApps(id, apps);
                g.put(cell, "f:" + id);
            }
            prefs.setGrid(g);
            return;
        }

        if (fromList && from < 0) {
            if (g.size() >= Prefs.MAX_HOME) {
                toast("На рабочем столе максимум " + Prefs.MAX_HOME + " приложений. Можно сложить в папку.");
                return;
            }
            removeFromFolders(item.substring(2));
            g = prefs.grid();
            if (target != null) {
                // Клетка занята — сдвигаем её жильца в ближайшую свободную.
                int free = nearestFree(g, cell);
                if (free < 0) return;
                g.put(free, target);
            }
            g.put(cell, item);
            prefs.setGrid(g);
            return;
        }

        if (from < 0) return;
        // Перенос по сетке: на пустую клетку — просто туда, на занятую — меняемся местами.
        g.remove(from);
        if (target != null) g.put(from, target);
        g.put(cell, item);
        prefs.setGrid(g);
    }

    private int nearestFree(java.util.TreeMap<Integer, String> g, int cell) {
        int best = -1, bestDist = Integer.MAX_VALUE;
        int r0 = cell / Prefs.COLS, c0 = cell % Prefs.COLS;
        for (int i = 0; i < Prefs.CELLS; i++) {
            if (g.containsKey(i)) continue;
            int d = Math.abs(i / Prefs.COLS - r0) + Math.abs(i % Prefs.COLS - c0);
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }

    private void removeDragged(String item) {
        if (item == null || item.startsWith("new:")) return;
        if (item.startsWith("a:")) {
            removeEverywhere(item.substring(2));
        } else if (item.startsWith("f:")) {
            String id = item.substring(2);
            main.post(() -> deleteFolder(id));
        }
    }

    private void onDragFinished() {
        if (dragItem == null) return;
        String item = dragItem;
        boolean menu = dropOnSelf;
        // Из списка: подержал и отпустил, не двигая, — вернуть список и показать меню.
        boolean listMenu = item.startsWith("new:") && dragMaxMove < dp(24);
        dragItem = null;
        dropOnSelf = false;
        removeZone.setVisibility(View.GONE);
        main.post(() -> {
            renderHome();
            if (menu) showMenuFor(item);
            if (listMenu) {
                AppEntry app = byKey.get(item.substring(4));
                if (app != null) {
                    openDrawer();
                    showDrawerAppMenu(app);
                }
            }
        });
    }

    private void showMenuFor(String item) {
        if (item.startsWith("a:")) {
            AppEntry app = byKey.get(item.substring(2));
            if (app != null) showHomeAppMenu(app);
        } else if (item.startsWith("f:")) {
            showFolderMenu(item.substring(2));
        }
    }

    private void addRows(LinearLayout target, List<View> cells) {
        int rows = (cells.size() + COLS - 1) / COLS;
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < COLS; c++) {
                int i = r * COLS + c;
                View cell = i < cells.size() ? cells.get(i) : new View(this);
                row.addView(cell, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            target.addView(row, matchWrap());
        }
    }

    /** Ячейка приложения. folderDialog != null — ячейка внутри открытой папки. */
    private View appCell(AppEntry app, AlertDialog folderDialog) {
        LinearLayout cell = cellBase(app.label);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(share(icon(app)));
        applyIconFilter(iv);
        cell.addView(iv, 0, new LinearLayout.LayoutParams(dp(56), dp(56)));
        if (Guard.blockReason(prefs, app.packageName()) != null) cell.setAlpha(0.35f);
        cell.setOnClickListener(v -> {
            if (folderDialog != null) folderDialog.dismiss();
            launch(app, v);
        });
        cell.setOnLongClickListener(v -> {
            if (folderDialog != null) {
                folderDialog.dismiss();
                showFolderAppMenu(app);
            } else {
                startDrag("a:" + app.key, cell, iv);
            }
            return true;
        });
        return cell;
    }

    private View folderCell(String id, List<AppEntry> apps) {
        LinearLayout cell = cellBase(prefs.folderName(id));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(5), dp(5), dp(5), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x55FFFFFF);
        bg.setCornerRadius(dp(14));
        box.setBackground(bg);
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 2; c++) {
                int i = r * 2 + c;
                ImageView mini = new ImageView(this);
                if (i < apps.size()) {
                    mini.setImageDrawable(share(icon(apps.get(i))));
                    applyIconFilter(mini);
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(20), dp(20));
                lp.setMargins(dp(1), dp(1), dp(1), dp(1));
                row.addView(mini, lp);
            }
            box.addView(row, wrap());
        }
        cell.addView(box, 0, new LinearLayout.LayoutParams(dp(56), dp(56)));
        cell.setOnClickListener(v -> openFolder(id));
        cell.setOnLongClickListener(v -> { startDrag("f:" + id, cell, box); return true; });
        return cell;
    }

    private LinearLayout cellBase(String label) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(4), dp(8), dp(4), dp(8));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(TEXT);
        tv.setShadowLayer(4f, 0f, 1f, 0x99000000);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        cell.addView(tv, lp);
        return cell;
    }

    private Drawable icon(AppEntry app) {
        Drawable d = icons.get(app.key);
        if (d != null) return d;
        try {
            d = app.info.getBadgedIcon(0);
        } catch (Exception e) {
            d = null;
        }
        if (d == null) d = getDrawable(android.R.drawable.sym_def_app_icon);
        icons.put(app.key, d);
        return d;
    }

    /** Отдельная копия иконки для каждого ImageView (одну Drawable нельзя показывать в двух местах). */
    private Drawable share(Drawable d) {
        Drawable.ConstantState cs = d == null ? null : d.getConstantState();
        return cs != null ? cs.newDrawable(getResources()) : d;
    }

    /** Иконка для строки списка: из кэша или загрузка в фоне (чтобы список не тормозил). */
    private void bindIcon(ImageView iv, AppEntry a) {
        iv.setTag(a.key);
        applyIconFilter(iv);
        Drawable cached = icons.get(a.key);
        if (cached != null) {
            iv.setImageDrawable(share(cached));
            return;
        }
        iv.setImageDrawable(null);
        io.execute(() -> {
            Drawable loaded;
            try {
                loaded = a.info.getBadgedIcon(0);
            } catch (Exception e) {
                loaded = null;
            }
            Drawable fin = loaded;
            main.post(() -> {
                Drawable d = fin != null ? fin : getDrawable(android.R.drawable.sym_def_app_icon);
                icons.put(a.key, d);
                if (a.key.equals(iv.getTag())) iv.setImageDrawable(share(d));
            });
        });
    }

    private void applyIconFilter(ImageView iv) {
        if (prefs.grayIcons()) {
            ColorMatrix m = new ColorMatrix();
            m.setSaturation(0f);
            iv.setColorFilter(new ColorMatrixColorFilter(m));
        } else {
            iv.clearColorFilter();
        }
    }

    private List<AppEntry> folderEntries(String id) {
        List<AppEntry> out = new ArrayList<>();
        for (String key : prefs.folderApps(id)) {
            AppEntry a = byKey.get(key);
            if (a != null && !Guard.isDistracting(prefs, a.packageName())) out.add(a);
        }
        return out;
    }

    private void openFolder(String id) {
        List<AppEntry> apps = folderEntries(id);
        if (apps.isEmpty()) return;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(8), dp(12), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(prefs.folderName(id))
                .setView(scroll)
                .create();
        List<View> cells = new ArrayList<>();
        for (AppEntry a : apps) cells.add(appCell(a, dialog));
        addRows(content, cells);
        dialog.show();
    }

    // ------------------------------------------------------------------ apps

    private void reloadApps() {
        io.execute(() -> {
            List<AppEntry> loaded = AppRepository.load(getApplicationContext(), prefs);
            Guard.autoClassify(getApplicationContext(), prefs, loaded);
            main.post(() -> {
                if (isDestroyed()) return;
                allApps.clear();
                allApps.addAll(loaded);
                byKey.clear();
                for (AppEntry a : allApps) byKey.put(a.key, a);
                icons.keySet().retainAll(byKey.keySet());
                renderHome();
                onQueryChanged();
                updateStatus();
            });
        });
    }

    private void refreshNames() {
        AppRepository.applyNames(allApps, prefs);
        renderHome();
        onQueryChanged();
    }

    private void onQueryChanged() {
        String q = search.getText().toString().trim().toLowerCase(Locale.getDefault());
        Set<String> hidden = prefs.hidden();
        boolean night = Guard.nightActive(prefs);
        filtered.clear();
        List<AppEntry> pool = new ArrayList<>();
        for (AppEntry a : allApps) {
            // Ночью в списке только то, что разрешено.
            if (night && !Guard.allowedAtNight(prefs, a.packageName())) continue;
            pool.add(a);
        }
        if (q.isEmpty()) {
            for (AppEntry a : pool) if (!hidden.contains(a.key)) filtered.add(a);
        } else {
            // Ранжирование: начало имени → начало слова → вхождение.
            // Скрытые приложения находятся поиском, но не видны в общем списке.
            List<AppEntry> starts = new ArrayList<>();
            List<AppEntry> word = new ArrayList<>();
            List<AppEntry> contains = new ArrayList<>();
            for (AppEntry a : pool) {
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

    /** Все запуски идут через эту проверку: блокировки → пауза → запуск. */
    private void launch(AppEntry app, View from) {
        String reason = Guard.blockReason(prefs, app.packageName());
        if (reason != null) {
            hideKeyboard();
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(app.label)
                    .setMessage(reason)
                    .setPositiveButton("Понятно", null)
                    .show();
            return;
        }
        if (Guard.isDistracting(prefs, app.packageName())) {
            showDelay(app, from);
            return;
        }
        startApp(app, from);
    }

    private void startApp(AppEntry app, View from) {
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

    /** Пауза 20 секунд перед открытием отвлекающего приложения. */
    private void showDelay(AppEntry app, View from) {
        hideKeyboard();
        if (delayDialog != null) delayDialog.dismiss();
        final int[] left = {Guard.DELAY_SECONDS};
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(app.label)
                .setMessage("Пауза " + Guard.DELAY_SECONDS + " секунд.\n\n"
                        + "Зачем ты открываешь это приложение?\nМожет, сейчас не нужно?")
                .setNegativeButton("Не открывать", null)
                .setPositiveButton("Открыть (" + left[0] + ")", (d, w) -> startApp(app, from))
                .create();
        dialog.setCanceledOnTouchOutside(false);
        Runnable tick = new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing()) return;
                Button b = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                left[0]--;
                if (left[0] > 0) {
                    b.setText("Открыть (" + left[0] + ")");
                    main.postDelayed(this, 1000);
                } else {
                    b.setText("Открыть");
                    b.setEnabled(true);
                }
            }
        };
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            main.postDelayed(tick, 1000);
        });
        dialog.setOnDismissListener(d -> {
            main.removeCallbacks(tick);
            if (delayDialog == dialog) delayDialog = null;
        });
        delayDialog = dialog;
        dialog.show();
    }

    // ------------------------------------------------------------------ рабочий стол: изменения

    private boolean addToHome(AppEntry app) {
        if (Guard.isDistracting(prefs, app.packageName())) {
            toast("Отвлекающие приложения нельзя ставить на рабочий стол");
            return false;
        }
        String item = "a:" + app.key;
        if (prefs.cellOf(item) >= 0) return true;
        if (prefs.grid().size() >= Prefs.MAX_HOME) {
            toast("На рабочем столе максимум " + Prefs.MAX_HOME + " приложений. Можно сложить в папку.");
            return false;
        }
        removeFromFolders(app.key);
        if (!prefs.placeItem(item, -1)) {
            toast("На рабочем столе нет свободного места");
            return false;
        }
        return true;
    }

    /** Убирает приложение и с рабочего стола, и из всех папок. */
    private void removeEverywhere(String key) {
        prefs.removeItem("a:" + key);
        removeFromFolders(key);
    }

    private void removeFromFolders(String key) {
        for (String id : prefs.folderIds()) {
            List<String> apps = prefs.folderApps(id);
            if (apps.remove(key)) {
                if (apps.isEmpty()) {
                    prefs.removeItem("f:" + id);
                    prefs.deleteFolderData(id);
                } else {
                    prefs.setFolderApps(id, apps);
                }
            }
        }
    }

    private void pickFolder(AppEntry app) {
        if (Guard.isDistracting(prefs, app.packageName())) {
            toast("Отвлекающие приложения нельзя класть в папки");
            return;
        }
        List<String> ids = prefs.folderIds();
        String current = prefs.folderOf(app.key);
        List<String> names = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        for (String id : ids) {
            if (id.equals(current)) continue;
            names.add("📁 " + prefs.folderName(id));
            targets.add(id);
        }
        names.add("＋ Новая папка…");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("В папку")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    if (which < targets.size()) moveToFolder(app, targets.get(which));
                    else askText("Новая папка", "", name -> moveToNewFolder(app, name));
                })
                .show();
    }

    private void moveToFolder(AppEntry app, String id) {
        removeEverywhere(app.key);
        List<String> apps = prefs.folderApps(id);
        if (!apps.contains(app.key)) apps.add(app.key);
        prefs.setFolderApps(id, apps);
        renderHome();
    }

    private void moveToNewFolder(AppEntry app, String name) {
        String item = "a:" + app.key;
        boolean onHome = prefs.cellOf(item) >= 0;
        if (!onHome && prefs.grid().size() >= Prefs.MAX_HOME) {
            toast("На рабочем столе нет места для новой папки");
            return;
        }
        String id = prefs.newFolderId();
        prefs.setFolderName(id, name);
        removeFromFolders(app.key);
        if (prefs.cellOf(item) >= 0) prefs.replaceItem(item, "f:" + id);
        else if (!prefs.placeItem("f:" + id, -1)) { toast("Нет свободного места"); return; }
        List<String> apps = new ArrayList<>();
        apps.add(app.key);
        prefs.setFolderApps(id, apps);
        renderHome();
    }

    private void deleteFolder(String id) {
        int cell = prefs.cellOf("f:" + id);
        if (cell < 0) return;
        List<String> apps = prefs.folderApps(id);
        prefs.removeItem("f:" + id);
        prefs.deleteFolderData(id);
        int dropped = 0;
        boolean first = true;
        for (String key : apps) {
            if (!prefs.placeItem("a:" + key, first ? cell : -1)) dropped++;
            first = false;
        }
        if (dropped > 0) toast("Не поместилось: " + dropped + ". Они остались в списке приложений.");
        renderHome();
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

    private void showDrawerAppMenu(AppEntry app) {
        hideKeyboard();
        String pkg = app.packageName();
        boolean distracting = Guard.isDistracting(prefs, pkg);
        boolean onHome = prefs.home().contains("a:" + app.key);
        String folder = prefs.folderOf(app.key);
        Menu m = new Menu();

        if (!distracting) {
            if (onHome) {
                m.add("Убрать с рабочего стола", () -> { removeEverywhere(app.key); renderHome(); });
            } else if (folder != null) {
                m.add("Убрать из папки «" + prefs.folderName(folder) + "»",
                        () -> { removeEverywhere(app.key); renderHome(); });
            } else {
                m.add("На рабочий стол", () -> { addToHome(app); renderHome(); });
            }
            m.add("В папку…", () -> pickFolder(app));
        }
        m.add(distracting ? "Отвлекающее: да (снять отметку)" : "Отметить как отвлекающее (пауза 20 сек)",
                () -> setDistracting(pkg, !distracting));
        boolean hidden = prefs.hidden().contains(app.key);
        m.add(hidden ? "Показывать в списке" : "Скрыть из списка",
                () -> { prefs.setHidden(app.key, !hidden); onQueryChanged(); });
        addCommon(m, app);
        if (!app.workProfile) {
            m.add("Удалить", () -> safeStart(new Intent(Intent.ACTION_DELETE,
                    Uri.fromParts("package", pkg, null))));
        }
        m.show(app.label);
    }

    private void showHomeAppMenu(AppEntry app) {
        Menu m = new Menu();
        m.add("Убрать с рабочего стола", () -> { removeEverywhere(app.key); renderHome(); });
        m.add("В папку…", () -> pickFolder(app));
        addCommon(m, app);
        m.show(app.label);
    }

    private void showFolderAppMenu(AppEntry app) {
        Menu m = new Menu();
        m.add("Вынуть из папки на рабочий стол", () -> {
            removeFromFolders(app.key);
            if (!addToHome(app)) toast("Приложение осталось в общем списке");
            renderHome();
        });
        m.add("В другую папку…", () -> pickFolder(app));
        addCommon(m, app);
        m.show(app.label);
    }

    private void showFolderMenu(String id) {
        Menu m = new Menu();
        m.add("Открыть", () -> openFolder(id));
        m.add("Переименовать папку", () -> askText("Название папки", prefs.folderName(id),
                name -> { prefs.setFolderName(id, name); renderHome(); }));
        m.add("Удалить папку (приложения вернутся на стол)", () -> deleteFolder(id));
        m.show(prefs.folderName(id));
    }

    private void addCommon(Menu m, AppEntry app) {
        m.add("Переименовать", () -> showRename(app));
        m.add("О приложении", () -> {
            try {
                launcherApps.startAppDetailsActivity(app.component, app.user, null, null);
            } catch (Exception e) {
                toast("Не удалось открыть сведения");
            }
        });
    }

    private void setDistracting(String pkg, boolean on) {
        Set<String> s = prefs.distracting();
        if (on) s.add(pkg); else s.remove(pkg);
        prefs.setDistracting(s);
        if (on) {
            // Две зоны: убираем с рабочего стола и из папок.
            for (AppEntry a : allApps) if (a.packageName().equals(pkg)) removeEverywhere(a.key);
            toast("Теперь только из списка и после паузы 20 сек");
        }
        renderHome();
        onQueryChanged();
    }

    private void showRename(AppEntry app) {
        String current = prefs.rename(app.key);
        EditText input = new EditText(this);
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

    private interface TextResult { void done(String text); }

    private void askText(String title, String initial, TextResult result) {
        EditText input = new EditText(this);
        input.setText(initial);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Готово", (d, w) -> result.done(input.getText().toString()))
                .setNegativeButton("Отмена", null)
                .show();
    }

    // ------------------------------------------------------------------ settings

    private void showSettings() {
        boolean night = Guard.nightActive(prefs);
        boolean video = Guard.videoActive(prefs);
        Menu m = new Menu();
        m.add("Фон: " + (prefs.blackTheme() ? "чёрный (OLED)" : "обои"), () -> {
            prefs.setBlackTheme(!prefs.blackTheme()); applyTheme(); showSettings();
        });
        m.add("Иконки: " + (prefs.grayIcons() ? "чёрно-белые" : "цветные"), () -> {
            prefs.setGrayIcons(!prefs.grayIcons()); renderHome(); showSettings();
        });
        m.add("Добавить виджет", this::pickWidget);
        if (!prefs.widgets().isEmpty()) m.add("Убрать виджет", this::removeWidgetDialog);

        m.add("Отвлекающие приложения (" + countPackages(prefs.distracting()) + ") — пауза "
                + Guard.DELAY_SECONDS + " сек", this::editDistracting);

        String videoState;
        if (!video) videoState = "выкл";
        else if (prefs.videoUnblockAt() > 0) videoState = "вкл, выключится в 9:00";
        else videoState = "вкл";
        m.add("Блок видео и браузеров: " + videoState, this::toggleVideoBlock);
        m.add("Что блокировать как видео (" + countPackages(prefs.videoApps()) + ")", this::editVideoApps);

        m.add("Ночной режим " + Guard.NIGHT_START_HOUR + ":00–" + Guard.NIGHT_END_HOUR + ":00: "
                + onOff(prefs.nightMode()) + (night ? " (сейчас ночь)" : ""), () -> {
            if (prefs.nightMode() && night) {
                toast("Ночью выключить нельзя. До 9:00.");
                return;
            }
            prefs.setNightMode(!prefs.nightMode());
            updateGuards(true);
            showSettings();
        });
        m.add("Разрешено ночью (" + countPackages(prefs.nightAllowed()) + " + звонки и будильник)",
                this::editNightAllowed);

        m.add("Клавиатура при открытии списка: " + onOff(prefs.showKeyboard()), () -> {
            prefs.setShowKeyboard(!prefs.showKeyboard()); showSettings();
        });
        m.add("Автозапуск при одном совпадении: " + onOff(prefs.autoLaunch()), () -> {
            prefs.setAutoLaunch(!prefs.autoLaunch()); showSettings();
        });
        m.add("Показывать дату: " + onOff(prefs.showDate()), () -> {
            prefs.setShowDate(!prefs.showDate()); applyTheme(); showSettings();
        });
        m.add("Скрытые приложения (" + countHidden() + ")", this::showHiddenApps);
        m.add("Сменить обои", () -> safeStart(Intent.createChooser(
                new Intent(Intent.ACTION_SET_WALLPAPER), "Обои")));
        m.add("Сделать лаунчером по умолчанию", this::requestDefaultLauncher);
        m.add("Настройки Android", () -> safeStart(new Intent(Settings.ACTION_SETTINGS)));
        m.show("Mini Launcher");
    }

    private void toggleVideoBlock() {
        if (!Guard.videoActive(prefs)) {
            prefs.setVideoBlock(true);
            prefs.setVideoUnblockAt(0);
            toast("Видео и браузеры заблокированы. Видео — только в Telegram.");
        } else if (prefs.videoUnblockAt() > 0) {
            prefs.setVideoUnblockAt(0);
            toast("Отмена выключения: блок остаётся");
        } else {
            prefs.setVideoUnblockAt(Guard.nextMorning(System.currentTimeMillis()));
            toast("Блок выключится в 9:00. Раньше — никак.");
        }
        updateGuards(true);
        showSettings();
    }

    /** Уникальные пакеты всех приложений: пакет → имя, по алфавиту. */
    private LinkedHashMap<String, String> packageLabels() {
        Map<String, String> m = new HashMap<>();
        for (AppEntry a : allApps) {
            if (!m.containsKey(a.packageName())) m.put(a.packageName(), a.label);
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(m.entrySet());
        Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.PRIMARY);
        entries.sort((x, y) -> collator.compare(x.getValue(), y.getValue()));
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entries) out.put(e.getKey(), e.getValue());
        return out;
    }

    private interface SetResult { void done(Set<String> chosen, Set<String> before); }

    private void editPackages(String title, Set<String> current, Set<String> exclude, SetResult result) {
        LinkedHashMap<String, String> pkgs = packageLabels();
        List<String> keys = new ArrayList<>();
        for (String p : pkgs.keySet()) if (exclude == null || !exclude.contains(p)) keys.add(p);
        // Отмеченные — сверху, чтобы сразу было видно, что выбрано.
        List<String> ordered = new ArrayList<>();
        for (String p : keys) if (current.contains(p)) ordered.add(p);
        for (String p : keys) if (!current.contains(p)) ordered.add(p);
        String[] names = new String[ordered.size()];
        boolean[] checked = new boolean[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) {
            names[i] = pkgs.get(ordered.get(i));
            checked[i] = current.contains(ordered.get(i));
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Готово", (d, w) -> {
                    Set<String> chosen = new HashSet<>(current);
                    for (int i = 0; i < ordered.size(); i++) {
                        if (checked[i]) chosen.add(ordered.get(i)); else chosen.remove(ordered.get(i));
                    }
                    result.done(chosen, current);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void editDistracting() {
        editPackages("Отвлекающие (пауза 20 сек, не на рабочем столе)", prefs.distracting(), null,
                (chosen, before) -> {
                    prefs.setDistracting(chosen);
                    for (AppEntry a : allApps) {
                        if (chosen.contains(a.packageName()) && !before.contains(a.packageName())) {
                            removeEverywhere(a.key);
                        }
                    }
                    renderHome();
                    onQueryChanged();
                });
    }

    private void editVideoApps() {
        editPackages("Блокировать как видео и браузеры", prefs.videoApps(), null, (chosen, before) -> {
            if (Guard.videoActive(prefs)) {
                Set<String> removed = new HashSet<>(before);
                removed.removeAll(chosen);
                if (!removed.isEmpty()) {
                    chosen.addAll(removed);
                    toast("Пока блок включён, убирать из списка нельзя — только добавлять");
                }
            }
            prefs.setVideoApps(chosen);
            renderHome();
            onQueryChanged();
        });
    }

    private void editNightAllowed() {
        if (Guard.nightActive(prefs)) {
            toast("Ночью менять нельзя. До 9:00.");
            return;
        }
        Set<String> system = new HashSet<>();
        for (AppEntry a : allApps) if (Guard.isSystemNightApp(a.packageName())) system.add(a.packageName());
        editPackages("Разрешено ночью (Библия и т.п.)", prefs.nightAllowed(), system, (chosen, before) -> {
            prefs.setNightAllowed(chosen);
            renderHome();
            onQueryChanged();
        });
    }

    private int countPackages(Set<String> pkgs) {
        Set<String> installed = new HashSet<>();
        for (AppEntry a : allApps) installed.add(a.packageName());
        int n = 0;
        for (String p : pkgs) if (installed.contains(p)) n++;
        return n;
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

    // ------------------------------------------------------------------ widgets

    private void renderWidgets() {
        widgetsBox.removeAllViews();
        List<Integer> ids = prefs.widgets();
        List<Integer> alive = new ArrayList<>();
        for (int id : ids) {
            AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(id);
            if (info == null) continue; // провайдер удалён
            alive.add(id);
            AppWidgetHostView v = widgetHost.createView(this, id, info);
            v.setOnLongClickListener(view -> { confirmRemoveWidget(id, info); return true; });
            int h = Math.max(info.minHeight, dp(48));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, h);
            lp.topMargin = dp(12);
            widgetsBox.addView(v, lp);
        }
        if (alive.size() != ids.size()) prefs.setWidgets(alive);
    }

    private void pickWidget() {
        if (prefs.widgets().size() >= MAX_WIDGETS) {
            toast("Максимум " + MAX_WIDGETS + " виджета");
            return;
        }
        List<AppWidgetProviderInfo> providers = new ArrayList<>();
        for (AppWidgetProviderInfo p : widgetManager.getInstalledProviders()) {
            if ((p.widgetCategory & AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0) providers.add(p);
        }
        if (providers.isEmpty()) {
            toast("Нет доступных виджетов");
            return;
        }
        Map<AppWidgetProviderInfo, String> labels = new HashMap<>();
        for (AppWidgetProviderInfo p : providers) {
            String app;
            try {
                app = getPackageManager().getApplicationLabel(getPackageManager()
                        .getApplicationInfo(p.provider.getPackageName(), 0)).toString();
            } catch (Exception e) {
                app = p.provider.getPackageName();
            }
            labels.put(p, app + ": " + p.loadLabel(getPackageManager()));
        }
        Collator collator = Collator.getInstance(Locale.getDefault());
        providers.sort((a, b) -> collator.compare(labels.get(a), labels.get(b)));
        String[] names = new String[providers.size()];
        for (int i = 0; i < names.length; i++) names[i] = labels.get(providers.get(i));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Виджет")
                .setItems(names, (d, which) -> bindWidget(providers.get(which)))
                .show();
    }

    @SuppressWarnings("deprecation")
    private void bindWidget(AppWidgetProviderInfo info) {
        int id = widgetHost.allocateAppWidgetId();
        if (widgetManager.bindAppWidgetIdIfAllowed(id, info.getProfile(), info.provider, null)) {
            configureOrAdd(id);
            return;
        }
        pendingWidgetId = id;
        Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.getProfile());
        try {
            startActivityForResult(i, REQ_BIND);
        } catch (ActivityNotFoundException e) {
            widgetHost.deleteAppWidgetId(id);
            pendingWidgetId = -1;
            toast("Система не разрешила добавить виджет");
        }
    }

    private void configureOrAdd(int id) {
        AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(id);
        if (info != null && info.configure != null) {
            pendingWidgetId = id;
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG, null);
                return;
            } catch (Exception ignored) {
                // Не открылась настройка — добавляем как есть.
            }
        }
        addWidget(id);
    }

    private void addWidget(int id) {
        List<Integer> ids = prefs.widgets();
        if (!ids.contains(id)) ids.add(id);
        prefs.setWidgets(ids);
        pendingWidgetId = -1;
        renderWidgets();
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        int id = pendingWidgetId;
        if (requestCode == REQ_BIND) {
            if (resultCode == RESULT_OK && id != -1) configureOrAdd(id);
            else dropPendingWidget();
        } else if (requestCode == REQ_CONFIG) {
            if (resultCode == RESULT_OK && id != -1) addWidget(id);
            else dropPendingWidget();
        }
    }

    private void dropPendingWidget() {
        if (pendingWidgetId != -1) widgetHost.deleteAppWidgetId(pendingWidgetId);
        pendingWidgetId = -1;
    }

    private void confirmRemoveWidget(int id, AppWidgetProviderInfo info) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(info.loadLabel(getPackageManager()))
                .setItems(new String[]{"Убрать виджет"}, (d, w) -> removeWidget(id))
                .show();
    }

    private void removeWidgetDialog() {
        List<Integer> ids = prefs.widgets();
        String[] names = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(ids.get(i));
            names[i] = info == null ? "Виджет" : info.loadLabel(getPackageManager());
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Убрать виджет")
                .setItems(names, (d, which) -> removeWidget(ids.get(which)))
                .show();
    }

    private void removeWidget(int id) {
        List<Integer> ids = prefs.widgets();
        ids.remove(Integer.valueOf(id));
        prefs.setWidgets(ids);
        widgetHost.deleteAppWidgetId(id);
        renderWidgets();
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
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_DEFAULT);
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

    /** Каждую минуту: наступила ли ночь/утро, не пора ли снять блок видео. */
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateGuards(false); }
    };

    // ------------------------------------------------------------------ helpers

    /** Простое меню: пункты и действия. */
    private final class Menu {
        private final List<String> items = new ArrayList<>();
        private final List<Runnable> actions = new ArrayList<>();

        void add(String title, Runnable action) {
            items.add(title);
            actions.add(action);
        }

        void show(CharSequence title) {
            new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(title)
                    .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run())
                    .show();
        }
    }

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
            LinearLayout row = convertView instanceof LinearLayout ? (LinearLayout) convertView : null;
            ImageView iv;
            TextView tv;
            if (row == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(4), dp(7), dp(4), dp(7));
                iv = new ImageView(MainActivity.this);
                row.addView(iv, new LinearLayout.LayoutParams(dp(42), dp(42)));
                tv = new TextView(MainActivity.this);
                style(tv, 19, TEXT);
                tv.setShadowLayer(8f, 0f, 1f, 0xAA000000);
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.leftMargin = dp(16);
                row.addView(tv, lp);
            } else {
                iv = (ImageView) row.getChildAt(0);
                tv = (TextView) row.getChildAt(1);
            }
            AppEntry a = filtered.get(position);
            bindIcon(iv, a);
            boolean blocked = Guard.blockReason(prefs, a.packageName()) != null;
            tv.setText(Guard.isDistracting(prefs, a.packageName()) ? a.label + "  ⏳" : a.label);
            float alpha = prefs.hidden().contains(a.key) ? 0.5f : 1f;
            row.setAlpha(blocked ? 0.35f : alpha);
            return row;
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

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
