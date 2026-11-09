package com.nusuk.automator;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The APK ships without codes: this pulls the list from the server (/api/app/codes) in pages,
 * resuming from the cursor saved with the list. Runs on the first open, and again (cheaply: only
 * keys newer than the cursor) whenever the app is opened after a few hours.
 */
final class CodeDownloader {
    private static final String TAG = "CodeDownloader";
    private static final long RECHECK_MS = 6 * 60 * 60 * 1000L;

    static volatile boolean running = false;
    static volatile boolean failed = false;
    static volatile int serverTotal = 0;
    static volatile Runnable onProgress; // set by the screen that shows progress (any thread)
    private static long lastDone = 0;

    private CodeDownloader() {}

    /** Starts a download if the list is empty, or the last full check is old. `force` skips the age check. */
    static synchronized void start(Context ctx, boolean force) {
        if (running) return;
        Context app = ctx.getApplicationContext();
        KeyRepository repo = KeyRepository.getInstance(app);
        boolean empty = repo.getTotalCount() == 0;
        if (!force && !empty && System.currentTimeMillis() - lastDone < RECHECK_MS) return;
        running = true;
        failed = false;
        changed();
        new Thread(() -> run(app, repo), "code-download").start();
    }

    private static void run(Context ctx, KeyRepository repo) {
        try {
            long after = repo.codesCursor();
            while (true) {
                JSONObject r = post(ctx, after);
                JSONArray arr = r.optJSONArray("codes");
                List<String> page = new ArrayList<>();
                if (arr != null) for (int i = 0; i < arr.length(); i++) page.add(arr.optString(i));
                long last = r.optLong("last", after);
                serverTotal = r.optInt("total", serverTotal);
                repo.appendCodes(page, last);
                after = last;
                changed();
                if (r.optBoolean("done", true) || page.isEmpty()) break;
            }
            lastDone = System.currentTimeMillis();
            Log.i(TAG, "Code list up to date: " + repo.getTotalCount() + " codes");
        } catch (Exception e) {
            Log.w(TAG, "Code download failed", e);
            failed = true;
        } finally {
            running = false;
            changed();
        }
    }

    private static void changed() {
        Runnable r = onProgress;
        if (r != null) r.run();
    }

    private static JSONObject post(Context ctx, long after) throws Exception {
        byte[] bytes = new JSONObject().put("after", after).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(ctx.getString(R.string.api_base) + "/api/app/codes").openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-Device-Id", Access.deviceId(ctx));
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = conn.getOutputStream()) { os.write(bytes); }
            int status = conn.getResponseCode();
            InputStream in = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    char[] buf = new char[16384];
                    int n;
                    while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
                }
            }
            if (status != 200) throw new Exception("HTTP " + status);
            return new JSONObject(sb.toString());
        } finally {
            conn.disconnect();
        }
    }
}
