package com.nusuk.automator;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** App settings: language (English / Bangla) and the pace of the quiz automation (Normal / Fast). */
public class SettingsActivity extends ScreenActivity {
    private static final int BLUE = 0xFF3E7CB1, BLUE_SOFT = 0xFFEAF2F9, GREY_LINE = 0xFFD6D3D1;

    /** One tappable option: a radio mark, a title and an optional description. */
    private final class Choice {
        final LinearLayout row = new LinearLayout(SettingsActivity.this);
        final TextView mark;

        Choice(String title, String desc, Runnable onTap) {
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(px(14), px(14), px(14), px(14));
            row.setClickable(true);
            row.setFocusable(true);

            mark = new TextView(SettingsActivity.this);
            mark.setGravity(Gravity.CENTER);
            mark.setTextSize(13);
            mark.setTextColor(0xFFFFFFFF);
            row.addView(mark, new LinearLayout.LayoutParams(px(24), px(24)));

            LinearLayout col = new LinearLayout(SettingsActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView t = text(title, 16, 0xFF1C1917);
            t.setTypeface(getResources().getFont(R.font.plex_medium));
            col.addView(t);
            if (desc != null) col.addView(text(desc, 13, 0xFF78716C), lp(-2, -2, 3));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
            cp.setMarginStart(px(12));
            row.addView(col, cp);

            row.setOnClickListener(v -> onTap.run());
        }

        void setSelected(boolean on) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(px(12));
            bg.setColor(on ? BLUE_SOFT : 0xFFFFFFFF);
            bg.setStroke(px(on ? 2 : 1), on ? BLUE : GREY_LINE);
            row.setBackground(bg);

            GradientDrawable m = new GradientDrawable();
            m.setShape(GradientDrawable.OVAL);
            if (on) m.setColor(BLUE);
            else m.setStroke(px(2), GREY_LINE);
            mark.setBackground(m);
            mark.setText(on ? "✓" : "");
            row.setSelected(on);
        }
    }

    private Choice optNormal, optFast, optEn, optBn;

    @Override
    protected String screenTitle() {
        return "Settings";
    }

    private TextView updateStatus, updateBtn;

    /** App name, version, what it does, and whether a newer version is out. */
    private void buildAbout() {
        LinearLayout a = card(0);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView logo = icon(R.drawable.ic_logo, 30);
        logo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        logo.setBackgroundResource(R.drawable.avatar_bg);
        logo.setPadding(px(9), px(9), px(9), px(9));
        head.addView(logo, new LinearLayout.LayoutParams(px(52), px(52)));

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(text("Nusuk Auto", 18, 0xFF1C1917));
        String v = "";
        try {
            v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        names.addView(text("Version " + v, 13, 0xFF78716C), lp(-2, -2, 2));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1);
        np.setMarginStart(px(14));
        head.addView(names, np);
        a.addView(head);

        a.addView(text("Adds Umrah codes and plays the Nusuk quiz for you, so you save time.", 14, 0xFF44403C), lp(-1, -2, 14));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        updateStatus = text("", 13, 0xFF15803D);
        row.addView(updateStatus, new LinearLayout.LayoutParams(0, -2, 1));
        updateBtn = pill("Check for updates", 0, false, 0,
                () -> UpdateChecker.manualCheck(this, this::showUpdateStatus));
        updateBtn.setMinHeight(px(36));
        row.addView(updateBtn);
        a.addView(row, lp(-1, -2, 14));

        showUpdateStatus(null);
        UpdateChecker.autoCheck(this, this::showUpdateStatus);
    }

    /** [newer] = the newer version the server announced, or null when this one is the latest (as far as we know). */
    private void showUpdateStatus(UpdateChecker.Info newer) {
        if (isFinishing() || updateStatus == null) return;
        if (newer != null) {
            updateStatus.setText(L.t(this, "New version v" + newer.versionName + " is available"));
            updateStatus.setTextColor(0xFFB45309);
            updateBtn.setText(L.t(this, "Update"));
        } else {
            updateStatus.setText(L.t(this, "You have the latest version"));
            updateStatus.setTextColor(0xFF15803D);
            updateBtn.setText(L.t(this, "Check for updates"));
        }
    }

