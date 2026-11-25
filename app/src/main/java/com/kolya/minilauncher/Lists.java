package com.kolya.minilauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.AlarmClock;
import android.telecom.TelecomManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Списки: видеосайты, видеоприложения, браузеры, системные приложения. */
final class Lists {
    private Lists() {}

    /** Домены видеосервисов (с поддоменами). Закрываются фильтром сети во всём телефоне. */
    static final String[] VIDEO_DOMAINS = {
            // YouTube и Google-видео
            "youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com", "ytimg.com",
            "youtubei.googleapis.com", "youtube.googleapis.com", "youtubekids.com", "yt.be",
            // TikTok и короткие видео
            "tiktok.com", "tiktokv.com", "tiktokcdn.com", "tiktokcdn-us.com", "ttwstatic.com", "byteoversea.com",
            "ibytedtos.com", "ibyteimg.com", "musical.ly", "tiktokw.us", "likee.video", "like.video", "likeevideo.com",
            "kwai.com", "kwai.net", "triller.co", "coub.com", "snackvideo.com",
            // Русскоязычные видеосервисы
            "rutube.ru", "rutube.sport", "rtbcdn.ru", "vkvideo.ru", "vk.video", "video.mail.ru", "dzen.ru", "zen.yandex.ru",
            "frontend.vh.yandex.ru", "strm.yandex.ru", "ivi.ru", "ivi.tv", "okko.tv", "okko.sport", "more.tv", "start.ru",
            "wink.ru", "premier.one", "kion.ru", "smotrim.ru", "1tv.ru", "ntv.ru", "ctc.ru", "tnt-online.ru", "kinopoisk.ru",
            "hd.kinopoisk.ru", "kinogo.biz", "lordfilm.lu", "hdrezka.ag", "rezka.ag", "rezka.cc", "hdrezka.me", "kinogo.ec",
            "filmix.ac", "baskino.me", "zona.plus", "megogo.net", "sweet.tv",
            // Международные
            "vimeo.com", "vimeocdn.com", "twitch.tv", "ttvnw.net", "jtvnw.net", "netflix.com", "nflxvideo.net",
            "nflxso.net", "nflxext.com", "dailymotion.com", "dmcdn.net", "dai.ly", "rumble.com", "bitchute.com",
            "odysee.com", "bilibili.com", "bilibili.tv", "biliapi.net", "hulu.com", "disneyplus.com", "primevideo.com",
            "max.com", "hbomax.com", "crunchyroll.com", "pluto.tv", "tubitv.com", "kick.com", "veoh.com",
            "jwplayer.com", "jwpcdn.com", "brightcove.com", "brightcove.net", "wistia.com", "wistia.net",
            "kaltura.com", "vidyard.com", "streamable.com", "ok.ru",
            // Обходы YouTube и скачивалки
            "yewtu.be", "piped.video", "kavin.rocks", "savefrom.net", "y2mate.com", "ssyoutube.com", "en.savefrom.net",
            "freetube.writingways.io",
            // Короткие видео в соцсетях
            "instagram.com", "cdninstagram.com", "threads.net",
            // Видео для взрослых
            "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com", "youporn.com", "spankbang.com",
            "eporner.com", "onlyfans.com", "chaturbate.com", "stripchat.com", "porn.com", "tube8.com", "beeg.com",
    };

    /** Если имя сервера содержит эти слова — тоже закрываем. */
    static final String[] VIDEO_WORDS = {
            "youtube", "googlevideo", "tiktok", "invidious", "piped.", "pornhub", "xvideos", "xnxx", "xhamster",
            "porn", "rezka", "kinogo", "lordfilm", "hdrezka",
    };

    /** Адреса страниц видео на сайтах, которые целиком не закрываем (проверка адресной строки). */
    static final String[] VIDEO_PATHS = {
            "vk.com/video", "vk.com/clip", "vk.com/vkvideo", "m.vk.com/video", "vk.ru/video", "vk.ru/clip",
            "my.mail.ru/video", "facebook.com/watch", "facebook.com/reel", "fb.watch", "x.com/i/broadcasts",
            "yandex.ru/video", "ya.ru/video", "yandex.com/video", "yandex.by/video", "yandex.kz/video",
            "tbm=vid", "udm=7",
    };

