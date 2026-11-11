package com.nusuk.automator.codes;

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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.nusuk.automator.R;
import com.nusuk.automator.core.Access;

/**
 * Backs up this phone's tested codes to the server (/api/app/progress), keyed by the device id
 * (ANDROID_ID hash: the same after uninstall/reinstall as long as the APK keeps its signing key).
 * On a fresh install the first sync brings the old history back, so used codes are never retried.
 * Every reply also carries the current priority keys. Offline is fine: results stay pending
 * (persisted by KeyRepository) and go up on a later try.
 */
public final class CodeSync {
    private static final String TAG = "CodeSync";
    /** Results are batched: one upload at most this long after a new result. */
    static final long PUSH_DELAY_MS = 20_000;
    private static final int CHUNK = 2000;           // codes per request (server takes 2500)
    private static final long RETRY_MIN_MS = 30_000;
    private static final long RETRY_MAX_MS = 10 * 60_000;

    private static final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
    private static ScheduledFuture<?> pending;
    private static long pendingAt = Long.MAX_VALUE;
    private static long retryMs = RETRY_MIN_MS;
    private static final long DEAD_CHECK_MS = 30 * 60_000; // shared dead list: refresh at least this often
    private static volatile long lastDeadCheck = 0;

    private CodeSync() {}

    /** Runs a sync within `delayMs` (an earlier scheduled one is kept). */
    public static synchronized void syncSoon(Context ctx, long delayMs) {
        final Context app = ctx.getApplicationContext();
        long at = System.currentTimeMillis() + delayMs;
        if (pending != null && !pending.isDone() && pendingAt <= at) return;
        if (pending != null) pending.cancel(false);
        pendingAt = at;
        pending = exec.schedule(() -> run(app), delayMs, TimeUnit.MILLISECONDS);
    }

    private static void run(Context ctx) {
        synchronized (CodeSync.class) { pendingAt = Long.MAX_VALUE; }
        KeyRepository repo = KeyRepository.getInstance(ctx);
        try {
            if (repo.needsRestore()) {
                // Push everything we have, in chunks; the last call asks for the full history.
                List<List<String>> all = repo.snapshotTested();
                List<String> s = all.get(0), i = all.get(1), a = all.get(2);
                int total = s.size() + i.size() + a.size(), off = 0;
                do {
                    List<String> cs = new ArrayList<>(), ci = new ArrayList<>(), ca = new ArrayList<>();
                    for (int k = off; k < Math.min(total, off + CHUNK); k++) {
                        if (k < s.size()) cs.add(s.get(k));
                        else if (k < s.size() + a.size()) ca.add(a.get(k - s.size()));
                        else ci.add(i.get(k - s.size() - a.size()));
                    }
                    off += CHUNK;
                    boolean last = off >= total;
                    JSONObject r = post(ctx, repo, cs, ci, ca, last);
                    if (last) repo.onRestored(strings(r.optJSONArray("success")), strings(r.optJSONArray("invalid")), strings(r.optJSONArray("already")));
                } while (off < total);
            }
            while (true) {
                List<List<String>> next = repo.nextPending(CHUNK);
                List<String> s = next.get(0), i = next.get(1), a = next.get(2);
                if (s.isEmpty() && i.isEmpty() && a.isEmpty()) break;
                post(ctx, repo, s, i, a, false);
                repo.onUploaded(s, i, a);
            }
            // Nothing to upload: still fetch dead-list changes (at most one page per call).
            if (System.currentTimeMillis() - lastDeadCheck > DEAD_CHECK_MS) {
                JSONObject r = post(ctx, repo, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), false);
                if (r.optBoolean("deadMore")) syncSoon(ctx, 2000);
            }
            retryMs = RETRY_MIN_MS;
        } catch (Exception e) {
            Log.w(TAG, "Sync failed, retry in " + (retryMs / 1000) + "s: " + e);
            long wait = retryMs;
            retryMs = Math.min(RETRY_MAX_MS, retryMs * 2);
            syncSoon(ctx, wait);
        }
    }

    private static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) for (int k = 0; k < a.length(); k++) out.add(a.optString(k));
        return out;
    }

    /** One sync call. Every reply also carries the priority keys and dead-list changes, applied here. */
    private static JSONObject post(Context ctx, KeyRepository repo, List<String> success, List<String> invalid,
                                   List<String> already, boolean full) throws Exception {
        JSONObject r = postRaw(ctx, success, invalid, already, full, repo.deadSince());
        repo.onPriority(strings(r.optJSONArray("priority")));
        if (r.has("deadAt")) {
            repo.onDead(strings(r.optJSONArray("dead")), strings(r.optJSONArray("alive")), r.optLong("deadAt"));
            lastDeadCheck = System.currentTimeMillis();
        }
        return r;
    }

    private static JSONObject postRaw(Context ctx, List<String> success, List<String> invalid, List<String> already,
                                      boolean full, long deadSince) throws Exception {
        JSONObject body = new JSONObject();
        body.put("success", new JSONArray(success));
        body.put("invalid", new JSONArray(invalid));
        body.put("already", new JSONArray(already));
        body.put("full", full);
        body.put("deadSince", deadSince);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) new URL(ctx.getString(R.string.api_base) + "/api/app/progress").openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-Device-Id", Access.deviceId(ctx));
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20_000);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }
            int status = conn.getResponseCode();
            InputStream in = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
            }
            if (status != 200) throw new Exception("HTTP " + status + " " + sb);
            return new JSONObject(sb.toString());
        } finally {
            conn.disconnect();
        }
    }
}
