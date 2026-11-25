package com.kolya.minilauncher;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Фильтр сети на весь телефон. Это «VPN» только по названию: наружу ничего не уходит.
 * Через него идут только DNS-запросы — имена видеосервисов получают адрес 0.0.0.0,
 * остальное пересылается обычному DNS. Серверы «защищённого DNS» тоже закрыты.
 */
public class FilterVpn extends VpnService {
    static volatile boolean running;

    private static final byte[] DNS_IP = {10, 111, (byte) 222, 2};
    private static final String[] UPSTREAM = {"8.8.8.8", "1.1.1.1", "77.88.8.8"};

    private ParcelFileDescriptor tun;
    private FileOutputStream out;
    private ExecutorService pool;
    private ScheduledExecutorService watch;
    private Prefs prefs;

    static void start(Context c) {
        try {
            if (VpnService.prepare(c) != null) return; // нет согласия — его даёт экран «Фокуса»
            c.startService(new Intent(c, FilterVpn.class));
        } catch (Exception ignored) {}
    }

    static void restart(Context c) {
        try {
            if (VpnService.prepare(c) != null) return;
            c.startService(new Intent(c, FilterVpn.class).setAction("restart"));
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        prefs = new Prefs(this);
        if (intent != null && "restart".equals(intent.getAction())) stopTun();
        if (tun == null) startTun();
        if (watch == null) {
            watch = Executors.newSingleThreadScheduledExecutor();
            watch.scheduleWithFixedDelay(() -> {
                try {
                    Net.ensureAccessibility(this);
                    Net.enforcePrivateDns(this);
                    Guard.enforceGrayscale(this, prefs);
                } catch (Exception ignored) {}
            }, 2, 10, TimeUnit.SECONDS);
        }
        return START_STICKY;
    }

    private synchronized void startTun() {
        try {
            Builder b = new Builder();
            b.setSession(getString(R.string.app_name));
            b.addAddress("10.111.222.1", 32);
            b.addDnsServer("10.111.222.2");
            b.addRoute("10.111.222.2", 32);
            for (String ip : Lists.DOH_IPV4) b.addRoute(ip, 32);
            try {
                b.addAddress("fd00:f0c5::1", 128);
                for (String ip : Lists.DOH_IPV6) b.addRoute(ip, 128);
            } catch (Exception ignored) {}
            b.setMtu(1500);
            b.setBlocking(true);
            b.addDisallowedApplication(getPackageName());
            if (prefs.notionVideo()) {
                for (String n : Lists.NOTION) {
                    try {
                        b.addDisallowedApplication(n);
                    } catch (Exception ignored) {}
                }
            }
            b.setConfigureIntent(PendingIntent.getActivity(this, 0,
                    new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
            b.setMetered(false);
            ParcelFileDescriptor fd = b.establish();
            if (fd == null) return;
            tun = fd;
            out = new FileOutputStream(fd.getFileDescriptor());
            pool = Executors.newFixedThreadPool(6);
            running = true;
            Thread t = new Thread(() -> loop(fd), "focus-dns");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            stopTun();
        }
    }

    private void loop(ParcelFileDescriptor fd) {
        byte[] buf = new byte[32767];
        try (FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
            while (true) {
                int n = in.read(buf);
                if (n < 0) break;
                if (n > 0) handle(Arrays.copyOf(buf, n));
            }
        } catch (Exception ignored) {
            // интерфейс закрыт
        }
        synchronized (this) {
            if (tun == fd || tun == null) running = false;
        }
    }

    private void handle(byte[] pkt) {
        if (pkt.length < 28 || ((pkt[0] >> 4) & 0x0f) != 4) return; // IPv6 и мусор — отбрасываем
        int ihl = (pkt[0] & 0x0f) * 4;
        int total = Math.min(pkt.length, ((pkt[2] & 0xff) << 8) | (pkt[3] & 0xff));
        if (pkt[9] != 17 || total < ihl + 8) return; // только UDP; остальное (DoH, DoT) отбрасываем
        if (pkt[16] != DNS_IP[0] || pkt[17] != DNS_IP[1] || pkt[18] != DNS_IP[2] || pkt[19] != DNS_IP[3]) return;
        int dport = ((pkt[ihl + 2] & 0xff) << 8) | (pkt[ihl + 3] & 0xff);
        if (dport != 53) return;
        int sport = ((pkt[ihl] & 0xff) << 8) | (pkt[ihl + 1] & 0xff);
        byte[] src = Arrays.copyOfRange(pkt, 12, 16);
        byte[] q = Arrays.copyOfRange(pkt, ihl + 8, total);
        String name = Dns.qname(q);
        if (name == null) return;
        if (Guard.videoFilterActive(prefs) && Lists.domainBlocked(name)) {
            send(src, sport, Dns.blocked(q));
            return;
        }
        ExecutorService p = pool;
        if (p == null) return;
        try {
            p.execute(() -> forward(src, sport, q));
        } catch (Exception ignored) {}
    }

    private void forward(byte[] src, int sport, byte[] q) {
        try (DatagramSocket s = new DatagramSocket()) {
            protect(s);
            s.setSoTimeout(2000);
            for (String up : UPSTREAM) {
                try {
                    s.send(new DatagramPacket(q, q.length, InetAddress.getByName(up), 53));
                    byte[] r = new byte[4096];
                    DatagramPacket dp = new DatagramPacket(r, r.length);
                    s.receive(dp);
                    send(src, sport, Arrays.copyOf(r, dp.getLength()));
                    return;
                } catch (SocketTimeoutException ignored) {
                    // пробуем следующий сервер
                }
            }
        } catch (Exception ignored) {}
    }

    private synchronized void send(byte[] dst, int dport, byte[] data) {
        if (out == null) return;
        try {
            out.write(Dns.udp4(DNS_IP, dst, 53, dport, data));
        } catch (Exception ignored) {}
    }

    private synchronized void stopTun() {
        running = false;
        if (pool != null) pool.shutdownNow();
        pool = null;
        try {
            if (tun != null) tun.close();
        } catch (Exception ignored) {}
        tun = null;
        out = null;
    }

    @Override
    public void onRevoke() {
        stopTun();
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopTun();
        if (watch != null) watch.shutdownNow();
        watch = null;
        super.onDestroy();
    }
}