    /** Серверы «защищённого DNS» — через них браузер мог бы обойти фильтр. */
    static final String[] DOH_DOMAINS = {
            "dns.google", "dns.google.com", "cloudflare-dns.com", "mozilla.cloudflare-dns.com", "chrome.cloudflare-dns.com",
            "one.one.one.one", "1dot1dot1dot1.cloudflare-dns.com", "dns.quad9.net", "dns9.quad9.net", "dns11.quad9.net",
            "doh.opendns.com", "dns.adguard.com", "dns.adguard-dns.com", "unfiltered.adguard-dns.com", "dns.nextdns.io",
            "doh.cleanbrowsing.org", "dns.yandex.net", "common.dot.dns.yandex.net", "safe.dot.dns.yandex.net",
            "doh.dns.sb", "dns.controld.com", "freedns.controld.com", "doh.mullvad.net", "dns.mullvad.net",
            "doh.libredns.gr", "dns.alidns.com", "doh.pub", "dns.twnic.tw", "dns0.eu", "doh.xfinity.com",
    };

    static final String[] DOH_IPV4 = {
            "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "1.1.1.2", "1.0.0.2", "1.1.1.3", "1.0.0.3",
            "9.9.9.9", "149.112.112.112", "9.9.9.11", "149.112.112.11", "208.67.222.222", "208.67.220.220",
            "94.140.14.14", "94.140.15.15", "94.140.14.140", "94.140.14.141", "77.88.8.8", "77.88.8.1",
            "77.88.8.88", "77.88.8.2", "185.228.168.9", "185.228.169.9", "76.76.2.0", "76.76.10.0",
            "45.90.28.0", "45.90.30.0", "194.242.2.2", "185.222.222.222", "45.11.45.11",
    };

    static final String[] DOH_IPV6 = {
            "2001:4860:4860::8888", "2001:4860:4860::8844", "2606:4700:4700::1111", "2606:4700:4700::1001",
            "2620:fe::fe", "2620:fe::9", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff", "2a02:6b8::feed:0ff",
            "2a02:6b8:0:1::feed:0ff",
    };

    /** Известные видеоприложения: закрыты, пока включён блок видео. */
    static final Set<String> VIDEO_APPS = new HashSet<>(Arrays.asList(
            "com.google.android.youtube", "com.google.android.apps.youtube.kids", "com.google.android.youtube.tv",
            "com.google.android.apps.youtube.music", "com.google.android.apps.youtube.creator",
            "app.revanced.android.youtube", "app.rvx.android.youtube", "com.vanced.android.youtube",
            "org.schabi.newpipe", "org.polymorphicshade.newpipe", "free.rm.skytube.oss", "com.github.libretube",
            "com.zhiliaoapp.musically", "com.zhiliaoapp.musically.go", "com.ss.android.ugc.trill",
            "com.ss.android.ugc.aweme", "com.instagram.android", "com.instagram.lite", "com.instagram.barcelona",
            "com.facebook.katana", "com.facebook.lite", "com.snapchat.android", "com.vkontakte.android",
            "com.vk.vkvideo", "com.vk.vkclient", "ru.ok.android", "ru.rutube.app", "ru.rutube.app.tv",
            "video.like", "video.like.lite", "com.yandex.zen", "ru.yandex.yandexvideo", "ru.ivi.client", "ru.kinopoisk",
            "ru.more.play", "ru.okko.android", "ru.rt.video.app.mobile", "ru.mts.mtstv", "one.premier.android",
            "ru.smotrim.app", "ru.ctc.android", "com.netflix.mediaclient", "tv.twitch.android.app",
            "com.dailymotion.dailymotion", "com.vimeo.android.videoapp", "com.kwai.video", "com.kwai.bulldog",
            "com.smile.gifmaker", "com.pinterest", "com.amazon.avod.thirdpartyclient", "com.disney.disneyplus",
            "com.wbd.stream", "com.hbo.hbonow", "com.crunchyroll.crunchyroid", "tv.pluto.android",
            "com.tubitv", "com.rumble.battles", "com.coub.android", "com.google.android.videos",
            "com.google.android.apps.youtube.unplugged", "org.videolan.vlc", "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro", "is.xyz.mpv", "com.kmplayer", "com.brouken.player",
            "video.player.videoplayer", "com.inshot.xplayer", "com.bilibili.app.in", "tv.danmaku.bili",
            "com.megogo.application", "ua.youtv.youtv", "net.sweet.tv"
    ));

