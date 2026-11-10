package com.nusuk.automator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * New-version check. publish.sh uploads version.json next to the APK:
 *   {"versionCode":6, "versionName":"1.2", "minVersionCode":0, "url":"...", "notes":"..."}
 * Newer versionCode -> "Update available" dialog (Later hides it for a day).
 * This app below minVersionCode -> the dialog can't be dismissed (forced update).
 * "Update" opens the download link in the browser: the same way users installed the app.
 */
final class UpdateChecker {
    private static final String TAG = "UpdateChecker";
    static final String VERSION_URL = "https://files-nusuk.minbars.net/version.json";
    private static final String DEFAULT_URL = "https://nusuk.minbars.net/download";
    private static final long CHECK_EVERY_MS = 6 * 3600_000L;   // automatic checks
    private static final long SNOOZE_MS = 24 * 3600_000L;       // "Later"
    private static final String PREFS = "update";

    private static final ExecutorService exec = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** What the server says, or null when unknown. */
    static final class Info {
        int versionCode, minVersionCode;
        String versionName, url, notes;
    }

    interface Listener {
        /** Called on the main thread with the newer version, or null when up to date. */
        void onChecked(Info newer);
    }

    private UpdateChecker() {}

    static int currentVersionCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** On app open: checks at most every 6h, shows the dialog unless snoozed (never for a forced update). */
    static void autoCheck(Activity a, Listener badge) {
        SharedPreferences p = prefs(a);
        long now = System.currentTimeMillis();
        Info cached = readCached(a);
        if (cached != null && badge != null) badge.onChecked(isNewer(a, cached) ? cached : null);
        if (cached != null && isForced(a, cached)) showDialog(a, cached);  // forced: every open
        if (now - p.getLong("lastCheck", 0) < CHECK_EVERY_MS) return;
        fetch(a, info -> {
            p.edit().putLong("lastCheck", System.currentTimeMillis()).apply();
            if (badge != null) badge.onChecked(info);
            if (info == null) return;
            boolean snoozed = p.getInt("snoozedCode", 0) == info.versionCode
                    && System.currentTimeMillis() - p.getLong("snoozedAt", 0) < SNOOZE_MS;
            if (isForced(a, info) || !snoozed) showDialog(a, info);
        }, false);
    }

    /** User tapped the version badge: check now and always say the result. */
    static void manualCheck(Activity a, Listener badge) {
        Toast.makeText(a, L.t(a, "Checking for updates..."), Toast.LENGTH_SHORT).show();
        fetch(a, info -> {
            if (badge != null) badge.onChecked(info);
            if (info != null) showDialog(a, info);
            else Toast.makeText(a, L.t(a, "You have the latest version"), Toast.LENGTH_SHORT).show();
        }, true);
    }

    private static boolean isNewer(Context c, Info i) {
        return i.versionCode > currentVersionCode(c);
    }

    private static boolean isForced(Context c, Info i) {
        return isNewer(c, i) && currentVersionCode(c) < i.minVersionCode;
    }

    private static Info readCached(Context c) {
        String raw = prefs(c).getString("info", null);
        if (raw == null) return null;
        try {
            return parse(new JSONObject(raw));
        } catch (Exception e) {
            return null;
        }
    }

    private static Info parse(JSONObject j) {
        Info i = new Info();
        i.versionCode = j.optInt("versionCode", 0);
        i.minVersionCode = j.optInt("minVersionCode", 0);
        i.versionName = j.optString("versionName", "");
        i.url = j.optString("url", DEFAULT_URL);
        i.notes = j.optString("notes", "");
        return i;
    }

    /** Fetches version.json off the main thread; `cb` gets the newer version or null. */
    private static void fetch(Activity a, Listener cb, boolean reportErrors) {
        final Context app = a.getApplicationContext();
        exec.execute(() -> {
            Info newer = null;
            String error = null;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(VERSION_URL + "?t=" + System.currentTimeMillis()).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setUseCaches(false);
                try {
                    if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                    }
                    JSONObject j = new JSONObject(sb.toString());
                    prefs(app).edit().putString("info", j.toString()).apply();
                    Info info = parse(j);
                    if (isNewer(app, info)) newer = info;
                } finally {
                    conn.disconnect();
                }
            } catch (Exception e) {
                Log.w(TAG, "Update check failed: " + e);
                error = e.toString();
            }
            final Info result = newer;
            final boolean failed = error != null;
            main.post(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (failed && reportErrors) {
                    Toast.makeText(a, L.t(a, "Could not check for updates. Check your internet."), Toast.LENGTH_LONG).show();
                    return;
                }
                if (!failed) cb.onChecked(result);
            });
        });
    }

    private static AlertDialog shown;

    private static void showDialog(Activity a, Info info) {
        if (a.isFinishing() || a.isDestroyed()) return;
        if (shown != null && shown.isShowing()) return;
        boolean forced = isForced(a, info);
        String msg = (forced
                ? L.t(a, "This version no longer works properly. Please install the new version to continue.")
                : L.t(a, "A new version of Nusuk Auto is ready."))
                + (info.notes.isEmpty() ? "" : "\n\n" + L.t(a, "What's new:") + "\n" + info.notes)
                + "\n\n" + L.t(a, "Your progress is kept when you update.");
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(L.t(a, forced ? "Update required" : "Update available") + " (v" + info.versionName + ")")
                .setMessage(msg)
                .setCancelable(!forced)
                .setPositiveButton(L.t(a, "Update"), (d, w) -> openDownload(a, info));
        if (!forced) {
            b.setNegativeButton(L.t(a, "Later"), (d, w) -> prefs(a).edit()
                    .putInt("snoozedCode", info.versionCode)
                    .putLong("snoozedAt", System.currentTimeMillis()).apply());
        }
        shown = b.show();
    }

    private static void openDownload(Activity a, Info info) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(info.url != null && info.url.startsWith("https://") ? info.url : DEFAULT_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Toast.makeText(a, L.t(a, "When the download finishes, open it and tap Update."), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(a, info.url, Toast.LENGTH_LONG).show();
        }
    }
}