    @Override
    protected void build() {
        buildAbout();
        label("Language", 22);
        LinearLayout lc = card(8);
        lc.addView(text("Choose the language of the app.", 14, 0xFF44403C));
        LinearLayout langs = new LinearLayout(this);
        langs.setOrientation(LinearLayout.HORIZONTAL);
        optEn = new Choice("English", null, () -> setLang(L.EN));
        optBn = new Choice("বাংলা", null, () -> setLang(L.BN));
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, -2, 1);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, -2, 1);
        b.setMarginStart(px(10));
        langs.addView(optEn.row, a);
        langs.addView(optBn.row, b);
        lc.addView(langs, lp(-1, -2, 14));

        label("Quiz pace", 22);
        LinearLayout c = card(8);
        c.addView(text("Choose how fast the quiz is played.", 14, 0xFF44403C));
        optNormal = new Choice("Normal (recommended)",
                "Takes a few seconds per question and pauses between quizzes, like a real person. Safest for your account.",
                () -> setPace(true));
        optFast = new Choice("Fast",
                "No waiting at all. Quicker, but looks like a bot and your account may get limited.",
                () -> setPace(false));
        c.addView(optNormal.row, lp(-1, -2, 14));
        c.addView(optFast.row, lp(-1, -2, 10));
        label("Share", 22);
        LinearLayout shc = card(8);
        shc.addView(text("Know someone who does Umrah quizzes? Share the app with them.", 14, 0xFF44403C));
        TextView shareBtn = pill("Share the app", 0, false, 0, () -> Links.share(this));
        shareBtn.setMinHeight(px(36));
        shc.addView(shareBtn, lp(-2, -2, 14));

        label("Support", 22);
        LinearLayout sc = card(8);
        sc.addView(text("Something not working? Send a report with your app version, phone model and recent errors.", 14, 0xFF44403C));
        TextView share = pill("Share debug report", 0, false, 0, this::shareReport);
        share.setMinHeight(px(36));
        sc.addView(share, lp(-2, -2, 14));
        render();
    }

    /** Builds a plain-text report (versions, settings, last part of crash.txt) and opens the share sheet. */
    private void shareReport() {
        StringBuilder r = new StringBuilder("Nusuk Auto debug report\n");
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            r.append("App: ").append(pi.versionName).append(" (").append(pi.versionCode).append(")\n");
        } catch (Exception ignored) {}
        r.append("Android: ").append(android.os.Build.VERSION.RELEASE).append(" (API ")
                .append(android.os.Build.VERSION.SDK_INT).append(")\n")
         .append("Device: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append('\n')
         .append("Language: ").append(L.lang(this)).append("  Pace: ").append(QuizPace.isOn(this) ? "normal" : "fast").append('\n');
        String en = android.provider.Settings.Secure.getString(getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        r.append("Accessibility on: ").append(en != null && en.contains(getPackageName())).append('\n');
        java.io.File f = new java.io.File(String.valueOf(getExternalFilesDir(null)), "crash.txt");
        r.append("\n--- recent errors ---\n");
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            long n = Math.min(raf.length(), 6000);
            byte[] b = new byte[(int) n];
            raf.seek(raf.length() - n);
            raf.readFully(b);
            r.append(new String(b, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            r.append("(none)\n");
        }
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Nusuk Auto debug report")
                .putExtra(Intent.EXTRA_TEXT, r.toString());
        startActivity(Intent.createChooser(send, L.t(this, "Share debug report")));
    }

    private void setLang(String code) {
        if (code.equals(L.lang(this))) return;
        L.set(this, code);
        // the floating panel is built once: stop it so it is rebuilt in the new language next time
        startService(new Intent(this, OverlayService.class).setAction("STOP"));
        recreate();
    }

    private void setPace(boolean normal) {
        QuizPace.setOn(this, normal);
        render();
    }

    private void render() {
        boolean normal = QuizPace.isOn(this);
        optNormal.setSelected(normal);
        optFast.setSelected(!normal);
        boolean bn = L.isBn(this);
        optEn.setSelected(!bn);
        optBn.setSelected(bn);
    }
}
