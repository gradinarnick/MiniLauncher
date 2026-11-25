package com.kolya.minilauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

/** Экран-заглушка: пауза перед отвлекающим приложением или блокировка с картинкой. */
public class BlockActivity extends Activity {
    static volatile String showingPkg;

    private String pkg;
    private CountDownTimer timer;
    private boolean leaving;

    static Intent intent(Context c, String pkg, Guard.Verdict v) {
        return new Intent(c, BlockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("pkg", pkg)
                .putExtra("kind", v.kind)
                .putExtra("title", v.title)
                .putExtra("text", v.text)
                .putExtra("ill", v.illustration)
                .putExtra("sec", v.seconds)
                .putExtra("dark", v.dark);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setDecorFitsSystemWindows(false);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goHome);
        }
        build(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        build(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showingPkg = pkg;
    }

    @Override
    protected void onStop() {
        super.onStop();
        showingPkg = null;
        if (timer != null) timer.cancel();
        if (!leaving) finish();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        goHome();
    }

    private void build(Intent i) {
        if (timer != null) timer.cancel();
        pkg = i.getStringExtra("pkg");
        showingPkg = pkg;
        int kind = i.getIntExtra("kind", Guard.BLOCK);
        String title = i.getStringExtra("title");
        String text = i.getStringExtra("text");
        int ill = i.getIntExtra("ill", R.drawable.ill_block);
        int sec = i.getIntExtra("sec", 20);
        boolean dark = i.getBooleanExtra("dark", false);
        String appLabel = pkg == null ? "" : AppRepository.label(this, pkg);

        int ink = dark ? 0xFFFFFFFF : Ui.INK;
        int sub = dark ? 0xFFC7D2FE : Ui.SUB;

        FrameLayout root = new FrameLayout(this);
        int[] colors = dark ? new int[]{0xFF1E1B4B, 0xFF312E81}
                : kind == Guard.PAUSE ? new int[]{0xFFFEF3C7, 0xFFFFFBEB, 0xFFFFFFFF}
                : new int[]{0xFFFFE4E6, 0xFFFFF1F2, 0xFFFFFFFF};
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors));
        getWindow().setStatusBarColor(0);
        getWindow().setNavigationBarColor(0);
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(dark ? 0 : light, light);
        }

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout col = Ui.col(this);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = Ui.dp(this, 28);
        col.setPadding(p, p, p, p);
        sv.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener((v, wi) -> {
            Insets in = wi.getInsets(WindowInsets.Type.systemBars());
            col.setPadding(p, p + in.top, p, p + in.bottom);
            return WindowInsets.CONSUMED;
        });

        View top = new View(this);
        col.addView(top, new LinearLayout.LayoutParams(1, 0, 1));

        // Значок и название приложения
        if (pkg != null) {
            LinearLayout app = Ui.row(this);
            int ph = Ui.dp(this, 6), pw = Ui.dp(this, 12);
            app.setPadding(pw, ph, pw, ph);
            app.setBackground(Ui.round(dark ? 0x33FFFFFF : 0xB3FFFFFF, Ui.dp(this, 20)));
            ImageView ic = new ImageView(this);
            ic.setImageDrawable(AppRepository.icon(this, pkg));
            app.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
            TextView al = Ui.text(this, appLabel, 15, ink, true);
            al.setPadding(Ui.dp(this, 8), 0, 0, 0);
            app.addView(al);
            col.addView(app, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        ImageView art = Ui.image(this, ill, 168);
        LinearLayout.LayoutParams ap = (LinearLayout.LayoutParams) art.getLayoutParams();
        ap.topMargin = Ui.dp(this, 24);
        col.addView(art, ap);

        TextView t = Ui.text(this, title == null ? "" : title, 28, ink, true);
        t.setGravity(Gravity.CENTER);
        col.addView(t, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 20));

        String body = kind == Guard.PAUSE
                ? "Сделай вдох.\nТебе правда нужно открыть «" + appLabel + "» сейчас?"
                : text;
        TextView tx = Ui.text(this, body == null ? "" : body, 17, sub, false);
        tx.setGravity(Gravity.CENTER);
        col.addView(tx, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));

        TextView primary;
        TextView secondary;
        if (kind == Guard.PAUSE) {
            Ring ring = new Ring(this);
            col.addView(ring, Ui.lp(this, Ui.dp(this, 132), Ui.dp(this, 132), 24));
            primary = Ui.button(this, "Не сейчас", Ui.BTN_PRIMARY);
            primary.setOnClickListener(v -> goHome());
            secondary = Ui.button(this, "Открыть «" + appLabel + "»", Ui.BTN_WHITE);
            secondary.setEnabled(false);
            secondary.setAlpha(0.4f);
            final TextView open = secondary;
            open.setOnClickListener(v -> openApp());
            long total = sec * 1000L;
            ring.set(1f, String.valueOf(sec));
            timer = new CountDownTimer(total, 100) {
                @Override
                public void onTick(long left) {
                    ring.set(left / (float) total, String.valueOf((left + 999) / 1000));
                }

                @Override
                public void onFinish() {
                    ring.set(0f, "✓");
                    open.setEnabled(true);
                    open.animate().alpha(1f).setDuration(250).start();
                }
            }.start();
        } else {
            primary = Ui.button(this, "На главный экран", Ui.BTN_PRIMARY);
            primary.setOnClickListener(v -> goHome());
            secondary = Ui.button(this, "Временный доступ по коду", dark ? Ui.BTN_WHITE : Ui.BTN_SOFT);
            secondary.setOnClickListener(v -> {
                leaving = true;
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("page", "unlock"));
                finish();
            });
        }
        View bottom = new View(this);
        col.addView(bottom, new LinearLayout.LayoutParams(1, 0, 1));
        col.addView(primary, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 24));
        col.addView(secondary, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));

        setContentView(root);
        art.setScaleX(0.85f);
        art.setScaleY(0.85f);
        art.setAlpha(0f);
        art.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(350).start();
    }

    private void openApp() {
        leaving = true;
        if (pkg != null) {
            GuardService.grantPass(pkg);
            Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
            if (li != null) {
                li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(li);
                } catch (Exception ignored) {}
            }
        }
        finish();
    }

    private void goHome() {
        leaving = true;
        Intent h = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(h);
        } catch (Exception ignored) {}
        finish();
    }

    /** Кольцо обратного отсчёта. */
    static final class Ring extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float frac = 1f;
        private String label = "";

        Ring(Context c) {
            super(c);
            track.setStyle(Paint.Style.STROKE);
            track.setColor(0x33B45309);
            arc.setStyle(Paint.Style.STROKE);
            arc.setColor(0xFFD97706);
            arc.setStrokeCap(Paint.Cap.ROUND);
            txt.setColor(0xFF92400E);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        void set(float f, String l) {
            frac = f;
            label = l;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight());
            float st = s * 0.09f;
            track.setStrokeWidth(st);
            arc.setStrokeWidth(st);
            float l = (getWidth() - s) / 2f + st / 2f, t = (getHeight() - s) / 2f + st / 2f;
            box.set(l, t, l + s - st, t + s - st);
            c.drawArc(box, 0, 360, false, track);
            c.drawArc(box, -90, 360 * frac, false, arc);
            txt.setTextSize(s * 0.34f);
            c.drawText(label, getWidth() / 2f, getHeight() / 2f - (txt.descent() + txt.ascent()) / 2f, txt);
        }
    }
}
