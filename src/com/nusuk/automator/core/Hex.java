package com.nusuk.automator.core;

import java.security.MessageDigest;

/** Lower-case hex and SHA-256 helpers. */
public final class Hex {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {}

    public static String of(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = DIGITS[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = DIGITS[bytes[i] & 0xF];
        }
        return new String(out);
    }

    public static String sha256(byte[] data) {
        try {
            return of(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
