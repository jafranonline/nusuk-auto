package com.nusuk.automator.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

import com.nusuk.automator.core.L;

/**
 * Updates the app from inside the app: downloads the new APK (HTTPS), checks it is this app at a
 * higher version, then gives it to Android's installer. Android still asks "Update this app?" once
 * and only accepts an APK signed with the same key, so progress is kept. The first time, Android
 * also needs "Allow from this source" for Nusuk Auto (we open that screen).
 */
final class ApkInstaller {
    private static final String TAG = "ApkInstaller";
    static final String ACTION_RESULT = "com.nusuk.automator.INSTALL_RESULT";
    private static final String FILE_HOST = "https://files-nusuk.minbars.net/";
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile boolean busy = false;

    private ApkInstaller() {}

    static void start(Activity a, UpdateChecker.Info info) {
        if (busy) return;
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a)
                    .setTitle(L.t(a, "Allow installing updates"))
                    .setMessage(L.t(a, "Android needs your OK once so Nusuk Auto can install its own updates. Turn on \"Allow from this source\", then come back and tap Update again."))
                    .setPositiveButton(L.t(a, "Open settings"), (d, w) -> {
                        try {
                            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
                        } catch (Exception e) {
                            a.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                        }
                    })
                    .setNegativeButton(L.t(a, "Cancel"), null)
                    .show();
            return;
        }
        busy = true;

        android.widget.LinearLayout box = new android.widget.LinearLayout(a);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad / 2);
        TextView status = new TextView(a);
        status.setText(L.t(a, "Downloading..."));
        status.setTextSize(17);
        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(true);
        box.addView(status);
        box.addView(bar, new android.widget.LinearLayout.LayoutParams(-1, -2));
        AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle(L.t(a, "Updating") + " (v" + info.versionName + ")")
                .setView(box)
                .setCancelable(false)
                .create();
        dlg.show();

        final Context app = a.getApplicationContext();
        new Thread(() -> {
            String error = null;
            try {
                File apk = download(app, info, pct -> main.post(() -> {
                    bar.setIndeterminate(false);
                    bar.setProgress(pct);
                    status.setText(L.t(a, "Downloading...") + " " + L.t(a, pct + "%"));
                }));
                main.post(() -> { bar.setIndeterminate(true); status.setText(L.t(a, "Installing...")); });
                install(app, apk);
            } catch (Exception e) {
                Log.w(TAG, "Update failed: " + e);
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String err = error;
            main.post(() -> {
                busy = false;
                if (dlg.isShowing()) {
                    try { dlg.dismiss(); } catch (Exception ignored) {}
                }
                if (err != null) {
                    Toast.makeText(app, L.t(app, "Update failed. Try again or download it from the website.") + "\n" + err, Toast.LENGTH_LONG).show();
                }
            });
        }, "apk-update").start();
    }

    private interface Progress { void pct(int p); }

    private static File download(Context app, UpdateChecker.Info info, Progress progress) throws Exception {
        File dir = new File(app.getCacheDir(), "update");
        if (!dir.exists()) dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        File out = new File(dir, "update.apk");

        String[] urls = {FILE_HOST + "nusuk-auto-" + info.versionCode + ".apk", FILE_HOST + "nusuk-auto.apk"};
        Exception last = null;
        for (String u : urls) {
            try {
                fetch(u, out, progress);
                check(app, out, info);
                return out;
            } catch (Exception e) {
                last = e;
                out.delete();
            }
        }
        throw last == null ? new Exception("download failed") : last;
    }

    private static void fetch(String url, File out, Progress progress) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url + "?t=" + System.currentTimeMillis()).openConnection();
        try {
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setUseCaches(false);
            if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
            long total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n, lastPct = -1;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int p = (int) (done * 100 / total);
                        if (p != lastPct) { lastPct = p; progress.pct(p); }
                    }
                }
                if (total > 0 && done != total) throw new Exception("incomplete download");
            }
        } finally {
            conn.disconnect();
        }
    }

    /** The file must be this app, at the announced (higher) version, match the published checksum,
     *  and be signed with the same key as the installed app. */
    private static void check(Context app, File apk, UpdateChecker.Info info) throws Exception {
        android.content.pm.PackageInfo pi = app.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (pi == null || !app.getPackageName().equals(pi.packageName)) throw new Exception("not a Nusuk Auto update file");
        if (pi.versionCode <= UpdateChecker.currentVersionCode(app)) throw new Exception("no newer version in the file");
        if (info.sha256 == null || info.sha256.isEmpty()) throw new Exception("no checksum published");
        checkSigner(app, apk);
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(apk)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            if (!sb.toString().equalsIgnoreCase(info.sha256)) throw new Exception("file check failed");
        }
    }

    /** Same signing certificate(s) as the running app, so only an APK signed by us is ever installed. */
    @SuppressWarnings("deprecation")
    private static void checkSigner(Context app, File apk) throws Exception {
        PackageManager pm = app.getPackageManager();
        android.content.pm.Signature[] mine, theirs;
        if (Build.VERSION.SDK_INT >= 28) {
            android.content.pm.PackageInfo cur = pm.getPackageInfo(app.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            android.content.pm.PackageInfo upd = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
            mine = cur.signingInfo == null ? null : cur.signingInfo.getApkContentsSigners();
            theirs = upd == null || upd.signingInfo == null ? null : upd.signingInfo.getApkContentsSigners();
        } else {
            mine = pm.getPackageInfo(app.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            android.content.pm.PackageInfo upd = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            theirs = upd == null ? null : upd.signatures;
        }
        if (!certDigests(mine).equals(certDigests(theirs)) || certDigests(mine).isEmpty())
            throw new Exception("update is not signed by Nusuk Auto");
    }

    private static java.util.Set<String> certDigests(android.content.pm.Signature[] sigs) throws Exception {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (sigs == null) return out;
        for (android.content.pm.Signature s : sigs) {
            StringBuilder sb = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(s.toByteArray())) sb.append(String.format("%02x", b));
            out.add(sb.toString());
        }
        return out;
    }

    private static void install(Context app, File apk) throws Exception {
        PackageInstaller pi = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(app.getPackageName());
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream os = s.openWrite("update.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                s.fsync(os);
            }
            Intent cb = new Intent(app, InstallReceiver.class).setAction(ACTION_RESULT);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(app, id, cb, flags).getIntentSender());
        }
    }
}
