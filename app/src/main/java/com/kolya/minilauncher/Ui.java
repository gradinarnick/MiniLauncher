package com.kolya.minilauncher;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Общий стиль: светлый фон, белые карточки, скругления, мягкие цвета. */
final class Ui {
    static final int BG = 0xFFF4F6FB;
    static final int CARD = 0xFFFFFFFF;
    static final int INK = 0xFF0F172A;
    static final int SUB = 0xFF64748B;
    static final int LINE = 0xFFE2E8F0;
    static final int ACCENT = 0xFF4F46E5;
    static final int ACCENT2 = 0xFF7C3AED;
    static final int ACCENT_SOFT = 0xFFEEF2FF;
    static final int GREEN = 0xFF15803D;
    static final int GREEN_SOFT = 0xFFDCFCE7;
    static final int RED = 0xFFE11D48;
    static final int RED_SOFT = 0xFFFFE4E6;
    static final int AMBER = 0xFFB45309;
    static final int AMBER_SOFT = 0xFFFEF3C7;
    static final int GRAY_SOFT = 0xFFF1F5F9;

    static final int BTN_PRIMARY = 0, BTN_SOFT = 1, BTN_DANGER = 2, BTN_WHITE = 3;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static Drawable pressable(Drawable bg, float radiusPx) {
        return new RippleDrawable(ColorStateList.valueOf(0x22000000), bg, round(0xFFFFFFFF, radiusPx));
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        int p = dp(c, 18);
        l.setPadding(p, p, p, p);
        l.setBackground(round(CARD, dp(c, 24)));
        l.setElevation(dp(c, 1));
        return l;
    }

    /** Параметры с отступом сверху. */
    static LinearLayout.LayoutParams lp(Context c, int w, int h, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(c, topDp);
        return p;
    }

    static LinearLayout.LayoutParams weight(Context c, int marginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        p.leftMargin = dp(c, marginDp);
        p.rightMargin = dp(c, marginDp);
        return p;
    }

    static TextView button(Context c, String s, int style) {
        int bg, fg;
        switch (style) {
            case BTN_SOFT: bg = ACCENT_SOFT; fg = ACCENT; break;
            case BTN_DANGER: bg = RED_SOFT; fg = RED; break;
            case BTN_WHITE: bg = 0xFFFFFFFF; fg = ACCENT; break;
            default: bg = ACCENT; fg = 0xFFFFFFFF; break;
        }
        TextView t = text(c, s, 16, fg, true);
        t.setGravity(Gravity.CENTER);
        int h = dp(c, 14), w = dp(c, 20);
        t.setPadding(w, h, w, h);
        float r = dp(c, 18);
        t.setBackground(pressable(round(bg, r), r));
        return t;
    }

    static ImageView image(Context c, int res, int sizeDp) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return iv;
    }

    static TextView chip(Context c, String s, int fg, int bg) {
        TextView t = text(c, s, 13, fg, true);
        int h = dp(c, 5), w = dp(c, 10);
        t.setPadding(w, h, w, h);
        t.setBackground(round(bg, dp(c, 12)));
        return t;
    }

    static void clickable(View v, float radiusDp, Runnable r) {
        Drawable bg = v.getBackground();
        float rad = dp(v.getContext(), radiusDp);
        v.setBackground(pressable(bg != null ? bg : new ColorDrawable(0), rad));
        v.setOnClickListener(x -> r.run());
    }

    /** Нижняя шторка со скруглёнными углами. */
    static Dialog sheet(Activity a, View content) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout wrap = col(a);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        float r = dp(a, 28);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        wrap.setBackground(bg);
        int p = dp(a, 20);
        wrap.setPadding(p, dp(a, 10), p, dp(a, 28));
        View handle = new View(a);
        handle.setBackground(round(LINE, dp(a, 3)));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(a, 40), dp(a, 5));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(a, 14);
        wrap.addView(handle, hp);
        wrap.addView(content);
        ScrollView sv = new ScrollView(a);
        sv.addView(wrap);
        d.setContentView(sv);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        d.show();
        return d;
    }
}
