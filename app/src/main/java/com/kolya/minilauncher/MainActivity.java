package com.kolya.minilauncher;

import android.app.Activity;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Главный экран «Фокуса»: плитки с картинками и экраны настроек. */
public class MainActivity extends Activity {
    private static final int REQ_VPN = 11, REQ_ADMIN = 12;

    private Prefs prefs;
    private FrameLayout host;
    private final ArrayDeque<Runnable> stack = new ArrayDeque<>();
    private Runnable current;
    private List<AppEntry> apps = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Insets insets = Insets.NONE;

    /** Новый ключ, который ещё не подтверждён кодом. */
    private String pendingSecret;
    private String unlockScope = "all";
    private AppEntry unlockApp;
    private int unlockMinutes = 15;

    private final Runnable homePage = this::home;
    private final Runnable videoPage = this::videoPage;
    private final Runnable appsPage = this::appsPage;
    private final Runnable nightPage = this::nightPage;
    private final Runnable grayPage = this::grayPage;
    private final Runnable keyPage = this::keyPage;
    private final Runnable unlockPage = this::unlockPage;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            if (current == homePage || current == unlockPage) render();
            handler.postDelayed(this, 20_000);
        }
    };

    // ================================================================== жизненный цикл

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        getWindow().setDecorFitsSystemWindows(false);
        host = new FrameLayout(this);
        host.setBackgroundColor(Ui.BG);
        host.setOnApplyWindowInsetsListener((v, wi) -> {
            insets = wi.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            applyInsets();
            return WindowInsets.CONSUMED;
        });
        setContentView(host);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
        }
        Lists.refresh(this);
        apps = AppRepository.load(this);
        preset();
        current = homePage;
        if ("unlock".equals(getIntent().getStringExtra("page"))) {
            stack.push(homePage);
            current = unlockPage;
        }
        render();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if ("unlock".equals(intent.getStringExtra("page")) && current != unlockPage) open(unlockPage);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Net.ensureAccessibility(this);
        Net.enforcePrivateDns(this);
        Guard.enforceGrayscale(this, prefs);
        FilterVpn.start(this);
        apps = AppRepository.load(this);
        render();
        // фильтр мог ещё не успеть запуститься — обновим экран чуть позже
        handler.postDelayed(() -> { if (current == homePage) render(); }, 1500);
        handler.postDelayed(refresher, 20_000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacksAndMessages(null);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        goBack();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VPN && res == RESULT_OK) FilterVpn.start(this);
        handler.postDelayed(this::render, 800);
    }

    /** Первый запуск: Библия доступна ночью, мессенджеры — с паузой 20 секунд. */
    private void preset() {
        if (prefs.presetDone()) return;
        Set<String> night = prefs.nightAllowed();
        for (AppEntry a : apps) {
            String l = a.label.toLowerCase(Locale.ROOT), p = a.pkg.toLowerCase(Locale.ROOT);
            if (l.contains("библи") || l.contains("bible") || l.contains("biblia") || l.contains("біблі")
                    || p.contains("bible") || p.contains("biblia")) {
                night.add(a.pkg);
            }
            if (Lists.DISTRACTING.contains(a.pkg) && "allow".equals(prefs.rule(a.pkg))) {
                prefs.setRule(a.pkg, "pause:20");
            }
        }
        prefs.setNightAllowed(night);
        prefs.setPresetDone();
    }

    // ================================================================== навигация

    private void open(Runnable page) {
        stack.push(current);
        current = page;
        render();
    }

    private void goBack() {
        if (stack.isEmpty()) {
            finish();
        } else {
            current = stack.pop();
            render();
        }
    }

    private void render() {
        if (current != null) current.run();
    }

    private void applyInsets() {
        if (host.getChildCount() == 0) return;
        View v = host.getChildAt(0);
        v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    /** Новая страница: прокрутка + колонка. С заголовком и стрелкой «назад», если title не null. */
    private LinearLayout page(String title) {
        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        sv.setFillViewport(true);
        LinearLayout col = Ui.col(this);
        col.setPadding(dp(18), dp(6), dp(18), dp(36));
        sv.addView(col, new FrameLayout.LayoutParams(MATCH, WRAP));
        host.removeAllViews();
        host.addView(sv, new FrameLayout.LayoutParams(MATCH, MATCH));
        applyInsets();
        if (title != null) {
            LinearLayout bar = Ui.row(this);
            bar.setPadding(0, dp(10), 0, dp(10));
            TextView back = Ui.text(this, "←", 26, Ui.INK, false);
            back.setGravity(Gravity.CENTER);
            Ui.clickable(back, 22, this::goBack);
            bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
            TextView t = Ui.text(this, title, 22, Ui.INK, true);
            t.setPadding(dp(6), 0, 0, 0);
            bar.addView(t);
            col.addView(bar);
        }
        return col;
    }

    /** Большая карточка с картинкой сверху страницы. */
    private void hero(LinearLayout col, int ill, int softColor, String title, String text) {
        LinearLayout card = Ui.col(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(20), dp(22), dp(20), dp(22));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{softColor, 0xFFFFFFFF});
        g.setCornerRadius(dp(28));
        card.setBackground(g);
        card.setElevation(dp(1));
        ImageView art = Ui.image(this, ill, 132);
        card.addView(art);
        TextView t = Ui.text(this, title, 22, Ui.INK, true);
        t.setGravity(Gravity.CENTER);
        card.addView(t, Ui.lp(this, WRAP, WRAP, 12));
        if (text != null) {
            TextView s = Ui.text(this, text, 15, Ui.SUB, false);
            s.setGravity(Gravity.CENTER);
            card.addView(s, Ui.lp(this, WRAP, WRAP, 6));
        }
        col.addView(card, Ui.lp(this, MATCH, WRAP, 6));
        art.setAlpha(0f);
        art.setTranslationY(dp(10));
        art.animate().alpha(1f).translationY(0).setDuration(300).start();
    }

    private LinearLayout section(LinearLayout col, String title) {
        if (title != null) {
            TextView h = Ui.text(this, title, 14, Ui.SUB, true);
            h.setPadding(dp(6), 0, 0, 0);
            col.addView(h, Ui.lp(this, WRAP, WRAP, 20));
        }
        LinearLayout card = Ui.card(this);
        col.addView(card, Ui.lp(this, MATCH, WRAP, title != null ? 8 : 14));
        return card;
    }

    private void divider(LinearLayout card) {
        View d = new View(this);
        d.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MATCH, 1);
        p.topMargin = dp(12);
        p.bottomMargin = dp(12);
        card.addView(d, p);
    }

    /** Строка с переключателем. Нажатие — onClick (сам решает, нужен ли код). */
    private void toggleRow(LinearLayout card, String title, String sub, boolean on, Runnable onClick) {
        LinearLayout row = Ui.row(this);
        LinearLayout txt = Ui.col(this);
        txt.addView(Ui.text(this, title, 17, Ui.INK, true));
        if (sub != null) txt.addView(Ui.text(this, sub, 14, Ui.SUB, false), Ui.lp(this, WRAP, WRAP, 2));
        row.addView(txt, new LinearLayout.LayoutParams(0, WRAP, 1));
        Switch sw = new Switch(this);
        sw.setChecked(on);
        sw.setClickable(false);
        sw.setFocusable(false);
        row.addView(sw);
        row.setPadding(0, dp(4), 0, dp(4));
        Ui.clickable(row, 12, onClick);
        card.addView(row);
    }

    private void statusRow(LinearLayout card, String title, boolean ok, String okText, String badText, Runnable fix) {
        LinearLayout row = Ui.row(this);
        View dot = new View(this);
        dot.setBackground(Ui.round(ok ? 0xFF22C55E : 0xFFF43F5E, dp(6)));
        row.addView(dot, new LinearLayout.LayoutParams(dp(12), dp(12)));
        LinearLayout txt = Ui.col(this);
        txt.setPadding(dp(12), 0, dp(8), 0);
        txt.addView(Ui.text(this, title, 16, Ui.INK, true));
        txt.addView(Ui.text(this, ok ? okText : badText, 14, ok ? Ui.SUB : Ui.RED, false));
        row.addView(txt, new LinearLayout.LayoutParams(0, WRAP, 1));
        if (!ok && fix != null) {
            TextView b = Ui.button(this, "Включить", Ui.BTN_SOFT);
            b.setPadding(dp(14), dp(8), dp(14), dp(8));
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            b.setOnClickListener(v -> fix.run());
            row.addView(b);
        }
        row.setPadding(0, dp(6), 0, dp(6));
        card.addView(row);
    }

    private TextView bullet(String s) {
        TextView t = Ui.text(this, "•  " + s, 15, Ui.INK, false);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ================================================================== код владельца ключа

    /** Выполняет действие только после правильного одноразового кода. Без ключа — сразу. */
    private void requireCode(String why, Runnable ok) {
        if (prefs.secret() == null) {
            ok.run();
            return;
        }
        LinearLayout c = Ui.col(this);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.addView(Ui.image(this, R.drawable.ill_key, 84));
        TextView t = Ui.text(this, "Нужен код", 22, Ui.INK, true);
        c.addView(t, Ui.lp(this, WRAP, WRAP, 10));
        TextView w = Ui.text(this, why + "\nПопроси 6 цифр из Google Authenticator у владельца ключа.", 15, Ui.SUB, false);
        w.setGravity(Gravity.CENTER);
        c.addView(w, Ui.lp(this, WRAP, WRAP, 4));
        EditText et = codeField();
        c.addView(et, Ui.lp(this, MATCH, WRAP, 16));
        TextView err = Ui.text(this, "", 14, Ui.RED, false);
        err.setGravity(Gravity.CENTER);
        err.setVisibility(View.GONE);
        c.addView(err, Ui.lp(this, MATCH, WRAP, 8));
        TextView btn = Ui.button(this, "Подтвердить", Ui.BTN_PRIMARY);
        c.addView(btn, Ui.lp(this, MATCH, WRAP, 14));
        Dialog d = Ui.sheet(this, c);
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        et.requestFocus();
        Runnable check = () -> {
            if (Totp.verify(prefs, prefs.secret(), et.getText().toString())) {
                d.dismiss();
                ok.run();
            } else {
                err.setText("Код неверный или уже использован. Попроси новый.");
                err.setVisibility(View.VISIBLE);
                et.setText("");
            }
        };
        btn.setOnClickListener(v -> check.run());
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c2) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c2) {}
            @Override public void afterTextChanged(Editable s) {
                if (s.length() == 6) check.run();
            }
        });
    }

    private EditText codeField() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        et.setLetterSpacing(0.35f);
        et.setGravity(Gravity.CENTER);
        et.setTypeface(Typeface.MONOSPACE);
        et.setTextColor(Ui.INK);
        et.setHint("••••••");
        et.setHintTextColor(0xFFCBD5E1);
        et.setBackground(Ui.round(0xFFFFFFFF, dp(18)));
        et.setPadding(dp(12), dp(14), dp(12), dp(14));
        return et;
    }

    // ================================================================== настройка шагов

    private boolean guardOn() { return GuardService.instance != null; }
    private boolean filterOn() { return FilterVpn.running; }
    private boolean keyOn() { return prefs.secret() != null; }

    private ComponentName admin() { return new ComponentName(this, AdminReceiver.class); }

    private boolean adminOn() {
        DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
        return dpm != null && dpm.isAdminActive(admin());
    }

    private void startFilter() {
        GuardService.setupBypassUntil = System.currentTimeMillis() + 90_000;
        Intent i = VpnService.prepare(this);
        if (i != null) {
            try {
                startActivityForResult(i, REQ_VPN);
            } catch (Exception e) {
                toast("Не получилось открыть запрос VPN");
            }
        } else {
            FilterVpn.start(this);
            handler.postDelayed(this::render, 800);
        }
    }

    private void requestAdmin() {
        GuardService.setupBypassUntil = System.currentTimeMillis() + 90_000;
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin())
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Пока включено, «Фокус» нельзя удалить без кода владельца ключа.");
        try {
            startActivityForResult(i, REQ_ADMIN);
        } catch (Exception e) {
            toast("Не получилось открыть настройку");
        }
    }

    private void openGuardSettings() {
        LinearLayout c = Ui.col(this);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.addView(Ui.image(this, R.drawable.ill_shield, 96));
        c.addView(Ui.text(this, "Включи защиту", 22, Ui.INK, true), Ui.lp(this, WRAP, WRAP, 10));
        TextView s = Ui.text(this,
                "1. Нажми «Открыть настройки».\n"
                        + "2. Найди «Фокус — защита» (иногда в «Установленные приложения»).\n"
                        + "3. Включи и подтверди.\n\n"
                        + "Если переключатель серый: Настройки → Приложения → Фокус → ⋮ (вверху справа) → "
                        + "«Разрешить ограниченные настройки», затем снова сюда.", 15, Ui.SUB, false);
        c.addView(s, Ui.lp(this, MATCH, WRAP, 8));
        TextView b = Ui.button(this, "Открыть настройки", Ui.BTN_PRIMARY);
        c.addView(b, Ui.lp(this, MATCH, WRAP, 16));
        Dialog d = Ui.sheet(this, c);
        b.setOnClickListener(v -> {
            d.dismiss();
            GuardService.setupBypassUntil = System.currentTimeMillis() + 120_000;
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception e) {
                toast("Открой: Настройки → Специальные возможности");
            }
        });
    }

    private int stepsDone() {
        int n = 0;
        if (keyOn()) n++;
        if (guardOn()) n++;
        if (filterOn()) n++;
        if (adminOn()) n++;
        return n;
    }

    // ================================================================== главная

    private void home() {
        LinearLayout col = page(null);

        LinearLayout head = Ui.row(this);
        head.setPadding(dp(4), dp(18), dp(4), dp(14));
        LinearLayout ht = Ui.col(this);
        ht.addView(Ui.text(this, "Фокус", 32, Ui.INK, true));
        String date = new SimpleDateFormat("EEEE, d MMMM", Locale.forLanguageTag("ru")).format(new Date());
        ht.addView(Ui.text(this, date.substring(0, 1).toUpperCase(Locale.ROOT) + date.substring(1), 15, Ui.SUB, false));
        head.addView(ht, new LinearLayout.LayoutParams(0, WRAP, 1));
        col.addView(head);

        // ---- главная карточка
        int done = stepsDone();
        boolean ok = done == 4;
        LinearLayout hero = Ui.row(this);
        hero.setPadding(dp(22), dp(22), dp(16), dp(22));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                ok ? new int[]{Ui.ACCENT, Ui.ACCENT2} : new int[]{0xFFF43F5E, 0xFFF59E0B});
        g.setCornerRadius(dp(28));
        hero.setBackground(g);
        hero.setElevation(dp(3));
        LinearLayout htxt = Ui.col(this);
        htxt.addView(Ui.text(this, ok ? "Защита включена" : "Настрой защиту", 24, 0xFFFFFFFF, true));
        htxt.addView(Ui.text(this, ok ? summary() : "Готово " + done + " из 4 шагов", 15, 0xE6FFFFFF, false),
                Ui.lp(this, WRAP, WRAP, 6));
        hero.addView(htxt, new LinearLayout.LayoutParams(0, WRAP, 1));
        hero.addView(Ui.image(this, ok ? R.drawable.ill_shield : R.drawable.ill_setup, 92));
        col.addView(hero);

        // ---- шаги настройки
        if (!ok) {
            LinearLayout card = section(col, "Шаги настройки");
            step(card, 1, "Ключ Google Authenticator", "Коды для отключения будут только у жены", keyOn(), () -> open(keyPage));
            divider(card);
            step(card, 2, "Защита приложений", "Специальные возможности → «Фокус — защита»", guardOn(), this::openGuardSettings);
            divider(card);
            step(card, 3, "Фильтр видео", "Закрывает видеосайты в любом браузере", filterOn(), this::startFilter);
            divider(card);
            step(card, 4, "Защита от удаления", "Без кода «Фокус» не удалить", adminOn(), this::requestAdmin);
        }

        // ---- временный доступ сейчас
        Map<String, Long> active = prefs.activeUnlocks();
        if (!active.isEmpty()) {
            LinearLayout card = section(col, "Сейчас открыто");
            activeUnlocksList(card, active);
        }

        // ---- плитки
        TextView h = Ui.text(this, "Ограничения", 14, Ui.SUB, true);
        h.setPadding(dp(6), 0, 0, 0);
        col.addView(h, Ui.lp(this, WRAP, WRAP, 20));

        boolean vf = Guard.videoFilterActive(prefs);
        String videoState = vf ? "Заблокировано" : prefs.videoBlock() ? "Открыто временно" : "Выключено";
        int videoColor = vf ? Ui.RED : prefs.videoBlock() ? Ui.AMBER : Ui.SUB;
        int rules = 0;
        for (String r : prefs.rules().values()) if (!"allow".equals(r)) rules++;
        String nightState = !prefs.nightMode() ? "Выключен" : Guard.nightNow(prefs) ? "Сейчас ночь" : Guard.nightRange(prefs);

        LinearLayout r1 = Ui.row(this);
        tile(r1, R.drawable.ill_video_off, 0xFFFFE4E6, "Видео", videoState, videoColor, () -> open(videoPage));
        tile(r1, R.drawable.ill_apps, 0xFFDCFCE7, "Приложения", rules == 0 ? "Нет правил" : rulesText(rules), Ui.GREEN, () -> open(appsPage));
        col.addView(r1, Ui.lp(this, MATCH, WRAP, 8));
        LinearLayout r2 = Ui.row(this);
        tile(r2, R.drawable.ill_moon, 0xFFE0E7FF, "Ночь", nightState, Ui.ACCENT, () -> open(nightPage));
        tile(r2, R.drawable.ill_gray, 0xFFF1F5F9, "Чёрно-белый", prefs.grayscale() ? "Включён" : "Выключен", Ui.INK, () -> open(grayPage));
        col.addView(r2, Ui.lp(this, MATCH, WRAP, 12));
        LinearLayout r3 = Ui.row(this);
        tile(r3, R.drawable.ill_unlock, 0xFFE0F2FE, "Доступ", active.isEmpty() ? "По коду" : "Открыто", 0xFF0369A1, () -> open(unlockPage));
        tile(r3, R.drawable.ill_key, 0xFFEDE9FE, "Ключ", keyOn() ? "Настроен" : "Не настроен", keyOn() ? Ui.GREEN : Ui.RED, () -> open(keyPage));
        col.addView(r3, Ui.lp(this, MATCH, WRAP, 12));

        TextView foot = Ui.text(this, "Ужесточить можно всегда. Ослабить — только по коду из Google Authenticator владельца ключа.",
                13, Ui.SUB, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(dp(16), 0, dp(16), 0);
        col.addView(foot, Ui.lp(this, MATCH, WRAP, 22));
    }

    private String rulesText(int n) {
        int m10 = n % 10, m100 = n % 100;
        String w = (m10 == 1 && m100 != 11) ? "правило" : (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) ? "правила" : "правил";
        return n + " " + w;
    }

    private String summary() {
        List<String> parts = new ArrayList<>();
        parts.add(Guard.videoFilterActive(prefs) ? "Видео закрыто" : "Видео открыто");
        if (prefs.nightMode()) parts.add("ночь " + Guard.nightRange(prefs));
        if (prefs.grayscale()) parts.add("ч/б экран");
        return String.join(" · ", parts);
    }

    private void step(LinearLayout card, int n, String title, String sub, boolean done, Runnable action) {
        LinearLayout row = Ui.row(this);
        TextView num = Ui.text(this, done ? "✓" : String.valueOf(n), 16, done ? 0xFFFFFFFF : Ui.ACCENT, true);
        num.setGravity(Gravity.CENTER);
        num.setBackground(Ui.round(done ? 0xFF22C55E : Ui.ACCENT_SOFT, dp(16)));
        row.addView(num, new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout txt = Ui.col(this);
        txt.setPadding(dp(12), 0, dp(8), 0);
        txt.addView(Ui.text(this, title, 16, done ? Ui.SUB : Ui.INK, true));
        txt.addView(Ui.text(this, sub, 13, Ui.SUB, false));
        row.addView(txt, new LinearLayout.LayoutParams(0, WRAP, 1));
        if (!done) {
            TextView b = Ui.button(this, "Сделать", Ui.BTN_PRIMARY);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            b.setPadding(dp(14), dp(8), dp(14), dp(8));
            b.setOnClickListener(v -> action.run());
            row.addView(b);
        }
        card.addView(row);
    }

    private void tile(LinearLayout row, int ill, int soft, String title, String state, int stateColor, Runnable click) {
        LinearLayout t = Ui.col(this);
        t.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{soft, 0xFFFFFFFF});
        g.setCornerRadius(dp(24));
        t.setBackground(g);
        t.setElevation(dp(1));
        t.addView(Ui.image(this, ill, 64));
        t.addView(Ui.text(this, title, 17, Ui.INK, true), Ui.lp(this, WRAP, WRAP, 10));
        TextView s = Ui.text(this, state, 14, stateColor, false);
        s.setSingleLine(true);
        t.addView(s, Ui.lp(this, WRAP, WRAP, 2));
        Ui.clickable(t, 24, click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, WRAP, 1);
        p.leftMargin = row.getChildCount() == 0 ? 0 : dp(6);
        p.rightMargin = row.getChildCount() == 0 ? dp(6) : 0;
        row.addView(t, p);
    }

    private void activeUnlocksList(LinearLayout card, Map<String, Long> active) {
        boolean first = true;
        for (Map.Entry<String, Long> e : active.entrySet()) {
            if (!first) divider(card);
            first = false;
            String scope = e.getKey();
            String name = "all".equals(scope) ? "Всё" : "video".equals(scope) ? "Видео"
                    : scope.startsWith("app:") ? AppRepository.label(this, scope.substring(4)) : scope;
            LinearLayout row = Ui.row(this);
            LinearLayout txt = Ui.col(this);
            txt.addView(Ui.text(this, name, 16, Ui.INK, true));
            txt.addView(Ui.text(this, "до " + Guard.clock(e.getValue()), 14, Ui.AMBER, false));
            row.addView(txt, new LinearLayout.LayoutParams(0, WRAP, 1));
            TextView b = Ui.button(this, "Закрыть", Ui.BTN_SOFT);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            b.setPadding(dp(14), dp(8), dp(14), dp(8));
            b.setOnClickListener(v -> {
                prefs.setUntil(scope, 0);
                render();
            });
            row.addView(b);
            card.addView(row);
        }
    }

    // ================================================================== видео

    private void videoPage() {
        LinearLayout col = page("Видео");
        boolean vf = Guard.videoFilterActive(prefs);
        hero(col, R.drawable.ill_video_off, 0xFFFFE4E6,
                vf ? "Видео заблокировано" : prefs.videoBlock() ? "Видео открыто временно" : "Видео не блокируется",
                "YouTube, TikTok, VK Видео, Rutube, Netflix, Кинопоиск, Twitch и другие — в любом браузере и приложении.");

        LinearLayout card = section(col, null);
        toggleRow(card, "Блокировать всё видео", "Включить — сразу. Выключить — по коду.", prefs.videoBlock(), () -> {
            if (prefs.videoBlock()) {
                requireCode("Выключить блок видео.", () -> {
                    prefs.setVideoBlock(false);
                    render();
                });
            } else {
                prefs.setVideoBlock(true);
                render();
            }
        });
        divider(card);
        toggleRow(card, "Видео внутри Notion", "Детские песни, вставленные в страницы Notion, играют. Ссылки из Notion в YouTube — закрыты.",
                prefs.notionVideo(), () -> {
                    if (!prefs.notionVideo()) {
                        requireCode("Разрешить видео внутри Notion.", () -> {
                            prefs.setNotionVideo(true);
                            FilterVpn.restart(this);
                            render();
                        });
                    } else {
                        prefs.setNotionVideo(false);
                        FilterVpn.restart(this);
                        render();
                    }
                });

        LinearLayout st = section(col, "Как работает");
        statusRow(st, "Фильтр сети", filterOn(), "Видеосайты закрыты во всём телефоне", "Выключен — браузеры закрыты, пока не включишь", this::startFilter);
        divider(st);
        statusRow(st, "Проверка адресной строки", guardOn(), "Видео закрывается в любом браузере", "Нужна защита в «Специальных возможностях»", this::openGuardSettings);

        LinearLayout what = section(col, "Что закрыто");
        what.addView(bullet("Видеосайты в любом браузере: Chrome, Яндекс, Samsung, Firefox, Edge…"));
        what.addView(bullet("Приложения YouTube, TikTok, VK, Instagram, Rutube, Кинопоиск, видеоплееры"));
        what.addView(bullet("Браузеры со встроенным VPN (Opera, Tor, UC…) и VPN-приложения"));
        what.addView(bullet("Обходы: «защищённый DNS», зеркала YouTube, скачивалки видео"));
        what.addView(bullet("Telegram открыт: сами сообщения и видео в нём не блокируются"));

        List<AppEntry> found = new ArrayList<>();
        for (AppEntry a : apps) if (Lists.isVideoApp(a.pkg) || Lists.isBypassApp(a.pkg)) found.add(a);
        if (!found.isEmpty()) {
            LinearLayout list = section(col, "Закрыто на этом телефоне");
            for (int i = 0; i < found.size(); i++) {
                if (i > 0) divider(list);
                AppEntry a = found.get(i);
                LinearLayout row = Ui.row(this);
                ImageView ic = new ImageView(this);
                ic.setImageDrawable(AppRepository.icon(this, a));
                row.addView(ic, new LinearLayout.LayoutParams(dp(36), dp(36)));
                TextView l = Ui.text(this, a.label, 16, Ui.INK, false);
                l.setPadding(dp(12), 0, 0, 0);
                row.addView(l, new LinearLayout.LayoutParams(0, WRAP, 1));
                row.addView(Ui.chip(this, vf ? "закрыто" : "открыто", vf ? Ui.RED : Ui.AMBER, vf ? Ui.RED_SOFT : Ui.AMBER_SOFT));
                list.addView(row);
            }
        }
    }

    // ================================================================== приложения

    private void appsPage() {
        LinearLayout col = page("Приложения");
        hero(col, R.drawable.ill_apps, 0xFFDCFCE7, "Правила для приложений",
                "Пауза перед открытием, лимит в день, расписание или полный запрет — для любого приложения.");
        EditText search = new EditText(this);
        search.setHint("Поиск приложения");
        search.setSingleLine(true);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        search.setBackground(Ui.round(0xFFFFFFFF, dp(18)));
        search.setPadding(dp(16), dp(14), dp(16), dp(14));
        col.addView(search, Ui.lp(this, MATCH, WRAP, 14));
        LinearLayout list = Ui.card(this);
        list.setPadding(dp(12), dp(8), dp(12), dp(8));
        col.addView(list, Ui.lp(this, MATCH, WRAP, 12));
        Runnable fill = () -> fillApps(list, search.getText().toString());
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { fill.run(); }
        });
        fill.run();
    }

    private void fillApps(LinearLayout list, String query) {
        list.removeAllViews();
        String q = query.trim().toLowerCase(Locale.ROOT);
        // сначала приложения с правилами, потом остальные
        List<AppEntry> withRule = new ArrayList<>(), rest = new ArrayList<>();
        for (AppEntry a : apps) {
            if (!q.isEmpty() && !a.label.toLowerCase(Locale.ROOT).contains(q)) continue;
            boolean has = !"allow".equals(prefs.rule(a.pkg)) || Lists.isVideoApp(a.pkg) || Lists.isBypassApp(a.pkg);
            (has ? withRule : rest).add(a);
        }
        withRule.addAll(rest);
        for (AppEntry a : withRule) {
            LinearLayout row = Ui.row(this);
            row.setPadding(dp(6), dp(10), dp(6), dp(10));
            ImageView ic = new ImageView(this);
            ic.setImageDrawable(AppRepository.icon(this, a));
            row.addView(ic, new LinearLayout.LayoutParams(dp(42), dp(42)));
            TextView l = Ui.text(this, a.label, 16, Ui.INK, false);
            l.setPadding(dp(14), 0, dp(8), 0);
            l.setSingleLine(true);
            row.addView(l, new LinearLayout.LayoutParams(0, WRAP, 1));
            if (prefs.videoBlock() && (Lists.isVideoApp(a.pkg) || Lists.isBypassApp(a.pkg))) {
                row.addView(Ui.chip(this, "Видео — закрыто", Ui.RED, Ui.RED_SOFT));
            } else {
                Rule r = Rule.parse(prefs.rule(a.pkg));
                row.addView(Ui.chip(this, r.type == Rule.ALLOW ? "—" : r.describe(), r.color(), r.soft()));
            }
            Ui.clickable(row, 16, () -> ruleSheet(a));
            list.addView(row);
        }
        if (withRule.isEmpty()) {
            TextView none = Ui.text(this, "Ничего не найдено", 15, Ui.SUB, false);
            none.setPadding(dp(8), dp(12), dp(8), dp(12));
            list.addView(none);
        }
    }

    private void ruleSheet(AppEntry a) {
        Rule old = Rule.parse(prefs.rule(a.pkg));
        final int[] type = {old.type};
        final int[] pause = {old.type == Rule.PAUSE ? old.a : 20};
        final int[] limit = {old.type == Rule.LIMIT ? old.a : 15};
        final int[] from = {old.type == Rule.SCHED ? old.a : 9 * 60};
        final int[] to = {old.type == Rule.SCHED ? old.b : 18 * 60};

        LinearLayout c = Ui.col(this);
        LinearLayout head = Ui.row(this);
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(AppRepository.icon(this, a));
        head.addView(ic, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout ht = Ui.col(this);
        ht.setPadding(dp(14), 0, 0, 0);
        ht.addView(Ui.text(this, a.label, 21, Ui.INK, true));
        long used = prefs.usedToday(a.pkg) / 60_000;
        ht.addView(Ui.text(this, "Сегодня: " + used + " мин", 14, Ui.SUB, false));
        head.addView(ht, new LinearLayout.LayoutParams(0, WRAP, 1));
        c.addView(head);

        if (prefs.videoBlock() && (Lists.isVideoApp(a.pkg) || Lists.isBypassApp(a.pkg))) {
            TextView v = Ui.text(this, "Это видеоприложение: пока включён блок видео, оно закрыто при любом правиле.", 14, Ui.RED, false);
            v.setBackground(Ui.round(Ui.RED_SOFT, dp(14)));
            v.setPadding(dp(12), dp(10), dp(12), dp(10));
            c.addView(v, Ui.lp(this, MATCH, WRAP, 12));
        }

        LinearLayout opts = Ui.col(this);
        c.addView(opts, Ui.lp(this, MATCH, WRAP, 12));
        TextView save = Ui.button(this, "Сохранить", Ui.BTN_PRIMARY);
        c.addView(save, Ui.lp(this, MATCH, WRAP, 14));

        Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            opts.removeAllViews();
            int[][] items = {{Rule.ALLOW}, {Rule.PAUSE}, {Rule.LIMIT}, {Rule.SCHED}, {Rule.BLOCK}};
            for (int[] it : items) {
                int t = it[0];
                Rule sample = new Rule(t, t == Rule.PAUSE ? pause[0] : limit[0], 0);
                String title, sub;
                switch (t) {
                    case Rule.PAUSE: title = "Пауза перед открытием"; sub = "Экран ожидания, потом можно открыть"; break;
                    case Rule.LIMIT: title = "Лимит в день"; sub = "Потом закрыто до завтра"; break;
                    case Rule.SCHED: title = "Расписание"; sub = "Закрыто в выбранные часы"; break;
                    case Rule.BLOCK: title = "Заблокировать"; sub = "Не открывается совсем"; break;
                    default: title = "Без ограничений"; sub = "Открывается как обычно"; break;
                }
                boolean sel = type[0] == t;
                LinearLayout o = Ui.col(this);
                o.setPadding(dp(14), dp(12), dp(14), dp(12));
                GradientDrawable bg = Ui.round(sel ? sample.soft() : 0xFFFFFFFF, dp(18));
                bg.setStroke(dp(sel ? 2 : 1), sel ? sample.color() : Ui.LINE);
                o.setBackground(bg);
                LinearLayout r = Ui.row(this);
                r.addView(Ui.image(this, sample.illustration(), 40));
                LinearLayout tx = Ui.col(this);
                tx.setPadding(dp(12), 0, 0, 0);
                tx.addView(Ui.text(this, title, 16, Ui.INK, true));
                tx.addView(Ui.text(this, sub, 13, Ui.SUB, false));
                r.addView(tx, new LinearLayout.LayoutParams(0, WRAP, 1));
                o.addView(r);
                if (sel && t == Rule.PAUSE) o.addView(stepper(pause, 5, 5, 300, " сек", draw[0]), Ui.lp(this, MATCH, WRAP, 10));
                if (sel && t == Rule.LIMIT) o.addView(stepper(limit, 5, 5, 600, " мин", draw[0]), Ui.lp(this, MATCH, WRAP, 10));
                if (sel && t == Rule.SCHED) {
                    LinearLayout times = Ui.row(this);
                    TextView f = Ui.button(this, "с " + Guard.hhmm(from[0]), Ui.BTN_WHITE);
                    TextView e = Ui.button(this, "до " + Guard.hhmm(to[0]), Ui.BTN_WHITE);
                    f.setOnClickListener(v -> pickTime(from[0], m -> { from[0] = m; draw[0].run(); }));
                    e.setOnClickListener(v -> pickTime(to[0], m -> { to[0] = m; draw[0].run(); }));
                    times.addView(f, Ui.weight(this, 0));
                    View gap = new View(this);
                    times.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));
                    times.addView(e, Ui.weight(this, 0));
                    o.addView(times, Ui.lp(this, MATCH, WRAP, 10));
                }
                o.setOnClickListener(v -> {
                    if (type[0] != t) {
                        type[0] = t;
                        draw[0].run();
                    }
                });
                opts.addView(o, Ui.lp(this, MATCH, WRAP, 8));
            }
        };
        draw[0].run();
        Dialog d = Ui.sheet(this, c);
        save.setOnClickListener(v -> {
            int t = type[0];
            Rule now = new Rule(t, t == Rule.PAUSE ? pause[0] : t == Rule.LIMIT ? limit[0] : t == Rule.SCHED ? from[0] : 0,
                    t == Rule.SCHED ? to[0] : 0);
            Runnable apply = () -> {
                prefs.setRule(a.pkg, now.encode());
                d.dismiss();
                render();
            };
            if (Rule.notLooser(old, now)) apply.run();
            else requireCode("Ослабить правило для «" + a.label + "».", apply);
        });
    }

    private View stepper(int[] value, int step, int min, int max, String unit, Runnable changed) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER);
        TextView minus = Ui.button(this, "−", Ui.BTN_WHITE);
        TextView plus = Ui.button(this, "+", Ui.BTN_WHITE);
        TextView v = Ui.text(this, value[0] + unit, 20, Ui.INK, true);
        v.setGravity(Gravity.CENTER);
        minus.setOnClickListener(x -> {
            value[0] = Math.max(min, value[0] - step);
            changed.run();
        });
        plus.setOnClickListener(x -> {
            value[0] = Math.min(max, value[0] + step);
            changed.run();
        });
        r.addView(minus, new LinearLayout.LayoutParams(dp(56), WRAP));
        r.addView(v, new LinearLayout.LayoutParams(0, WRAP, 1));
        r.addView(plus, new LinearLayout.LayoutParams(dp(56), WRAP));
        return r;
    }

    private void pickTime(int minutes, Consumer<Integer> done) {
        new TimePickerDialog(this, (tp, h, m) -> done.accept(h * 60 + m), minutes / 60, minutes % 60, true).show();
    }

    private void appPicker(String title, Consumer<AppEntry> picked) {
        LinearLayout c = Ui.col(this);
        c.addView(Ui.text(this, title, 20, Ui.INK, true));
        EditText search = new EditText(this);
        search.setHint("Поиск");
        search.setSingleLine(true);
        search.setBackground(Ui.round(0xFFFFFFFF, dp(16)));
        search.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.addView(search, Ui.lp(this, MATCH, WRAP, 10));
        LinearLayout list = Ui.col(this);
        c.addView(list, Ui.lp(this, MATCH, WRAP, 8));
        Dialog d = Ui.sheet(this, c);
        Runnable fill = () -> {
            list.removeAllViews();
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            int shown = 0;
            for (AppEntry a : apps) {
                if (!q.isEmpty() && !a.label.toLowerCase(Locale.ROOT).contains(q)) continue;
                if (shown++ > 80) break;
                LinearLayout row = Ui.row(this);
                row.setPadding(dp(6), dp(8), dp(6), dp(8));
                ImageView ic = new ImageView(this);
                ic.setImageDrawable(AppRepository.icon(this, a));
                row.addView(ic, new LinearLayout.LayoutParams(dp(36), dp(36)));
                TextView l = Ui.text(this, a.label, 16, Ui.INK, false);
                l.setPadding(dp(12), 0, 0, 0);
                row.addView(l);
                Ui.clickable(row, 14, () -> {
                    d.dismiss();
                    picked.accept(a);
                });
                list.addView(row);
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c2) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c2) {}
            @Override public void afterTextChanged(Editable s) { fill.run(); }
        });
        fill.run();
    }

    // ================================================================== ночь

    private void nightPage() {
        LinearLayout col = page("Ночной режим");
        boolean now = Guard.nightNow(prefs);
        hero(col, R.drawable.ill_moon, 0xFFE0E7FF,
                prefs.nightMode() ? "Ночь " + Guard.nightRange(prefs) : "Ночной режим выключен",
                now ? "Сейчас ночь: открыты только звонки, будильник и разрешённые приложения."
                        : "Ночью открыты только звонки, будильник, Библия и то, что ты разрешишь.");

        LinearLayout card = section(col, null);
        toggleRow(card, "Ночной режим", "Включить — сразу. Выключить — по коду.", prefs.nightMode(), () -> {
            if (prefs.nightMode()) {
                requireCode("Выключить ночной режим.", () -> {
                    prefs.setNightMode(false);
                    render();
                });
            } else {
                prefs.setNightMode(true);
                render();
            }
        });
        divider(card);
        LinearLayout hours = Ui.row(this);
        LinearLayout ht = Ui.col(this);
        ht.addView(Ui.text(this, "Часы", 17, Ui.INK, true));
        ht.addView(Ui.text(this, "С " + Guard.hhmm(prefs.nightStart()) + " до " + Guard.hhmm(prefs.nightEnd()), 14, Ui.SUB, false));
        hours.addView(ht, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView change = Ui.button(this, "Изменить", Ui.BTN_SOFT);
        change.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        change.setPadding(dp(14), dp(8), dp(14), dp(8));
        change.setOnClickListener(v -> requireCode("Изменить часы ночного режима.", () ->
                pickTime(prefs.nightStart(), s -> {
                    toast("Теперь — когда ночь заканчивается");
                    pickTime(prefs.nightEnd(), e -> {
                        prefs.setNightHours(s, e);
                        render();
                    });
                })));
        hours.addView(change);
        card.addView(hours);

        LinearLayout allowed = section(col, "Доступно ночью");
        allowed.addView(bullet("Звонки, контакты и будильник — всегда"));
        for (String pkg : prefs.nightAllowed()) {
            divider(allowed);
            LinearLayout row = Ui.row(this);
            ImageView ic = new ImageView(this);
            ic.setImageDrawable(AppRepository.icon(this, pkg));
            row.addView(ic, new LinearLayout.LayoutParams(dp(36), dp(36)));
            TextView l = Ui.text(this, AppRepository.label(this, pkg), 16, Ui.INK, false);
            l.setPadding(dp(12), 0, 0, 0);
            row.addView(l, new LinearLayout.LayoutParams(0, WRAP, 1));
            TextView rm = Ui.button(this, "Убрать", Ui.BTN_DANGER);
            rm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            rm.setPadding(dp(14), dp(8), dp(14), dp(8));
            rm.setOnClickListener(v -> {
                Set<String> s = prefs.nightAllowed();
                s.remove(pkg);
                prefs.setNightAllowed(s);
                render();
            });
            row.addView(rm);
            allowed.addView(row);
        }
        TextView add = Ui.button(this, "Разрешить приложение ночью", Ui.BTN_SOFT);
        col.addView(add, Ui.lp(this, MATCH, WRAP, 12));
        add.setOnClickListener(v -> appPicker("Что разрешить ночью?", a ->
                requireCode("Разрешить «" + a.label + "» ночью.", () -> {
                    Set<String> s = prefs.nightAllowed();
                    s.add(a.pkg);
                    prefs.setNightAllowed(s);
                    render();
                })));
    }

    // ================================================================== чёрно-белый

    private void grayPage() {
        LinearLayout col = page("Чёрно-белый экран");
        hero(col, R.drawable.ill_gray, 0xFFF1F5F9, prefs.grayscale() ? "Весь телефон чёрно-белый" : "Цвета включены",
                "Серый экран скучный — и в телефон меньше тянет залипать.");
        LinearLayout card = section(col, null);
        toggleRow(card, "Чёрно-белый экран", "Включить — сразу. Выключить — по коду.", prefs.grayscale(), () -> {
            if (prefs.grayscale()) {
                requireCode("Вернуть цвета на экран.", () -> {
                    prefs.setGrayscale(false);
                    Guard.setPhoneGrayscale(this, false);
                    render();
                });
            } else {
                prefs.setGrayscale(true);
                Guard.enforceGrayscale(this, prefs);
                render();
            }
        });
        LinearLayout st = section(col, "Управление системой");
        boolean grant = Guard.canControlSystem(this);
        statusRow(st, "Разрешение с компьютера", grant, "Выдано — «Фокус» сам держит ч/б экран и защиту",
                "Не выдано — выдаётся один раз через компьютер", null);
        if (!grant) {
            TextView how = Ui.text(this,
                    "Это разрешение позволяет «Фокусу» самому включать чёрно-белый экран и возвращать защиту, если её выключат. "
                            + "Подключи телефон к компьютеру кабелем, включи «Отладку по USB» и скажи Claude — он выполнит команду:",
                    14, Ui.SUB, false);
            st.addView(how, Ui.lp(this, MATCH, WRAP, 10));
            TextView cmd = Ui.text(this, "adb shell pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS",
                    13, Ui.INK, false);
            cmd.setTypeface(Typeface.MONOSPACE);
            cmd.setTextIsSelectable(true);
            cmd.setBackground(Ui.round(Ui.GRAY_SOFT, dp(12)));
            cmd.setPadding(dp(12), dp(10), dp(12), dp(10));
            st.addView(cmd, Ui.lp(this, MATCH, WRAP, 8));
        }
    }

    // ================================================================== ключ

    private void keyPage() {
        LinearLayout col = page("Ключ");
        if (pendingSecret != null || !keyOn()) {
            keySetup(col);
            return;
        }
        hero(col, R.drawable.ill_key, 0xFFEDE9FE, "Ключ настроен",
                "Ослабить ограничения можно только с кодом из Google Authenticator владельца ключа. Каждый код работает один раз.");
        LinearLayout card = section(col, null);
        toggleRow(card, "Защита настроек", "Закрывает экраны, где «Фокус» можно отключить или удалить", prefs.protect(), () -> {
            if (prefs.protect()) {
                requireCode("Выключить защиту настроек.", () -> {
                    prefs.setProtect(false);
                    render();
                });
            } else {
                prefs.setProtect(true);
                render();
            }
        });
        TextView rekey = Ui.button(this, "Сменить ключ", Ui.BTN_SOFT);
        col.addView(rekey, Ui.lp(this, MATCH, WRAP, 16));
        rekey.setOnClickListener(v -> requireCode("Сменить ключ.", () -> {
            pendingSecret = Totp.newSecret();
            render();
        }));
        TextView del = Ui.button(this, "Удалить «Фокус»", Ui.BTN_DANGER);
        col.addView(del, Ui.lp(this, MATCH, WRAP, 10));
        del.setOnClickListener(v -> requireCode("Удалить «Фокус» с телефона.", this::uninstall));
    }

    private void keySetup(LinearLayout col) {
        hero(col, R.drawable.ill_key, 0xFFEDE9FE, pendingSecret == null ? "Ключ для кодов" : "Новый ключ",
                "Этот шаг делает владелец ключа (жена). Ты в это время не смотришь на экран.");
        if (pendingSecret == null) {
            LinearLayout card = section(col, null);
            card.addView(bullet("Владелец ключа ставит на свой телефон Google Authenticator"));
            card.addView(bullet("Сканирует QR-код с этого экрана"));
            card.addView(bullet("Дальше всё, что ослабляет защиту, — только по его коду"));
            TextView show = Ui.button(this, "Показать ключ", Ui.BTN_PRIMARY);
            col.addView(show, Ui.lp(this, MATCH, WRAP, 16));
            show.setOnClickListener(v -> {
                pendingSecret = Totp.newSecret();
                render();
            });
            return;
        }
        LinearLayout card = section(col, "1. Google Authenticator → «+» → «Сканировать QR-код»");
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        Bitmap qr = qr("otpauth://totp/Fokus:" + Uri.encode(Build.MODEL) + "?secret=" + pendingSecret
                + "&issuer=Fokus&digits=6&period=30", 640);
        if (qr != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(qr);
            card.addView(iv, new LinearLayout.LayoutParams(dp(240), dp(240)));
        }
        TextView alt = Ui.text(this, "Или «Ввести ключ настройки», тип «По времени»:", 14, Ui.SUB, false);
        alt.setGravity(Gravity.CENTER);
        card.addView(alt, Ui.lp(this, WRAP, WRAP, 10));
        TextView key = Ui.text(this, Totp.pretty(pendingSecret), 18, Ui.INK, true);
        key.setTypeface(Typeface.MONOSPACE);
        key.setGravity(Gravity.CENTER);
        key.setTextIsSelectable(true);
        card.addView(key, Ui.lp(this, WRAP, WRAP, 6));

        LinearLayout check = section(col, "2. Введи 6 цифр из приложения");
        EditText et = codeField();
        check.addView(et);
        TextView err = Ui.text(this, "", 14, Ui.RED, false);
        err.setVisibility(View.GONE);
        check.addView(err, Ui.lp(this, MATCH, WRAP, 8));
        TextView ok = Ui.button(this, "Готово", Ui.BTN_PRIMARY);
        col.addView(ok, Ui.lp(this, MATCH, WRAP, 16));
        ok.setOnClickListener(v -> {
            if (Totp.verify(prefs, pendingSecret, et.getText().toString())) {
                prefs.setSecret(pendingSecret);
                pendingSecret = null;
                toast("Ключ сохранён");
                goBack();
            } else {
                err.setText("Код не подошёл. Проверь, что время на обоих телефонах автоматическое, и введи новый код.");
                err.setVisibility(View.VISIBLE);
            }
        });
        TextView cancel = Ui.button(this, "Отмена", Ui.BTN_SOFT);
        col.addView(cancel, Ui.lp(this, MATCH, WRAP, 10));
        cancel.setOnClickListener(v -> {
            pendingSecret = null;
            goBack();
        });
    }

    private Bitmap qr(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? 0xFF0F172A : 0xFFFFFFFF;
            }
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            b.setPixels(px, 0, w, 0, 0, w, h);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private void uninstall() {
        prefs.setUntil("all", System.currentTimeMillis() + 10 * 60_000L);
        try {
            DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
            if (dpm != null && dpm.isAdminActive(admin())) dpm.removeActiveAdmin(admin());
        } catch (Exception ignored) {}
        handler.postDelayed(() -> {
            try {
                startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                toast("Удали «Фокус» в настройках приложений — 10 минут защита выключена");
            }
        }, 1200);
    }

    // ================================================================== временный доступ

    private void unlockPage() {
        LinearLayout col = page("Временный доступ");
        hero(col, R.drawable.ill_unlock, 0xFFE0F2FE, "Открыть на время",
                "Код владельца ключа открывает доступ на выбранное время. Потом всё закроется само.");

        Map<String, Long> active = prefs.activeUnlocks();
        if (!active.isEmpty()) {
            LinearLayout card = section(col, "Сейчас открыто");
            activeUnlocksList(card, active);
        }

        LinearLayout what = section(col, "Что открыть");
        choice(what, "Только видео", "YouTube и видеосайты; ночь и правила остаются", "video".equals(unlockScope), () -> {
            unlockScope = "video";
            render();
        });
        choice(what, "Одно приложение", unlockApp == null ? "Выбрать приложение" : unlockApp.label, "app".equals(unlockScope), () ->
                appPicker("Какое приложение открыть?", a -> {
                    unlockScope = "app";
                    unlockApp = a;
                    render();
                }));
        choice(what, "Всё", "Все ограничения, включая ночь и видео", "all".equals(unlockScope), () -> {
            unlockScope = "all";
            render();
        });

        LinearLayout howLong = section(col, "На сколько");
        LinearLayout chips = Ui.row(this);
        int[] mins = {15, 30, 60, 120};
        String[] names = {"15 мин", "30 мин", "1 час", "2 часа"};
        for (int i = 0; i < mins.length; i++) {
            int m = mins[i];
            boolean sel = unlockMinutes == m;
            TextView t = Ui.text(this, names[i], 15, sel ? 0xFFFFFFFF : Ui.INK, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(12), 0, dp(12));
            t.setBackground(Ui.round(sel ? 0xFF0284C7 : Ui.GRAY_SOFT, dp(14)));
            Ui.clickable(t, 14, () -> {
                unlockMinutes = m;
                render();
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, WRAP, 1);
            if (i > 0) p.leftMargin = dp(8);
            chips.addView(t, p);
        }
        howLong.addView(chips);

        TextView go = Ui.button(this, "Открыть по коду", Ui.BTN_PRIMARY);
        col.addView(go, Ui.lp(this, MATCH, WRAP, 18));
        go.setOnClickListener(v -> {
            if ("app".equals(unlockScope) && unlockApp == null) {
                toast("Сначала выбери приложение");
                return;
            }
            String scope = "app".equals(unlockScope) ? "app:" + unlockApp.pkg : unlockScope;
            String what2 = "all".equals(scope) ? "всё" : "video".equals(scope) ? "видео" : "«" + unlockApp.label + "»";
            requireCode("Открыть " + what2 + " на " + unlockMinutes + " мин.", () -> {
                prefs.setUntil(scope, System.currentTimeMillis() + unlockMinutes * 60_000L);
                if ("all".equals(scope) && prefs.grayscale()) Guard.setPhoneGrayscale(this, false);
                toast("Открыто до " + Guard.clock(prefs.until(scope)));
                render();
            });
        });
    }

    private void choice(LinearLayout card, String title, String sub, boolean sel, Runnable click) {
        LinearLayout row = Ui.row(this);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bg = Ui.round(sel ? 0xFFE0F2FE : 0xFFFFFFFF, dp(16));
        bg.setStroke(dp(sel ? 2 : 1), sel ? 0xFF0284C7 : Ui.LINE);
        row.setBackground(bg);
        View dot = new View(this);
        GradientDrawable d = Ui.round(sel ? 0xFF0284C7 : 0xFFFFFFFF, dp(9));
        d.setStroke(dp(2), sel ? 0xFF0284C7 : 0xFFCBD5E1);
        dot.setBackground(d);
        row.addView(dot, new LinearLayout.LayoutParams(dp(18), dp(18)));
        LinearLayout txt = Ui.col(this);
        txt.setPadding(dp(12), 0, 0, 0);
        txt.addView(Ui.text(this, title, 16, Ui.INK, true));
        txt.addView(Ui.text(this, sub, 13, Ui.SUB, false));
        row.addView(txt, new LinearLayout.LayoutParams(0, WRAP, 1));
        Ui.clickable(row, 16, click);
        card.addView(row, Ui.lp(this, MATCH, WRAP, card.getChildCount() == 0 ? 0 : 8));
    }
}
