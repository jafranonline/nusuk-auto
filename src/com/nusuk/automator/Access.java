package com.nusuk.automator;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** Play Quiz identity: a stable per-phone id. */
public final class Access {

    private static final String PREFS = "access";

    private Access() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * sha256 hex of ANDROID_ID (stable for this app on this phone, survives reinstall), so the
     * server never sees the raw id. Falls back to a random id saved on first use.
     */
    public static synchronized String deviceId(Context c) {
        SharedPreferences p = prefs(c);
        if (p.contains("token")) p.edit().remove("token").apply(); // old Access Code token, no longer used
        String cached = p.getString("device", null);
        if (cached != null) return cached;
        String raw = null;
        try {
            raw = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception ignored) {}
        if (raw == null || raw.isEmpty() || "9774d56d682e549c".equals(raw)) raw = UUID.randomUUID().toString();
        String id = sha256Hex("nusuk-quiz:" + raw);
        p.edit().putString("device", id).apply();
        return id;
    }


    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
