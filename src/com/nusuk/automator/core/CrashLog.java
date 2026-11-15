package com.nusuk.automator.core;

import android.content.Context;

import com.nusuk.automator.automation.AutomationService;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/** crash.txt in the app's private files dir: crashes and recovered problems, capped at 256 KB. */
public final class CrashLog {
    private static final String FILE = "crash.txt";
    private static final long MAX_BYTES = 256 * 1024;
    private static File dir;
    private static boolean installed;

    private CrashLog() {}

    public static synchronized void install(Context c) {
        if (installed) return;
        installed = true;
        dir = c.getApplicationContext().getFilesDir();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                append("thread=" + thread.getName() + state() + "\n" + sw);
            } catch (Throwable ignored) {}
            if (previous != null) previous.uncaughtException(thread, e);
        });
    }

    /** A problem we recovered from. */
    public static void problem(String what) {
        append(what + state());
    }

    public static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), FILE);
    }

    private static String state() {
        AutomationService s = AutomationService.getInstance();
        return s == null ? "" : " mode=" + s.getCurrentMode() + " running=" + s.isRunning();
    }

    private static synchronized void append(String text) {
        try {
            if (dir == null) return;
            File f = new File(dir, FILE);
            trim(f);
            try (FileWriter w = new FileWriter(f, true)) {
                w.write("==== " + new java.util.Date() + " " + text + "\n");
            }
        } catch (Throwable ignored) {}
    }

    /** Over the cap: keep only the newest quarter. */
    private static void trim(File f) throws Exception {
        if (f.length() <= MAX_BYTES) return;
        byte[] tail = new byte[(int) (MAX_BYTES / 4)];
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.seek(raf.length() - tail.length);
            raf.readFully(tail);
        }
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f, false)) {
            out.write(new String(tail, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));
        }
    }
}
