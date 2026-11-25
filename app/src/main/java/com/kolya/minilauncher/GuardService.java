package com.kolya.minilauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Защита (служба «Специальных возможностей»): видит, какое приложение открыто,
 * и закрывает его, если правило не разрешает; проверяет адресную строку браузеров;
 * не даёт отключить или удалить «Фокус» без кода.
 */
public class GuardService extends AccessibilityService {
    static volatile GuardService instance;
    /** Пока идёт настройка из «Фокуса» (включение VPN, администратора) — экраны настроек не закрываем. */
    static volatile long setupBypassUntil;

    /** Приложения, для которых пауза уже пройдена (до ухода из приложения). */
    private static final Map<String, Long> passes = new ConcurrentHashMap<>();

    private static final Pattern APP_WORD = Pattern.compile("(^|[^а-яёa-z])фокус([^а-яёa-z]|$)");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private String foreground;
    private long lastAccount;
    private long lastRefresh;
    private String lastShownPkg;
    private long lastShownAt;
    private long lastUrlScan;
    private long lastSettingsScan;

    static void grantPass(String pkg) {
        passes.put(pkg, System.currentTimeMillis());
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                periodic();
            } catch (Exception ignored) {}
            handler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onServiceConnected() {
        instance = this;
        prefs = new Prefs(this);
        Lists.refresh(this);
        lastRefresh = System.currentTimeMillis();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        handler.removeCallbacks(tick);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    // ------------------------------------------------------------ раз в 3 секунды

    private void periodic() {
        long now = System.currentTimeMillis();
        account(now);
        if (now - lastRefresh > 60_000) {
            Lists.refresh(this);
            lastRefresh = now;
        }
        Guard.enforceGrayscale(this, prefs);
        Net.enforcePrivateDns(this);
        if (!FilterVpn.running) FilterVpn.start(this);
        // Наступила ночь, кончился лимит или временный доступ — закрываем открытое приложение.
        if (foreground != null) evaluate(foreground);
    }

    /** Считает время, проведённое в открытом приложении (только при включённом экране). */
    private void account(long now) {
        if (foreground != null && lastAccount > 0) {
            long d = now - lastAccount;
            PowerManager pm = getSystemService(PowerManager.class);
            if (d > 0 && d < 60_000 && pm != null && pm.isInteractive()) prefs.addUsage(foreground, d);
        }
        lastAccount = now;
    }

    // ------------------------------------------------------------ события

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (prefs == null || e == null || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (pkg.equals(getPackageName())) return;
        int type = e.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (Lists.isSettingsLike(pkg)) scanSettings(pkg, true);
            if (Lists.isTransparent(pkg)) return;
            if (!pkg.equals(foreground)) {
                account(System.currentTimeMillis());
                foreground = pkg;
                for (String k : new ArrayList<>(passes.keySet())) if (!k.equals(pkg)) passes.remove(k);
                evaluate(pkg);
            }
            if (Lists.isBrowser(pkg)) scanUrl(pkg, true);
        } else if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (Lists.isBrowser(pkg)) scanUrl(pkg, false);
            else if (Lists.isSettingsLike(pkg)) scanSettings(pkg, false);
        }
    }

    private void evaluate(String pkg) {
        Guard.Verdict v = Guard.check(this, prefs, pkg, System.currentTimeMillis());
        if (v.kind == Guard.OK) return;
        if (v.kind == Guard.PAUSE && passes.containsKey(pkg)) return;
        show(pkg, v);
    }

    private void show(String pkg, Guard.Verdict v) {
        if (pkg.equals(BlockActivity.showingPkg)) return;
        long now = System.currentTimeMillis();
        if (pkg.equals(lastShownPkg) && now - lastShownAt < 1500) return;
        lastShownPkg = pkg;
        lastShownAt = now;
        if (v.kind == Guard.BLOCK) performGlobalAction(GLOBAL_ACTION_HOME);
        try {
            startActivity(BlockActivity.intent(this, pkg, v));
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ адресная строка браузера

    private void scanUrl(String pkg, boolean force) {
        if (!Guard.videoFilterActive(prefs)) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastUrlScan < 700) return;
        lastUrlScan = now;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !pkg.equals(root.getPackageName().toString())) return;
        if (findVideoUrl(root)) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            show(pkg, Guard.videoPageVerdict());
        }
    }

    private static boolean findVideoUrl(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty() && seen++ < 400) {
            AccessibilityNodeInfo n = queue.poll();
            CharSequence text = n.getText();
            if (text != null && text.length() > 3) {
                String id = n.getViewIdResourceName();
                String lid = id == null ? "" : id.toLowerCase(Locale.ROOT);
                if (n.isEditable() || lid.contains("url") || lid.contains("omnibox") || lid.contains("address")
                        || lid.contains("location") || lid.contains("search_box")) {
                    if (Lists.urlBlocked(text.toString())) return true;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo ch = n.getChild(i);
                if (ch != null) queue.add(ch);
            }
        }
        return false;
    }

    // ------------------------------------------------------------ защита от отключения и удаления

    private void scanSettings(String pkg, boolean force) {
        if (!prefs.protect() || prefs.secret() == null || Guard.temp(prefs, "all")) return;
        long now = System.currentTimeMillis();
        if (now < setupBypassUntil) return;
        if (!force && now - lastSettingsScan < 600) return;
        lastSettingsScan = now;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        String all = collectText(root);
        if (!APP_WORD.matcher(all).find()) return;
        boolean danger = Lists.isInstaller(pkg);
        if (!danger) for (String w : Lists.DANGER_WORDS) if (all.contains(w)) { danger = true; break; }
        if (!danger) return;
        performGlobalAction(GLOBAL_ACTION_BACK);
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_HOME), 150);
        show(pkg, Guard.protectVerdict());
    }

    private static String collectText(AccessibilityNodeInfo root) {
        StringBuilder sb = new StringBuilder();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty() && seen++ < 500) {
            AccessibilityNodeInfo n = queue.poll();
            if (n.getText() != null) sb.append(n.getText()).append('\n');
            if (n.getContentDescription() != null) sb.append(n.getContentDescription()).append('\n');
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo ch = n.getChild(i);
                if (ch != null) queue.add(ch);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
