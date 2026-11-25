package com.kolya.minilauncher;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Общие входящие: переписка из уведомлений Telegram, WhatsApp, Viber и VK.
 * Хранится только на телефоне (файл inbox.json), наружу ничего не уходит.
 * Ответ отправляет сам мессенджер — через кнопку «Ответить» из его уведомления.
 */
final class Inbox {
    private Inbox() {}

    static final int MAX_CHATS = 80, MAX_MSGS = 120;

    static final class Msg {
        String sender;
        String text;
        long ts;
        boolean me;
    }

    static final class Chat {
        String id, pkg, title;
        boolean group;
        long last;
        int unread;
        final List<Msg> msgs = new ArrayList<>();
    }

    /** Кнопки из последнего уведомления чата. Живут, пока жив процесс. */
    static final class Live {
        Notification.Action reply, read;
        PendingIntent open;
        String key;
    }

    static final Map<String, Live> live = new HashMap<>();
    private static List<Chat> chats;
    private static Runnable listener;
    private static final Handler main = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------ мессенджеры

    /** Мессенджер по пакету: 0 — не наш, 1 — Telegram, 2 — WhatsApp, 3 — Viber, 4 — VK. */
    static int kind(String pkg) {
        if (pkg == null) return 0;
        if (pkg.startsWith("org.telegram.") || pkg.equals("org.thunderdog.challegram") || pkg.equals("nekox.messenger")
                || pkg.equals("tw.nekomimi.nekogram") || pkg.equals("com.exteragram.messenger")) return 1;
        if (pkg.equals("com.whatsapp") || pkg.equals("com.whatsapp.w4b")) return 2;
        if (pkg.equals("com.viber.voip")) return 3;
        if (pkg.equals("com.vkontakte.android") || pkg.equals("com.vk.im") || pkg.equals("com.vk.vkclient")) return 4;
        return 0;
    }

    static final String[] NAMES = {"", "Telegram", "WhatsApp", "Viber", "VK"};
    static final int[] COLORS = {0xFF64748B, 0xFF229ED9, 0xFF25D366, 0xFF7360F2, 0xFF0077FF};

    static String appName(String pkg) { return NAMES[kind(pkg)]; }
    static int appColor(String pkg) { return COLORS[kind(pkg)]; }

    static boolean enabled(Context c) {
        android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
        return nm != null && nm.isNotificationListenerAccessGranted(new ComponentName(c, InboxService.class));
    }

    // ------------------------------------------------------------ данные

    static synchronized List<Chat> chats(Context c) {
        if (chats == null) chats = load(c);
        return chats;
    }

    static synchronized List<Chat> sorted(Context c) {
        List<Chat> l = new ArrayList<>(chats(c));
        Collections.sort(l, (a, b) -> Long.compare(b.last, a.last));
        return l;
    }

    static synchronized Chat find(Context c, String id) {
        for (Chat ch : chats(c)) if (ch.id.equals(id)) return ch;
        return null;
    }

    static synchronized int unreadTotal(Context c) {
        int n = 0;
        for (Chat ch : chats(c)) n += ch.unread;
        return n;
    }

    static synchronized Chat getOrCreate(Context c, String id, String pkg) {
        Chat ch = find(c, id);
        if (ch == null) {
            ch = new Chat();
            ch.id = id;
            ch.pkg = pkg;
            chats(c).add(ch);
        }
        return ch;
    }

    /** Добавляет сообщение, если его ещё нет. true — новое. */
    static synchronized boolean add(Chat ch, Msg m) {
        for (int i = ch.msgs.size() - 1; i >= 0 && i >= ch.msgs.size() - 60; i--) {
            Msg o = ch.msgs.get(i);
            if (o.text.equals(m.text) && (o.ts == m.ts || (o.me && m.me && Math.abs(o.ts - m.ts) < 120_000))) return false;
        }
        int pos = ch.msgs.size();
        while (pos > 0 && ch.msgs.get(pos - 1).ts > m.ts) pos--;
        ch.msgs.add(pos, m);
        while (ch.msgs.size() > MAX_MSGS) ch.msgs.remove(0);
        if (m.ts > ch.last) ch.last = m.ts;
        return true;
    }

    static synchronized void markRead(Context c, Chat ch) {
        if (ch.unread == 0) return;
        ch.unread = 0;
        save(c);
    }

    static synchronized void delete(Context c, Chat ch) {
        chats(c).remove(ch);
        live.remove(ch.id);
        save(c);
    }

    // ------------------------------------------------------------ подписка экрана

    static void setListener(Runnable r) { listener = r; }

    static void changed() {
        Runnable r = listener;
        if (r != null) main.post(r);
    }

    // ------------------------------------------------------------ файл

    private static File file(Context c) { return new File(c.getFilesDir(), "inbox.json"); }

    private static List<Chat> load(Context c) {
        List<Chat> out = new ArrayList<>();
        try {
            File f = file(c);
            if (!f.exists()) return out;
            JSONArray arr = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Chat ch = new Chat();
                ch.id = o.getString("id");
                ch.pkg = o.getString("pkg");
                ch.title = o.optString("title", "");
                ch.group = o.optBoolean("group");
                ch.last = o.optLong("last");
                ch.unread = o.optInt("unread");
                JSONArray ms = o.optJSONArray("msgs");
                if (ms != null) for (int j = 0; j < ms.length(); j++) {
                    JSONObject mo = ms.getJSONObject(j);
                    Msg m = new Msg();
                    m.sender = mo.optString("s", null);
                    m.text = mo.optString("t", "");
                    m.ts = mo.optLong("ts");
                    m.me = mo.optBoolean("me");
                    ch.msgs.add(m);
                }
                out.add(ch);
            }
        } catch (Exception ignored) {
            // повреждённый файл — начинаем с пустого списка
        }
        return out;
    }

    static synchronized void save(Context c) {
        List<Chat> l = chats(c);
        if (l.size() > MAX_CHATS) {
            Collections.sort(l, (a, b) -> Long.compare(b.last, a.last));
            while (l.size() > MAX_CHATS) l.remove(l.size() - 1);
        }
        try {
            JSONArray arr = new JSONArray();
            for (Chat ch : l) {
                JSONObject o = new JSONObject();
                o.put("id", ch.id);
                o.put("pkg", ch.pkg);
                o.put("title", ch.title);
                o.put("group", ch.group);
                o.put("last", ch.last);
                o.put("unread", ch.unread);
                JSONArray ms = new JSONArray();
                for (Msg m : ch.msgs) {
                    JSONObject mo = new JSONObject();
                    if (m.sender != null) mo.put("s", m.sender);
                    mo.put("t", m.text);
                    mo.put("ts", m.ts);
                    if (m.me) mo.put("me", true);
                    ms.put(mo);
                }
                o.put("msgs", ms);
                arr.put(o);
            }
            File tmp = new File(c.getFilesDir(), "inbox.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(file(c));
        } catch (Exception ignored) {
        }
        changed();
    }
}
