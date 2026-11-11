package com.nusuk.automator.notices;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.nusuk.automator.R;
import com.nusuk.automator.core.Access;

/**
 * Announcements sent from the admin page (/admin -> Notifications), shown as system notifications.
 * No Firebase: the phone asks /api/app/notices every ~15 min (JobScheduler, survives reboots) and
 * whenever the app opens. The server answers with notices newer than the last id seen.
 * The very first check after install only records the latest id, so old announcements never pop up.
 */
public final class Notices {
    private static final String TAG = "Notices";
    static final String CHANNEL = "announcements";
    private static final int JOB_ID = 4201;
    private static final long PERIOD_MS = 15 * 60_000L;      // JobScheduler minimum
    private static final long MIN_GAP_MS = 60_000L;          // app open: at most one check a minute
    private static final String PREFS = "notices";

    private static final int HISTORY_MAX = 60;

    /** Called (from a background thread) after a check stored new notices; screens refresh themselves. */
    public static volatile Runnable onChange;

    private static final ExecutorService exec = Executors.newSingleThreadExecutor();

    private Notices() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Registers the periodic background check (no-op when already scheduled). */
    public static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, NoticeJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build();
        int r = js.schedule(job);
        Log.i(TAG, "Background check scheduled: " + (r == JobScheduler.RESULT_SUCCESS));
    }

    /** App open: check now (throttled), off the main thread. */
    public static void checkSoon(Context c) {
        Context app = c.getApplicationContext();
        SharedPreferences p = prefs(app);
        long now = System.currentTimeMillis();
        if (now - p.getLong("lastCheck", 0) < MIN_GAP_MS) return;
        exec.execute(() -> check(app));
    }

    /** Fetches and shows new notices. Blocking: call off the main thread. Returns false on failure. */
    static boolean check(Context c) {
        SharedPreferences p = prefs(c);
        long since = p.getLong("since", -1);
        try {
            JSONObject r = post(c, since);
            long latest = r.optLong("latest", 0);
            JSONArray list = r.optJSONArray("notices");
            long seen = since;
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject n = list.getJSONObject(i);
                    store(c, n);
                    show(c, n);
                    seen = Math.max(seen, n.optLong("id"));
                }
            }
            // A full page (5) may mean more are waiting: continue from the last shown id next time.
            long next = list != null && list.length() >= 5 ? seen : Math.max(seen, latest);
            p.edit().putLong("since", Math.max(next, 0)).putLong("lastCheck", System.currentTimeMillis()).apply();
            Runnable cb = onChange;
            if (cb != null && seen > since) cb.run();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Check failed: " + e);
            return false;
        }
    }

    /** Forces a check now (Notifications screen opened / pulled), still off the main thread. */
    static void refreshNow(Context c) {
        Context app = c.getApplicationContext();
        exec.execute(() -> check(app));
    }

    // ===== History: every notice this phone received, newest first (kept on the phone) =====

    private static synchronized void store(Context c, JSONObject n) {
        try {
            SharedPreferences p = prefs(c);
            JSONArray old = new JSONArray(p.getString("history", "[]"));
            long id = n.optLong("id");
            JSONArray out = new JSONArray();
            out.put(new JSONObject().put("id", id).put("title", n.optString("title", "Nusuk Auto"))
                    .put("body", n.optString("body", "")).put("url", n.optString("url", ""))
                    .put("at", n.optLong("created_at", System.currentTimeMillis() / 1000)));
            for (int i = 0; i < old.length() && out.length() < HISTORY_MAX; i++) {
                if (old.getJSONObject(i).optLong("id") != id) out.put(old.getJSONObject(i));
            }
            p.edit().putString("history", out.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "History not saved: " + e);
        }
    }

    /** Saved notices, newest first. */
    static synchronized JSONArray history(Context c) {
        try {
            return new JSONArray(prefs(c).getString("history", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static int unreadCount(Context c) {
        long read = prefs(c).getLong("readUpTo", 0);
        JSONArray h = history(c);
        int n = 0;
        for (int i = 0; i < h.length(); i++) if (h.optJSONObject(i).optLong("id") > read) n++;
        return n;
    }

    /** Marks everything saved so far as read. */
    static void markAllRead(Context c) {
        JSONArray h = history(c);
        long max = h.length() > 0 ? h.optJSONObject(0).optLong("id") : 0;
        for (int i = 0; i < h.length(); i++) max = Math.max(max, h.optJSONObject(i).optLong("id"));
        prefs(c).edit().putLong("readUpTo", max).apply();
    }

    static synchronized void clearHistory(Context c) {
        markAllRead(c);
        prefs(c).edit().putString("history", "[]").apply();
    }

    public static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Announcements", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("News and updates from Nusuk Auto");
        nm.createNotificationChannel(ch);
    }

    private static void show(Context c, JSONObject n) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        ensureChannel(c);
        String title = n.optString("title", "Nusuk Auto");
        String body = n.optString("body", "");
        String url = n.optString("url", "");
        int id = (int) n.optLong("id");

        Intent tap;
        if (url.startsWith("https://")) {
            tap = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        } else {
            tap = new Intent(c, NotificationsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }
        PendingIntent pi = PendingIntent.getActivity(c, id, tap,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setColor(c.getColor(R.color.primary))
                .setContentTitle(title)
                .setAutoCancel(true)
                .setContentIntent(pi);
        if (!body.isEmpty()) {
            b.setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body));
        }
        try {
            nm.notify("notice", id, b.build());
            Log.i(TAG, "Shown notice " + id + ": " + title);
        } catch (SecurityException e) {
            Log.w(TAG, "Notifications not allowed: " + e);
        }
    }

    private static JSONObject post(Context c, long since) throws Exception {
        byte[] bytes = new JSONObject().put("since", since).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(c.getString(R.string.api_base) + "/api/app/notices").openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-Device-Id", Access.deviceId(c));
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
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
