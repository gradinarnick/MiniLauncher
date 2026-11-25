package com.kolya.minilauncher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.util.Calendar;
import java.util.Locale;

/**
 * Решает, можно ли открыть приложение прямо сейчас. Все ограничения — только здесь:
 * ночной режим, блок видео, обход фильтра, правила приложений и временный доступ по коду.
 */
final class Guard {
    static final int OK = 0, PAUSE = 1, BLOCK = 2;

    /** Решение для приложения. */
    static final class Verdict {
        final int kind;
        final String title;
        final String text;
        final int illustration;
        final int seconds;
        final boolean dark;

        Verdict(int kind, String title, String text, int illustration, int seconds, boolean dark) {
            this.kind = kind;
            this.title = title;
            this.text = text;
            this.illustration = illustration;
            this.seconds = seconds;
            this.dark = dark;
        }
    }

    static final Verdict ALLOW = new Verdict(OK, null, null, 0, 0, false);

    private Guard() {}

    // ------------------------------------------------------------ время

    static String hhmm(int minutes) {
        return String.format(Locale.ROOT, "%d:%02d", minutes / 60, minutes % 60);
    }

    static String clock(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return hhmm(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE));
    }

    static int minuteOfDay(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    /** Попадает ли время в промежуток [start, end) — в том числе через полночь. */
    static boolean inRange(long now, int start, int end) {
        if (start == end) return false;
        int m = minuteOfDay(now);
        return start < end ? (m >= start && m < end) : (m >= start || m < end);
    }

    static boolean nightNow(Prefs p) {
        return p.nightMode() && inRange(System.currentTimeMillis(), p.nightStart(), p.nightEnd());
    }

    static String nightRange(Prefs p) {
        return hhmm(p.nightStart()) + "–" + hhmm(p.nightEnd());
    }

    // ------------------------------------------------------------ временный доступ

    static boolean temp(Prefs p, String scope) {
        return p.until(scope) > System.currentTimeMillis();
    }

    /** Фильтр видео действует (включён и не открыт временно по коду). */
    static boolean videoFilterActive(Prefs p) {
        return p.videoBlock() && !temp(p, "all") && !temp(p, "video");
    }

    // ------------------------------------------------------------ главное решение

    static Verdict check(Context c, Prefs p, String pkg, long now) {
        if (pkg == null || pkg.equals(c.getPackageName()) || Lists.isAlwaysAllowed(pkg)) return ALLOW;
        if (temp(p, "all")) return ALLOW;

        if (p.nightMode() && inRange(now, p.nightStart(), p.nightEnd()) && nightBlocked(p, pkg)) {
            return nightVerdict(p);
        }

        if (videoFilterActive(p)) {
            if (Lists.isBypassApp(pkg)) {
                return new Verdict(BLOCK, "Закрыто",
                        "Через это приложение можно обойти блок видео, поэтому оно закрыто.",
                        R.drawable.ill_block, 0, false);
            }
            if (Lists.isVideoApp(pkg)) {
                return new Verdict(BLOCK, "Видео заблокировано",
                        "Видеоприложения закрыты.\nВременный доступ — только по коду владельца ключа.",
                        R.drawable.ill_video_off, 0, false);
            }
            if (Lists.isBrowser(pkg) && (!FilterVpn.running || Net.privateDnsBypass(c))) {
                return new Verdict(BLOCK, "Фильтр видео выключен",
                        "Пока фильтр сети не работает, браузеры закрыты.\nОткрой «Фокус» и включи фильтр.",
                        R.drawable.ill_video_off, 0, false);
            }
        }

        if (temp(p, "app:" + pkg)) return ALLOW;

        Rule r = Rule.parse(p.rule(pkg));
        switch (r.type) {
            case Rule.BLOCK:
                return new Verdict(BLOCK, "Заблокировано",
                        "Для этого приложения стоит полный запрет.", R.drawable.ill_block, 0, false);
            case Rule.SCHED:
                if (inRange(now, r.a, r.b)) {
                    return new Verdict(BLOCK, "Сейчас недоступно",
                            "Приложение закрыто с " + hhmm(r.a) + " до " + hhmm(r.b) + ".",
                            R.drawable.ill_clock, 0, false);
                }
                break;
            case Rule.LIMIT:
                if (p.usedToday(pkg) >= r.a * 60_000L) {
                    return new Verdict(BLOCK, "Лимит на сегодня исчерпан",
                            r.a + " мин в день уже использованы.\nЗавтра лимит начнётся заново.",
                            R.drawable.ill_timer, 0, false);
                }
                break;
            case Rule.PAUSE:
                return new Verdict(PAUSE, "Пауза", null, R.drawable.ill_hourglass, Math.max(3, r.a), false);
            default:
                break;
        }
        return ALLOW;
    }

    /** Закрыто ли приложение ночью: соцсети, магазины, игры и то, что ты добавил сам. */
    static boolean nightBlocked(Prefs p, String pkg) {
        if (Lists.nightSystem.contains(pkg)) return false;
        if (p.nightExtra().contains(pkg)) return true;
        return Lists.nightDefault.contains(pkg) && !p.nightAllowed().contains(pkg);
    }

    /** Ночью сайты соцсетей и магазинов закрыты (фильтр сети и адресная строка). */
    static boolean nightSitesBlocked(Prefs p) {
        return nightNow(p) && !temp(p, "all");
    }

    static Verdict nightVerdict(Prefs p) {
        return new Verdict(BLOCK, "Ночной режим",
                "До " + hhmm(p.nightEnd()) + " закрыты соцсети, магазины, игры и другие отвлекающие приложения.\nОтдохни — телефон подождёт.",
                R.drawable.ill_moon, 0, true);
    }

    static Verdict videoPageVerdict() {
        return new Verdict(BLOCK, "Видео заблокировано",
                "Видеосайты закрыты в любом браузере.\nВременный доступ — только по коду владельца ключа.",
                R.drawable.ill_video_off, 0, false);
    }

    static Verdict protectVerdict() {
        return new Verdict(BLOCK, "Защищено",
                "Отключить или удалить «Фокус» можно только с кодом владельца ключа:\nФокус → Ключ → Удалить.",
                R.drawable.ill_shield, 0, false);
    }

    // ------------------------------------------------------------ чёрно-белый телефон

    private static final String DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled";
    private static final String DALTONIZER_MODE = "accessibility_display_daltonizer";

    static boolean canControlSystem(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    static boolean isPhoneGrayscale(Context c) {
        try {
            ContentResolver cr = c.getContentResolver();
            return Settings.Secure.getInt(cr, DALTONIZER_ENABLED, 0) == 1
                    && Settings.Secure.getInt(cr, DALTONIZER_MODE, -1) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static void enforceGrayscale(Context c, Prefs p) {
        if (!p.grayscale() || temp(p, "all") || !canControlSystem(c) || isPhoneGrayscale(c)) return;
        setPhoneGrayscale(c, true);
    }

    static void setPhoneGrayscale(Context c, boolean on) {
        try {
            ContentResolver cr = c.getContentResolver();
            if (on) Settings.Secure.putInt(cr, DALTONIZER_MODE, 0);
            Settings.Secure.putInt(cr, DALTONIZER_ENABLED, on ? 1 : 0);
        } catch (Exception ignored) {
            // нет разрешения — ничего не делаем
        }
    }
}
