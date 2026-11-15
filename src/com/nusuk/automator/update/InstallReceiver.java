package com.nusuk.automator.update;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

import com.nusuk.automator.R;
import com.nusuk.automator.core.L;

/** Gets the installer's answer for ApkInstaller: shows Android's "Update?" screen, or tells why it failed. */
public class InstallReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "app_update";
    private static final int NOTIFY_ID = 7301;

    @Override
    public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = android.os.Build.VERSION.SDK_INT >= 33
                    ? i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : legacyIntent(i);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    c.startActivity(confirm);
                } catch (Exception e) {
                    // Android 10+ blocks starting an Activity from the background: let the user tap it.
                    notifyConfirm(c, confirm);
                }
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_FAILURE_ABORTED) {
            // (aborted = the user tapped Cancel; success = the app is being restarted by Android)
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(c, L.t(c, "Update failed. Try again or download it from the website.") + (msg == null ? "" : "\n" + msg), Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    private static Intent legacyIntent(Intent i) {
        return i.getParcelableExtra(Intent.EXTRA_INTENT);
    }

    private static void notifyConfirm(Context c, Intent confirm) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent pi = PendingIntent.getActivity(c, 0, confirm, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(NOTIFY_ID, new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notify)
                    .setContentTitle(L.t(c, "Update ready"))
                    .setContentText(L.t(c, "Tap to finish installing the update."))
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build());
        } catch (Exception ignored) {}
    }
}
