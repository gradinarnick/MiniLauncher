package com.kolya.minilauncher;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/** Разбор DNS-запросов и сборка IPv4/UDP-пакетов для фильтра сети. */
final class Dns {
    private Dns() {}

    /** Имя из первого вопроса DNS-запроса или null. */
    static String qname(byte[] q) {
        if (q.length < 17) return null;
        int qd = ((q[4] & 0xff) << 8) | (q[5] & 0xff);
        if (qd < 1) return null;
        StringBuilder sb = new StringBuilder();
        int i = 12;
        while (i < q.length) {
            int len = q[i] & 0xff;
            if (len == 0) return sb.toString().toLowerCase(Locale.ROOT);
            if ((len & 0xC0) != 0 || i + 1 + len > q.length) return null;
            if (sb.length() > 0) sb.append('.');
            for (int k = 0; k < len; k++) sb.append((char) (q[i + 1 + k] & 0xff));
            i += 1 + len;
        }
        return null;
    }

    private static int questionEnd(byte[] q) {
        int i = 12;
        while (i < q.length && q[i] != 0) i += 1 + (q[i] & 0xff);
        return Math.min(q.length, i + 1 + 4);
    }

    /** Ответ «адрес 0.0.0.0 / ::» — сайт не откроется. */
    static byte[] blocked(byte[] q) {
        int end = questionEnd(q);
        int qtype = end >= 16 ? (((q[end - 4] & 0xff) << 8) | (q[end - 3] & 0xff)) : 0;
        int rdlen = qtype == 1 ? 4 : qtype == 28 ? 16 : 0;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(q, 0, 2);               // id
        o.write(0x81); o.write(0x80);   // ответ, рекурсия
        o.write(0); o.write(1);         // 1 вопрос
        o.write(0); o.write(rdlen > 0 ? 1 : 0);
        o.write(0); o.write(0); o.write(0); o.write(0);
        o.write(q, 12, end - 12);
        if (rdlen > 0) {
            o.write(0xC0); o.write(0x0C);
            o.write(0); o.write(qtype);
            o.write(0); o.write(1);
            o.write(0); o.write(0); o.write(0); o.write(60);
            o.write(0); o.write(rdlen);
            for (int k = 0; k < rdlen; k++) o.write(0);
        }
        return o.toByteArray();
    }

    static byte[] udp4(byte[] src, byte[] dst, int sport, int dport, byte[] data) {
        int len = 28 + data.length;
        byte[] p = new byte[len];
        p[0] = 0x45;
        p[2] = (byte) (len >> 8);
        p[3] = (byte) len;
        p[6] = 0x40;
        p[8] = 64;
        p[9] = 17;
        System.arraycopy(src, 0, p, 12, 4);
        System.arraycopy(dst, 0, p, 16, 4);
        int cs = checksum(p, 0, 20);
        p[10] = (byte) (cs >> 8);
        p[11] = (byte) cs;
        p[20] = (byte) (sport >> 8);
        p[21] = (byte) sport;
        p[22] = (byte) (dport >> 8);
        p[23] = (byte) dport;
        int ul = 8 + data.length;
        p[24] = (byte) (ul >> 8);
        p[25] = (byte) ul;
        // контрольная сумма UDP = 0 (для IPv4 допустимо)
        System.arraycopy(data, 0, p, 28, data.length);
        return p;
    }

    private static int checksum(byte[] b, int off, int len) {
        int s = 0;
        for (int i = off; i < off + len; i += 2) {
            s += ((b[i] & 0xff) << 8) | (i + 1 < off + len ? (b[i + 1] & 0xff) : 0);
        }
        while ((s >> 16) != 0) s = (s & 0xffff) + (s >> 16);
        return ~s & 0xffff;
    }
}
