package com.kolya.minilauncher;

import android.app.Activity;
import android.app.Dialog;
import android.app.Notification;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** «Сообщения»: общие входящие Telegram, WhatsApp, Viber и VK. */
public class InboxActivity extends Activity {
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    private FrameLayout host;
    private Insets insets = Insets.NONE;
    /** Открытый чат или null — список. */
    private String openId;
    /** Фильтр списка: 0 — все, 1..4 — мессенджер. */
    private int filter;
    private String draft = "";
    /** Поле ввода было в фокусе — после обновления ленты вернуть клавиатуру. */
    private boolean typing;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
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
        if (b != null) openId = b.getString("open");
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (openId != null) out.putString("open", openId);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Inbox.setListener(this::onInboxChanged);
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Inbox.setListener(null);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        goBack();
    }

    private void goBack() {
        if (openId != null) {
            openId = null;
            draft = "";
            typing = false;
            render();
        } else {
            finish();
        }
    }

    private void onInboxChanged() {
        // В открытом чате не перерисовываем поле ввода, если человек печатает — только ленту.
        render();
    }

    private void render() {
        if (!Inbox.enabled(this)) setupPage();
        else if (openId != null && Inbox.find(this, openId) != null) chatPage();
        else {
            openId = null;
            listPage();
        }
    }

    private void applyInsets() {
        if (host.getChildCount() == 0) return;
        host.getChildAt(0).setPadding(insets.left, insets.top, insets.right, insets.bottom);
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ================================================================== включение

    private void setupPage() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout col = Ui.col(this);
        col.setPadding(dp(18), dp(6), dp(18), dp(36));
        sv.addView(col);
        setRoot(sv);
        col.addView(header("Сообщения", null));

        LinearLayout card = Ui.col(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(20), dp(24), dp(20), dp(24));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFFE0F2FE, 0xFFFFFFFF});
        g.setCornerRadius(dp(28));
        card.setBackground(g);
        card.addView(Ui.image(this, R.drawable.ill_chat, 132));
        TextView t = Ui.text(this, "Все мессенджеры в одном месте", 22, Ui.INK, true);
        t.setGravity(Gravity.CENTER);
        card.addView(t, Ui.lp(this, WRAP, WRAP, 12));
        TextView s = Ui.text(this,
                "Telegram, WhatsApp, Viber и VK. Новые сообщения собираются здесь, отвечать можно прямо отсюда — "
                        + "без лент, сторис и видео. Переписка хранится только на телефоне.", 15, Ui.SUB, false);
        s.setGravity(Gravity.CENTER);
        card.addView(s, Ui.lp(this, WRAP, WRAP, 6));
        col.addView(card, Ui.lp(this, MATCH, WRAP, 6));

        LinearLayout how = Ui.card(this);
        how.addView(Ui.text(this, "Как включить", 17, Ui.INK, true));
        TextView steps = Ui.text(this,
                "1. Нажми «Открыть настройки».\n"
                        + "2. Включи доступ к уведомлениям для «Фокус» и подтверди.\n\n"
                        + "Если переключатель серый: Настройки → Приложения → Фокус → ⋮ (вверху справа) → "
                        + "«Разрешить ограниченные настройки», затем снова сюда.\n\n"
                        + "Важно: в самих мессенджерах уведомления должны быть включены.", 15, Ui.SUB, false);
        how.addView(steps, Ui.lp(this, MATCH, WRAP, 8));
        TextView b = Ui.button(this, "Открыть настройки", Ui.BTN_PRIMARY);
        b.setOnClickListener(v -> openListenerSettings());
        how.addView(b, Ui.lp(this, MATCH, WRAP, 16));
        col.addView(how, Ui.lp(this, MATCH, WRAP, 14));
    }

    private void openListenerSettings() {
        // Пока доступ настраивается, защита не закрывает этот экран настроек.
        GuardService.setupBypassUntil = System.currentTimeMillis() + 120_000;
        ComponentName cn = new ComponentName(this, InboxService.class);
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString()));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Exception e2) {
                toast("Открой: Настройки → Уведомления → Доступ к уведомлениям");
            }
        }
    }

    // ================================================================== список чатов

    private void listPage() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout col = Ui.col(this);
        col.setPadding(dp(18), dp(6), dp(18), dp(36));
        sv.addView(col);
        setRoot(sv);

        int unread = Inbox.unreadTotal(this);
        col.addView(header("Сообщения", unread > 0 ? unreadText(unread) : "Всё прочитано"));

        // фильтр по мессенджеру
        LinearLayout chips = Ui.row(this);
        String[] names = {"Все", "Telegram", "WhatsApp", "Viber", "VK"};
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            boolean sel = filter == i;
            TextView c = Ui.chip(this, names[i], sel ? 0xFFFFFFFF : Ui.INK, sel ? (i == 0 ? Ui.ACCENT : Inbox.COLORS[i]) : Ui.CARD);
            c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            c.setPadding(dp(12), dp(7), dp(12), dp(7));
            Ui.clickable(c, 14, () -> { filter = k; render(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(WRAP, WRAP);
            p.rightMargin = dp(6);
            chips.addView(c, p);
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips);
        col.addView(hs, Ui.lp(this, MATCH, WRAP, 4));

        List<Inbox.Chat> list = Inbox.sorted(this);
        LinearLayout card = Ui.card(this);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        int shown = 0;
        for (Inbox.Chat ch : list) {
            if (filter != 0 && Inbox.kind(ch.pkg) != filter) continue;
            if (ch.msgs.isEmpty()) continue;
            if (shown++ > 0) {
                View d = new View(this);
                d.setBackgroundColor(Ui.LINE);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MATCH, 1);
                p.leftMargin = dp(70);
                card.addView(d, p);
            }
            card.addView(chatRow(ch));
        }
        if (shown == 0) {
            LinearLayout empty = Ui.col(this);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.setPadding(dp(16), dp(24), dp(16), dp(24));
            empty.addView(Ui.image(this, R.drawable.ill_chat, 96));
            TextView t = Ui.text(this, "Пока пусто", 18, Ui.INK, true);
            t.setGravity(Gravity.CENTER);
            empty.addView(t, Ui.lp(this, WRAP, WRAP, 10));
            TextView s = Ui.text(this, "Здесь появятся новые сообщения — как только они придут в уведомлениях.",
                    14, Ui.SUB, false);
            s.setGravity(Gravity.CENTER);
            empty.addView(s, Ui.lp(this, WRAP, WRAP, 4));
            card.addView(empty);
        }
        col.addView(card, Ui.lp(this, MATCH, WRAP, 12));

        TextView foot = Ui.text(this,
                "Видны только сообщения, пришедшие в уведомлениях. Старую переписку смотри в самом мессенджере.",
                13, Ui.SUB, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(dp(16), 0, dp(16), 0);
        col.addView(foot, Ui.lp(this, MATCH, WRAP, 18));
    }

    private View chatRow(Inbox.Chat ch) {
        LinearLayout row = Ui.row(this);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.addView(avatar(ch, 48), new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout txt = Ui.col(this);
        txt.setPadding(dp(12), 0, dp(8), 0);
        LinearLayout top = Ui.row(this);
        TextView name = Ui.text(this, ch.title, 16, Ui.INK, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView time = Ui.text(this, when(ch.last), 12, ch.unread > 0 ? Inbox.appColor(ch.pkg) : Ui.SUB, ch.unread > 0);
        top.addView(time);
        txt.addView(top);

        LinearLayout bottom = Ui.row(this);
        Inbox.Msg last = ch.msgs.get(ch.msgs.size() - 1);
        String prev = last.me ? "Вы: " + last.text : (ch.group && last.sender != null ? last.sender + ": " + last.text : last.text);
        TextView p = Ui.text(this, prev.replace('\n', ' '), 14, Ui.SUB, false);
        p.setSingleLine(true);
        p.setEllipsize(TextUtils.TruncateAt.END);
        bottom.addView(p, new LinearLayout.LayoutParams(0, WRAP, 1));
        if (ch.unread > 0) {
            TextView badge = Ui.text(this, String.valueOf(ch.unread), 12, 0xFFFFFFFF, true);
            badge.setGravity(Gravity.CENTER);
            badge.setMinWidth(dp(22));
            badge.setPadding(dp(6), dp(1), dp(6), dp(1));
            badge.setBackground(Ui.round(Inbox.appColor(ch.pkg), dp(11)));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(WRAP, dp(22));
            bp.leftMargin = dp(6);
            bottom.addView(badge, bp);
        }
        txt.addView(bottom, Ui.lp(this, MATCH, WRAP, 2));
        row.addView(txt, new LinearLayout.LayoutParams(0, WRAP, 1));

        Ui.clickable(row, 16, () -> { openId = ch.id; draft = ""; render(); });
        row.setOnLongClickListener(v -> { chatMenu(ch); return true; });
        return row;
    }

    private void chatMenu(Inbox.Chat ch) {
        LinearLayout c = Ui.col(this);
        c.addView(Ui.text(this, ch.title, 20, Ui.INK, true));
        c.addView(Ui.text(this, Inbox.appName(ch.pkg), 14, Ui.SUB, false), Ui.lp(this, WRAP, WRAP, 2));
        TextView del = Ui.button(this, "Убрать чат из списка", Ui.BTN_DANGER);
        c.addView(del, Ui.lp(this, MATCH, WRAP, 16));
        TextView note = Ui.text(this, "В самом мессенджере ничего не удалится.", 13, Ui.SUB, false);
        note.setGravity(Gravity.CENTER);
        c.addView(note, Ui.lp(this, MATCH, WRAP, 8));
        Dialog d = Ui.sheet(this, c);
        del.setOnClickListener(v -> { d.dismiss(); Inbox.delete(this, ch); render(); });
    }

    // ================================================================== чат

    private void chatPage() {
        Inbox.Chat ch = Inbox.find(this, openId);
        if (ch == null) return;
        boolean wasTyping = typing;
        Inbox.markRead(this, ch);
        Inbox.Live lv = Inbox.live.get(ch.id);

        LinearLayout root = Ui.col(this);
        setRoot(root);

        // шапка
        LinearLayout bar = Ui.row(this);
        bar.setPadding(dp(8), dp(8), dp(12), dp(8));
        bar.setBackgroundColor(Ui.CARD);
        bar.setElevation(dp(2));
        TextView back = Ui.text(this, "←", 26, Ui.INK, false);
        back.setGravity(Gravity.CENTER);
        Ui.clickable(back, 22, this::goBack);
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        bar.addView(avatar(ch, 40), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout tt = Ui.col(this);
        tt.setPadding(dp(10), 0, 0, 0);
        TextView name = Ui.text(this, ch.title, 17, Ui.INK, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        tt.addView(name);
        tt.addView(Ui.text(this, Inbox.appName(ch.pkg), 13, Inbox.appColor(ch.pkg), false));
        bar.addView(tt, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView more = Ui.text(this, "⋮", 24, Ui.INK, false);
        more.setGravity(Gravity.CENTER);
        Ui.clickable(more, 22, () -> chatActions(ch));
        bar.addView(more, new LinearLayout.LayoutParams(dp(40), dp(44)));
        root.addView(bar, new LinearLayout.LayoutParams(MATCH, WRAP));

        // лента
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout feed = Ui.col(this);
        feed.setPadding(dp(12), dp(10), dp(12), dp(10));
        sv.addView(feed);
        String lastDay = null;
        for (Inbox.Msg m : ch.msgs) {
            String day = day(m.ts);
            if (!day.equals(lastDay)) {
                lastDay = day;
                TextView dl = Ui.chip(this, day, Ui.SUB, Ui.GRAY_SOFT);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(WRAP, WRAP);
                p.gravity = Gravity.CENTER_HORIZONTAL;
                p.topMargin = dp(8);
                p.bottomMargin = dp(4);
                feed.addView(dl, p);
            }
            feed.addView(bubble(ch, m));
        }
        root.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1));
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));

        // ввод
        LinearLayout input = Ui.row(this);
        input.setPadding(dp(10), dp(8), dp(10), dp(10));
        input.setBackgroundColor(Ui.CARD);
        if (lv != null && lv.reply != null) {
            EditText et = new EditText(this);
            et.setHint("Сообщение");
            et.setText(draft);
            et.setSelection(draft.length());
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            et.setTextColor(Ui.INK);
            et.setHintTextColor(Ui.SUB);
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            et.setMaxLines(5);
            et.setPadding(dp(16), dp(10), dp(16), dp(10));
            et.setBackground(Ui.round(Ui.GRAY_SOFT, dp(22)));
            et.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) { draft = s.toString(); }
            });
            et.setOnFocusChangeListener((v, f) -> typing = f);
            input.addView(et, new LinearLayout.LayoutParams(0, WRAP, 1));
            if (wasTyping) {
                typing = true;
                et.requestFocus();
                et.post(() -> {
                    android.view.inputmethod.InputMethodManager im =
                            getSystemService(android.view.inputmethod.InputMethodManager.class);
                    if (im != null) im.showSoftInput(et, 0);
                });
            }
            TextView send = Ui.text(this, "➤", 20, 0xFFFFFFFF, true);
            send.setGravity(Gravity.CENTER);
            send.setBackground(Ui.round(Inbox.appColor(ch.pkg), dp(22)));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(44), dp(44));
            sp.leftMargin = dp(8);
            input.addView(send, sp);
            send.setOnClickListener(v -> sendReply(ch, et.getText().toString()));
        } else {
            LinearLayout box = Ui.col(this);
            box.addView(Ui.text(this, "Ответить отсюда пока нельзя", 15, Ui.INK, true));
            box.addView(Ui.text(this, "Кнопка ответа появится, когда придёт новое сообщение из этого чата.",
                    13, Ui.SUB, false));
            input.addView(box, new LinearLayout.LayoutParams(0, WRAP, 1));
        }
        root.addView(input, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private View bubble(Inbox.Chat ch, Inbox.Msg m) {
        LinearLayout b = Ui.col(this);
        b.setPadding(dp(12), dp(8), dp(12), dp(6));
        int color = Inbox.appColor(ch.pkg);
        b.setBackground(Ui.round(m.me ? soft(color) : Ui.CARD, dp(18)));
        b.setElevation(dp(0.5f));
        if (!m.me && ch.group && m.sender != null) {
            b.addView(Ui.text(this, m.sender, 13, color, true));
        }
        TextView t = Ui.text(this, m.text, 16, Ui.INK, false);
        t.setTextIsSelectable(true);
        b.addView(t);
        TextView time = Ui.text(this, clock(m.ts), 11, Ui.SUB, false);
        time.setGravity(Gravity.END);
        b.addView(time, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(WRAP, WRAP);
        p.gravity = m.me ? Gravity.END : Gravity.START;
        p.topMargin = dp(4);
        if (m.me) p.leftMargin = dp(48); else p.rightMargin = dp(48);
        b.setLayoutParams(p);
        return b;
    }

    private void chatActions(Inbox.Chat ch) {
        Inbox.Live lv = Inbox.live.get(ch.id);
        LinearLayout c = Ui.col(this);
        c.addView(Ui.text(this, ch.title, 20, Ui.INK, true));
        TextView open = Ui.button(this, "Открыть в " + Inbox.appName(ch.pkg), Ui.BTN_SOFT);
        c.addView(open, Ui.lp(this, MATCH, WRAP, 16));
        TextView read = null;
        if (lv != null && lv.read != null) {
            read = Ui.button(this, "Отметить прочитанным в " + Inbox.appName(ch.pkg), Ui.BTN_SOFT);
            c.addView(read, Ui.lp(this, MATCH, WRAP, 10));
        }
        TextView del = Ui.button(this, "Убрать чат из списка", Ui.BTN_DANGER);
        c.addView(del, Ui.lp(this, MATCH, WRAP, 10));
        Dialog d = Ui.sheet(this, c);
        open.setOnClickListener(v -> { d.dismiss(); openInApp(ch); });
        if (read != null) {
            read.setOnClickListener(v -> {
                d.dismiss();
                try {
                    lv.read.actionIntent.send();
                    toast("Отмечено прочитанным");
                } catch (Exception e) {
                    toast("Не получилось — открой чат в мессенджере");
                }
            });
        }
        del.setOnClickListener(v -> { d.dismiss(); openId = null; Inbox.delete(this, ch); render(); });
    }

    private void openInApp(Inbox.Chat ch) {
        Inbox.Live lv = Inbox.live.get(ch.id);
        if (lv != null && lv.open != null) {
            try {
                lv.open.send();
                return;
            } catch (Exception ignored) {}
        }
        Intent li = getPackageManager().getLaunchIntentForPackage(ch.pkg);
        if (li != null) {
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(li); } catch (Exception ignored) {}
        } else {
            toast(Inbox.appName(ch.pkg) + " не установлен");
        }
    }

    private void sendReply(Inbox.Chat ch, String text) {
        text = text.trim();
        if (text.isEmpty()) return;
        Inbox.Live lv = Inbox.live.get(ch.id);
        if (lv == null || lv.reply == null) {
            toast("Ответ недоступен — открой чат в мессенджере");
            return;
        }
        Notification.Action a = lv.reply;
        RemoteInput[] inputs = a.getRemoteInputs();
        Intent fill = new Intent();
        Bundle results = new Bundle();
        for (RemoteInput ri : inputs) results.putCharSequence(ri.getResultKey(), text);
        RemoteInput.addResultsToIntent(inputs, fill, results);
        RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT);
        try {
            a.actionIntent.send(this, 0, fill);
        } catch (Exception e) {
            toast("Не отправилось. Открой чат в " + Inbox.appName(ch.pkg));
            return;
        }
        Inbox.Msg m = new Inbox.Msg();
        m.text = text;
        m.ts = System.currentTimeMillis();
        m.me = true;
        Inbox.add(ch, m);
        draft = "";
        Inbox.save(this);
        render();
    }

    // ================================================================== общее

    private void setRoot(View v) {
        host.removeAllViews();
        host.addView(v, new FrameLayout.LayoutParams(MATCH, MATCH));
        applyInsets();
    }

    private View header(String title, String sub) {
        LinearLayout bar = Ui.row(this);
        bar.setPadding(0, dp(10), 0, dp(10));
        TextView back = Ui.text(this, "←", 26, Ui.INK, false);
        back.setGravity(Gravity.CENTER);
        Ui.clickable(back, 22, this::goBack);
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout tt = Ui.col(this);
        tt.setPadding(dp(6), 0, 0, 0);
        tt.addView(Ui.text(this, title, 24, Ui.INK, true));
        if (sub != null) tt.addView(Ui.text(this, sub, 14, Ui.SUB, false));
        bar.addView(tt);
        return bar;
    }

    /** Кружок с первой буквой имени и цветом мессенджера. */
    private View avatar(Inbox.Chat ch, int sizeDp) {
        FrameLayout f = new FrameLayout(this);
        String letter = ch.title == null || ch.title.isEmpty() ? "?" : ch.title.substring(0, ch.title.offsetByCodePoints(0, 1));
        TextView t = Ui.text(this, letter.toUpperCase(Locale.ROOT), sizeDp * 0.4f, 0xFFFFFFFF, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.round(Inbox.appColor(ch.pkg), dp(sizeDp / 2f)));
        f.addView(t, new FrameLayout.LayoutParams(MATCH, MATCH));
        return f;
    }

    private static int soft(int color) {
        // смешиваем цвет мессенджера с белым (≈18% цвета)
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        r = 255 - (255 - r) * 18 / 100;
        g = 255 - (255 - g) * 18 / 100;
        b = 255 - (255 - b) * 18 / 100;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static String clock(long ts) {
        return new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(ts));
    }

    private static boolean sameDay(long a, long b) {
        Calendar x = Calendar.getInstance(), y = Calendar.getInstance();
        x.setTimeInMillis(a);
        y.setTimeInMillis(b);
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR);
    }

    private static String when(long ts) {
        long now = System.currentTimeMillis();
        if (sameDay(ts, now)) return clock(ts);
        if (sameDay(ts, now - 86_400_000L)) return "вчера";
        return new SimpleDateFormat("d MMM", Locale.forLanguageTag("ru")).format(new Date(ts));
    }

    private static String day(long ts) {
        long now = System.currentTimeMillis();
        if (sameDay(ts, now)) return "Сегодня";
        if (sameDay(ts, now - 86_400_000L)) return "Вчера";
        return new SimpleDateFormat("d MMMM", Locale.forLanguageTag("ru")).format(new Date(ts));
    }

    static String unreadText(int n) {
        int m10 = n % 10, m100 = n % 100;
        String w = (m10 == 1 && m100 != 11) ? "непрочитанное" : "непрочитанных";
        return n + " " + w;
    }
}
