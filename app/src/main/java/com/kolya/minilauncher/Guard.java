package com.kolya.minilauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.AlarmClock;
import android.telecom.TelecomManager;

import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Правила против постоянного залипания в телефоне:
 *  - отвлекающие приложения открываются только после паузы (длительность настраивается)
 *    и не ставятся на рабочий стол;
 *  - ночной режим (время настраивается, по умолчанию 20:00–9:00): доступны только звонки,
 *    будильник и разрешённые приложения (Библия); выключить его ночью нельзя;
 *  - блок видео и браузеров: включается сразу, а выключается только в конце ночного режима;
 *  - весь телефон чёрно-белый: лаунчер держит системную функцию включённой
 *    (нужно разрешение WRITE_SECURE_SETTINGS, выдаётся один раз через adb).
 */
final class Guard {
    /** Задержка перед любым ослаблением ограничений. */
    static final int LOOSEN_DELAY_SECONDS = 30;

    /** Видеоприложения и соцсети с видео. Браузеры определяются автоматически. */
    private static final Set<String> KNOWN_VIDEO = new HashSet<>(Arrays.asList(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.kids",
            "com.google.android.youtube.tv",
            "app.revanced.android.youtube",
            "app.rvx.android.youtube",
            "com.vanced.android.youtube",
            "org.schabi.newpipe",
            "free.rm.skytube.oss",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.ss.android.ugc.aweme",
            "com.instagram.android",
            "com.instagram.lite",
            "com.facebook.katana",
            "com.facebook.lite",
            "com.snapchat.android",
            "com.vkontakte.android",
            "com.vk.vkvideo",
            "com.vk.vkclient",
            "ru.rutube.app",
            "ru.rutube.app.tv",
            "video.like",
            "com.yandex.zen",
            "ru.yandex.yandexvideo",
            "ru.ivi.client",
            "ru.kinopoisk",
            "ru.more.play",
            "com.netflix.mediaclient",
            "tv.twitch.android.app",
            "com.dailymotion.dailymotion",
            "com.vimeo.android.videoapp",
            "com.kwai.video",
            "com.smile.gifmaker",
            "com.pinterest"
    ));

    /** Мессенджеры и прочее, что открывается «на автомате» — только пауза, без блокировки. */
    private static final Set<String> KNOWN_DISTRACTING = new HashSet<>(Arrays.asList(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta",
            "org.telegram.plus",
            "org.thunderdog.challegram",
            "nekox.messenger",
            "tw.nekomimi.nekogram",
            "com.exteragram.messenger",
            "com.twitter.android",
            "com.reddit.frontpage",
            "ru.ok.android",
            "com.discord"
    ));

    private static Set<String> systemNight = new HashSet<>();

    private Guard() {}

    // ------------------------------------------------------------------ время

    static boolean isNight(Prefs prefs, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int start = prefs.nightStart();
        int end = prefs.nightEnd();
        if (start == end) return false;
        return start < end ? (m >= start && m < end) : (m >= start || m < end);
    }

    /** Ближайший конец ночного режима строго после now. */
    static long nextMorning(Prefs prefs, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, prefs.nightEnd() / 60);
        c.set(Calendar.MINUTE, prefs.nightEnd() % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis();
    }

    static String hhmm(int minutes) {
        return String.format(Locale.ROOT, "%d:%02d", minutes / 60, minutes % 60);
    }

    static String nightEndText(Prefs prefs) {
        return hhmm(prefs.nightEnd());
    }

    static String nightRangeText(Prefs prefs) {
        return hhmm(prefs.nightStart()) + "–" + hhmm(prefs.nightEnd());
    }