    /** Части имени пакета, по которым узнаём видеоприложение, которого нет в списке. */
    private static final String[] VIDEO_PKG_WORDS = {
            "youtube", "tiktok", "musically", "vkvideo", "rutube", "kinopoisk", "netflix", "twitch",
            "videoplayer", "video.player", "vlc", "likee", "video.like", "kwai", "newpipe", "libretube",
            "porn", "xvideos", "xhamster", "iptv", "smarttube",
    };

    /** Браузеры со встроенным VPN/прокси — ими можно обойти фильтр, поэтому они закрыты целиком. */
    static final Set<String> VPN_BROWSERS = new HashSet<>(Arrays.asList(
            "com.opera.browser", "com.opera.browser.beta", "com.opera.mini.native", "com.opera.mini.native.beta",
            "com.opera.gx", "com.opera.touch", "org.torproject.torbrowser", "org.torproject.torbrowser_alpha",
            "org.torproject.android", "info.guardianproject.orfox", "com.UCMobile.intl", "com.uc.browser.en",
            "com.ucmobile.lite", "com.UCMobile", "com.alohamobile.browser", "com.alohamobile.browser.lite",
            "com.cloudmosa.puffinFree", "com.cloudmosa.puffin", "com.cloudmosa.puffinTV", "com.tenta.android",
            "com.epicbrowser.mobile", "com.avast.android.secure.browser", "com.hidemyass.hidemyassbrowser",
            "com.browser.tor", "com.stoutner.privacybrowser.standard", "mark.via.gp",
            "com.kiwibrowser.browser", "com.yandex.browser.vpn"
    ));

    /** Мессенджеры и соцсети, в которых «залипают»: по умолчанию пауза 20 секунд. */
    static final Set<String> DISTRACTING = new HashSet<>(Arrays.asList(
            "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta",
            "org.telegram.plus", "org.thunderdog.challegram", "nekox.messenger", "tw.nekomimi.nekogram",
            "com.exteragram.messenger", "com.twitter.android", "com.reddit.frontpage", "com.discord",
            "ru.yandex.searchplugin"
    ));

    /** Notion: видео внутри его страниц можно разрешить отдельно. */
    static final String[] NOTION = {"notion.id", "so.notion.calendar"};

    /** Экраны, где «Фокус» можно отключить или удалить. */
    private static final Set<String> SETTINGS_LIKE = new HashSet<>(Arrays.asList(
            "com.android.settings", "com.google.android.packageinstaller", "com.android.packageinstaller",
            "com.samsung.android.packageinstaller", "com.google.android.permissioncontroller",
            "com.android.permissioncontroller", "com.android.systemui", "com.samsung.android.lool",
            "com.samsung.android.sm.devicesecurity", "com.miui.securitycenter", "com.coloros.safecenter",
            "com.android.vpndialogs", "com.google.android.apps.wellbeing", "com.samsung.android.forest"
    ));

    private static final Set<String> INSTALLERS = new HashSet<>(Arrays.asList(
            "com.google.android.packageinstaller", "com.android.packageinstaller",
            "com.samsung.android.packageinstaller"
    ));

    /** Слова на экранах настроек, рядом с названием «Фокус», при которых экран закрывается. */
    static final String[] DANGER_WORDS = {
            "удалить", "удаление", "остановить", "отключ", "выключ", "специальные возможности", "администратор",
            "vpn", "разрешени", "ограничен", "очистить", "хранилище", "по умолчанию", "использовать",
            "uninstall", "force stop", "disable", "turn off", "accessibility", "admin", "permission",
            "clear", "storage", "restricted",
    };

    // ------------------------------------------------------------ то, что зависит от телефона

    static volatile Set<String> browsers = Collections.emptySet();
    static volatile Set<String> vpnApps = Collections.emptySet();
    static volatile Set<String> launchers = Collections.emptySet();
    static volatile Set<String> nightSystem = Collections.emptySet();
    static volatile Set<String> keyboards = Collections.emptySet();

