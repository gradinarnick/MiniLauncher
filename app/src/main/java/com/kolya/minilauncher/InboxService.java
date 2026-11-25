package com.kolya.minilauncher;

import android.app.Notification;
import android.app.Person;
import android.app.RemoteInput;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Locale;

/**
 * Читает уведомления Telegram, WhatsApp, Viber и VK и складывает переписку в {@link Inbox}.
 * Остальные уведомления не трогает и не сохраняет.
 */
public class InboxService extends NotificationListenerService {

    @Override
    public void onListenerConnected() {
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) for (StatusBarNotification sbn : active) handle(sbn, false);
        } catch (Exception ignored) {}
        Inbox.save(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (handle(sbn, true)) Inbox.save(this);
    }

    /** true — что-то изменилось. countUnread=false при начальном чтении. */
    private boolean handle(StatusBarNotification sbn, boolean countUnread) {
        if (sbn == null || Inbox.kind(sbn.getPackageName()) == 0) return false;
        Notification n = sbn.getNotification();
        if (n == null) return false;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return false;
        if ((n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE)) != 0) return false;
        Bundle ex = n.extras;
        if (ex == null) return false;

        String pkg = sbn.getPackageName();
        Parcelable[] msgs = arr(ex, Notification.EXTRA_MESSAGES);
        boolean styled = msgs != null && msgs.length > 0;
        Notification.Action reply = replyAction(n);
        if (!styled && reply == null && !Notification.CATEGORY_MESSAGE.equals(n.category)) return false;

        String title = str(ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        boolean group = ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) || title != null;
        if (title == null) title = str(ex.getCharSequence(Notification.EXTRA_TITLE));
        if (title == null) return false;
        title = cleanTitle(title);

        String id = pkg + "|" + (n.getShortcutId() != null ? n.getShortcutId() : title);
        Inbox.Chat ch = Inbox.getOrCreate(this, id, pkg);
        ch.title = title;
        ch.group = group;

        String self = null;
        Person selfP = parcel(ex, Notification.EXTRA_MESSAGING_PERSON) instanceof Person
                ? (Person) parcel(ex, Notification.EXTRA_MESSAGING_PERSON) : null;
        if (selfP != null && selfP.getName() != null) self = selfP.getName().toString();

        boolean changed = false;
        if (styled) {
            for (Parcelable p : msgs) {
                if (!(p instanceof Bundle)) continue;
                Bundle b = (Bundle) p;
                Inbox.Msg m = new Inbox.Msg();
                CharSequence text = b.getCharSequence("text");
                m.text = text != null ? text.toString() : (b.get("uri") != null ? "📎 Вложение" : "");
                if (m.text.isEmpty()) continue;
                m.ts = b.getLong("time", n.when);
                String sender = null;
                Object sp = b.get("sender_person");
                if (sp instanceof Person && ((Person) sp).getName() != null) sender = ((Person) sp).getName().toString();
                if (sender == null) sender = str(b.getCharSequence("sender"));
                m.me = sender == null || sender.equals(self);
                m.sender = m.me ? null : sender;
                if (Inbox.add(ch, m)) {
                    changed = true;
                    if (countUnread && !m.me) ch.unread++;
                }
            }
        } else {
            CharSequence[] lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            CharSequence text = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (text == null) text = ex.getCharSequence(Notification.EXTRA_TEXT);
            if (lines != null && lines.length > 0) {
                for (CharSequence l : lines) changed |= addPlain(ch, l, n.when, countUnread);
            } else {
                changed |= addPlain(ch, text, n.when, countUnread);
            }
        }

        Inbox.Live lv = new Inbox.Live();
        lv.reply = reply;
        lv.read = readAction(n);
        lv.open = n.contentIntent;
        lv.key = sbn.getKey();
        Inbox.live.put(id, lv);
        if (ch.msgs.isEmpty()) {
            Inbox.delete(this, ch);
            return false;
        }
        Inbox.changed();
        return changed;
    }

    private static boolean addPlain(Inbox.Chat ch, CharSequence cs, long when, boolean countUnread) {
        String t = str(cs);
        if (t == null) return false;
        Inbox.Msg m = new Inbox.Msg();
        m.text = t;
        m.ts = when;
        if (Inbox.add(ch, m)) {
            if (countUnread) ch.unread++;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------ кнопки уведомления

    static Notification.Action replyAction(Notification n) {
        if (n.actions == null) return null;
        Notification.Action best = null;
        for (Notification.Action a : n.actions) {
            RemoteInput[] ri = a.getRemoteInputs();
            if (ri == null || ri.length == 0 || a.actionIntent == null) continue;
            boolean free = false;
            for (RemoteInput r : ri) if (r.getAllowFreeFormInput()) free = true;
            if (!free) continue;
            if (a.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_REPLY) return a;
            if (best == null) best = a;
        }
        return best;
    }

    private static Notification.Action readAction(Notification n) {
        if (n.actions == null) return null;
        for (Notification.Action a : n.actions) {
            if (a.actionIntent == null) continue;
            if (a.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ) return a;
            String t = a.title == null ? "" : a.title.toString().toLowerCase(Locale.ROOT);
            if (t.contains("прочит") || t.contains("read")) return a;
        }
        return null;
    }

    // ------------------------------------------------------------ мелочи

    /** Telegram пишет в заголовке «Имя (3 сообщения)» — убираем счётчик. */
    private static String cleanTitle(String t) {
        return t.replaceAll("\\s*\\(\\d+[^)]*\\)\\s*$", "").trim();
    }

    private static String str(CharSequence cs) {
        if (cs == null) return null;
        String s = cs.toString().trim();
        return s.isEmpty() ? null : s;
    }

    @SuppressWarnings("deprecation")
    private static Parcelable[] arr(Bundle b, String key) {
        try {
            return b.getParcelableArray(key);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static Parcelable parcel(Bundle b, String key) {
        try {
            return b.getParcelable(key);
        } catch (Exception e) {
            return null;
        }
    }
}
