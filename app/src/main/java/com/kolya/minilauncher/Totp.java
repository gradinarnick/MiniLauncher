package com.kolya.minilauncher;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Коды Google Authenticator (RFC 6238: HMAC-SHA1, 30 секунд, 6 цифр). Каждый код — один раз. */
final class Totp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {}

    static String newSecret() {
        byte[] b = new byte[20];
        new SecureRandom().nextBytes(b);
        return base32(b);
    }

    static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buf = 0, bits = 0;
        for (byte x : data) {
            buf = (buf << 8) | (x & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((buf >> (bits - 5)) & 31));
                bits -= 5;
            }
            buf &= (1 << bits) - 1;
        }
        if (bits > 0) sb.append(ALPHABET.charAt((buf << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] unbase32(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (char ch : s.toUpperCase(Locale.ROOT).toCharArray()) {
            int v = ALPHABET.indexOf(ch);
            if (v < 0) continue;
            buf = (buf << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buf >> (bits - 8)) & 0xff);
                bits -= 8;
                buf &= (1 << bits) - 1;
            }
        }
        return out.toByteArray();
    }

    static String code(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(unbase32(secret), "HmacSHA1"));
            byte[] msg = new byte[8];
            for (int i = 7; i >= 0; i--) {
                msg[i] = (byte) (step & 0xff);
                step >>= 8;
            }
            byte[] h = mac.doFinal(msg);
            int o = h[h.length - 1] & 0x0f;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16)
                    | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", bin % 1_000_000);
        } catch (Exception e) {
            return "------";
        }
    }

    static long step() {
        return System.currentTimeMillis() / 1000L / 30L;
    }

    /**
     * Проверяет код. Принимает коды за последние ~2 минуты (пока жена диктует),
     * но каждый — только один раз: использованный шаг запоминается.
     */
    static synchronized boolean verify(Prefs prefs, String secret, String input) {
        if (secret == null || input == null) return false;
        String digits = input.replaceAll("[^0-9]", "");
        if (digits.length() != 6) return false;
        long now = step();
        long last = prefs.lastStep();
        for (long s = now - 4; s <= now + 1; s++) {
            if (s <= last) continue;
            if (code(secret, s).equals(digits)) {
                prefs.setLastStep(s);
                return true;
            }
        }
        return false;
    }

    /** Ключ группами по 4 символа — так проще вводить вручную. */
    static String pretty(String secret) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < secret.length(); i++) {
            if (i > 0 && i % 4 == 0) sb.append(' ');
            sb.append(secret.charAt(i));
        }
        return sb.toString();
    }
}