    static void refresh(Context c) {
        PackageManager pm = c.getPackageManager();
        String self = c.getPackageName();

        Set<String> b = new HashSet<>();
        Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"));
        web.addCategory(Intent.CATEGORY_BROWSABLE);
        addActivities(pm, web, b);
        b.remove(self);

        Set<String> v = new HashSet<>();
        try {
            List<ResolveInfo> list = pm.queryIntentServices(new Intent("android.net.VpnService"),
                    PackageManager.MATCH_ALL | PackageManager.GET_SERVICES);
            for (ResolveInfo ri : list) if (ri.serviceInfo != null) v.add(ri.serviceInfo.packageName);
        } catch (Exception ignored) {}
        v.remove(self);

        Set<String> l = new HashSet<>();
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        addActivities(pm, home, l);
        l.remove(self);

        Set<String> n = new HashSet<>();
        try {
            TelecomManager tm = c.getSystemService(TelecomManager.class);
            if (tm != null && tm.getDefaultDialerPackage() != null) n.add(tm.getDefaultDialerPackage());
        } catch (Exception ignored) {}
        addActivities(pm, new Intent(Intent.ACTION_DIAL), n);
        addActivities(pm, new Intent(AlarmClock.ACTION_SHOW_ALARMS), n);
        addActivities(pm, new Intent(AlarmClock.ACTION_SET_ALARM), n);
        try {
            for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
                String p = ai.packageName.toLowerCase(Locale.ROOT);
                if (p.endsWith(".contacts") || p.contains(".dialer") || p.contains("deskclock")) n.add(ai.packageName);
            }
        } catch (Exception ignored) {}
        n.remove(self);

        Set<String> k = new HashSet<>();
        try {
            InputMethodManager imm = c.getSystemService(InputMethodManager.class);
            if (imm != null) for (InputMethodInfo i : imm.getInputMethodList()) k.add(i.getPackageName());
        } catch (Exception ignored) {}

        browsers = b;
        vpnApps = v;
        launchers = l;
        nightSystem = n;
        keyboards = k;
    }

    private static void addActivities(PackageManager pm, Intent intent, Set<String> out) {
        try {
            for (ResolveInfo ri : pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)) {
                if (ri.activityInfo != null) out.add(ri.activityInfo.packageName);
            }
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ проверки

    static boolean isSettingsLike(String pkg) { return SETTINGS_LIKE.contains(pkg); }
    static boolean isInstaller(String pkg) { return INSTALLERS.contains(pkg); }
    static boolean isBrowser(String pkg) { return browsers.contains(pkg); }

    /** Окна, которые не считаются «открытым приложением» (клавиатура, шторка, системные диалоги). */
    static boolean isTransparent(String pkg) {
        return keyboards.contains(pkg) || "com.android.systemui".equals(pkg) || "android".equals(pkg);
    }

    /** Всегда доступно, даже ночью: рабочий стол, настройки, звонки, клавиатура. */
    static boolean isAlwaysAllowed(String pkg) {
        return launchers.contains(pkg) || keyboards.contains(pkg) || SETTINGS_LIKE.contains(pkg)
                || "android".equals(pkg) || "com.android.server.telecom".equals(pkg)
                || "com.android.phone".equals(pkg) || "com.google.android.dialer".equals(pkg)
                || "com.samsung.android.incallui".equals(pkg) || "com.android.emergency".equals(pkg);
    }

    static boolean isVideoApp(String pkg) {
        if (VIDEO_APPS.contains(pkg)) return true;
        String p = pkg.toLowerCase(Locale.ROOT);
        for (String w : VIDEO_PKG_WORDS) if (p.contains(w)) return true;
        return false;
    }

    static boolean isBypassApp(String pkg) {
        return VPN_BROWSERS.contains(pkg) || vpnApps.contains(pkg);
    }

    /** Закрыт ли этот сервер фильтром сети. */
    static boolean domainBlocked(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        for (String d : VIDEO_DOMAINS) if (h.equals(d) || h.endsWith("." + d)) return true;
        for (String d : DOH_DOMAINS) if (h.equals(d) || h.endsWith("." + d)) return true;
        for (String w : VIDEO_WORDS) if (h.contains(w)) return true;
        return false;
    }

    /** Текст из адресной строки браузера ведёт на видео? */
    static boolean urlBlocked(String text) {
        if (text == null) return false;
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty() || t.contains(" ")) return false; // это поисковый запрос, а не адрес
        int scheme = t.indexOf("://");
        if (scheme >= 0) t = t.substring(scheme + 3);
        for (String p : VIDEO_PATHS) if (t.contains(p)) return true;
        int end = t.length();
        for (char ch : new char[]{'/', '?', '#', ':'}) {
            int i = t.indexOf(ch);
            if (i >= 0 && i < end) end = i;
        }
        String host = t.substring(0, end);
        if (!host.contains(".")) return false;
        for (String d : VIDEO_DOMAINS) if (host.equals(d) || host.endsWith("." + d)) return true;
        for (String w : VIDEO_WORDS) if (host.contains(w)) return true;
        return false;
    }
}