    static boolean nightActive(Prefs prefs) {
        return prefs.nightMode() && isNight(prefs, System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ чёрно-белый телефон

    private static final String DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled";
    private static final String DALTONIZER_MODE = "accessibility_display_daltonizer";
    private static final int MODE_MONOCHROME = 0;

    /** Выдано ли разрешение менять системные настройки (adb shell pm grant …). */
    static boolean canControlGrayscale(Context ctx) {
        return ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean isPhoneGrayscale(Context ctx) {
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            return android.provider.Settings.Secure.getInt(cr, DALTONIZER_ENABLED, 0) == 1
                    && android.provider.Settings.Secure.getInt(cr, DALTONIZER_MODE, -1) == MODE_MONOCHROME;
        } catch (Exception e) {
            return false;
        }
    }

    /** Если чёрно-белый режим положен — включает его (в том числе после выключения в обход). */
    static void enforceGrayscale(Context ctx, Prefs prefs) {
        if (!prefs.grayscale() || !canControlGrayscale(ctx)) return;
        if (isPhoneGrayscale(ctx)) return;
        setPhoneGrayscale(ctx, true);
    }

    static void setPhoneGrayscale(Context ctx, boolean on) {
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            if (on) android.provider.Settings.Secure.putInt(cr, DALTONIZER_MODE, MODE_MONOCHROME);
            android.provider.Settings.Secure.putInt(cr, DALTONIZER_ENABLED, on ? 1 : 0);
        } catch (Exception ignored) {
            // Нет разрешения — ничего не делаем.
        }
    }

    /** Действует ли блок видео прямо сейчас (и снимает его, если наступило время). */
    static boolean videoActive(Prefs prefs) {
        if (!prefs.videoBlock()) return false;
        long until = prefs.videoUnblockAt();
        if (until > 0 && System.currentTimeMillis() >= until) {
            prefs.setVideoBlock(false);
            prefs.setVideoUnblockAt(0);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ проверки

    /** Звонилки и будильник — разрешены ночью всегда. */
    static void refreshSystemApps(Context ctx) {
        Set<String> s = new HashSet<>();
        PackageManager pm = ctx.getPackageManager();
        try {
            TelecomManager tm = ctx.getSystemService(TelecomManager.class);
            if (tm != null && tm.getDefaultDialerPackage() != null) s.add(tm.getDefaultDialerPackage());
        } catch (Exception ignored) {}
        addResolvers(pm, new Intent(Intent.ACTION_DIAL), s);
        addResolvers(pm, new Intent(AlarmClock.ACTION_SHOW_ALARMS), s);
        addResolvers(pm, new Intent(AlarmClock.ACTION_SET_ALARM), s);
        s.remove(ctx.getPackageName());
        systemNight = s;
    }

    static boolean allowedAtNight(Prefs prefs, String pkg) {
        return systemNight.contains(pkg) || prefs.nightAllowed().contains(pkg);
    }

    static boolean isSystemNightApp(String pkg) {
        return systemNight.contains(pkg);
    }

    /** @return текст причины блокировки или null, если приложение можно открыть. */
    static String blockReason(Prefs prefs, String pkg) {
        if (nightActive(prefs) && !allowedAtNight(prefs, pkg)) {
            return "Ночной режим до " + nightEndText(prefs) + ".\nСейчас доступны только звонки, будильник и Библия.";
        }
        if (videoActive(prefs) && prefs.videoApps().contains(pkg)) {
            String when = prefs.videoUnblockAt() > 0 ? " до " + nightEndText(prefs) : "";
            return "Видео и браузеры заблокированы" + when + ".\nВидео можно смотреть только в Telegram.";
        }
        return null;
    }

    static boolean isDistracting(Prefs prefs, String pkg) {
        return prefs.distracting().contains(pkg);
    }

    // ------------------------------------------------------------------ автоклассификация

    /**
     * Новые установленные видеоприложения и браузеры сразу попадают в блок видео и
     * в отвлекающие, мессенджеры-«залипалки» — в отвлекающие. Каждое приложение
     * классифицируется один раз: если потом снять с него отметку, она не вернётся.
     */
    static void autoClassify(Context ctx, Prefs prefs, List<AppEntry> apps) {
        Set<String> installed = new HashSet<>();
        for (AppEntry a : apps) installed.add(a.packageName());

        Set<String> browsers = new HashSet<>();
        Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"));
        web.addCategory(Intent.CATEGORY_BROWSABLE);
        addResolvers(ctx.getPackageManager(), web, browsers);

        Set<String> seen = prefs.seenPackages();
        Set<String> video = prefs.videoApps();
        Set<String> distracting = prefs.distracting();
        boolean changed = false;
        for (String pkg : installed) {
            if (seen.contains(pkg)) continue;
            seen.add(pkg);
            changed = true;
            if (KNOWN_VIDEO.contains(pkg) || browsers.contains(pkg)) {
                video.add(pkg);
                distracting.add(pkg);
            } else if (KNOWN_DISTRACTING.contains(pkg)) {
                distracting.add(pkg);
            }
        }
        if (!prefs.biblePresetDone()) {
            Set<String> night = prefs.nightAllowed();
            for (AppEntry a : apps) {
                String l = a.originalLabel.toLowerCase(Locale.ROOT);
                String p = a.packageName().toLowerCase(Locale.ROOT);
                if (l.contains("библи") || l.contains("bible") || l.contains("biblia")
                        || l.contains("біблі") || p.contains("bible") || p.contains("biblia")) {
                    night.add(a.packageName());
                }
            }
            prefs.setNightAllowed(night);
            prefs.setBiblePresetDone();
        }
        if (changed) {
            prefs.setSeenPackages(seen);
            prefs.setVideoApps(video);
            prefs.setDistracting(distracting);
        }
    }

    private static void addResolvers(PackageManager pm, Intent intent, Set<String> out) {
        try {
            List<ResolveInfo> list = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL);
            for (ResolveInfo ri : list) {
                if (ri.activityInfo != null) out.add(ri.activityInfo.packageName);
            }
        } catch (Exception ignored) {}
    }
}
