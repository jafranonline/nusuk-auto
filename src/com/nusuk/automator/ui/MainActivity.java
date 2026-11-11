package com.nusuk.automator.ui;

import android.app.Activity;
import android.app.AlertDialog;
import org.json.JSONObject;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import android.text.SpannableString;
import android.text.Spanned;
import android.widget.Toast;

import com.nusuk.automator.R;
import com.nusuk.automator.automation.AutomationService;
import com.nusuk.automator.codes.AddKeysActivity;
import com.nusuk.automator.codes.CodeDownloader;
import com.nusuk.automator.codes.KeyRepository;
import com.nusuk.automator.core.L;
import com.nusuk.automator.core.Links;
import com.nusuk.automator.notices.Notices;
import com.nusuk.automator.notices.NotificationsActivity;
import com.nusuk.automator.overlay.OverlayService;
import com.nusuk.automator.quiz.QuizSolver;
import com.nusuk.automator.quiz.QuizSync;
import com.nusuk.automator.stats.Stats;
import com.nusuk.automator.stats.StatsActivity;
import com.nusuk.automator.update.UpdateChecker;

public class MainActivity extends Activity implements KeyRepository.ChangeListener {

    private static final String NUSUK_PACKAGE = "com.moh.nusukapp";

    private View permBanner;
    private View rowAccessibility;
    private TextView tvAccessibilityLabel, btnEnableAccessibility;
    private View rowOverlay;
    private View rowNusuk;
    private View runningBanner;
    private TextView tvCodesStat;
    private TextView tvQuizStat;
    private TextView tvQuizReset;
    private JSONObject quizStatus;      // last /api/quiz/status reply (null = unknown)
    private AlertDialog limitDialog;
    private TextView tvAppVersion, tvBellBadge;
    private String langAtCreate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        L.view(this, findViewById(android.R.id.content));
        langAtCreate = L.lang(this);
        setupSplash();

        permBanner = findViewById(R.id.permBanner);
        rowAccessibility = findViewById(R.id.rowAccessibility);
        tvAccessibilityLabel = findViewById(R.id.tvAccessibilityLabel);
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility);
        rowOverlay = findViewById(R.id.rowOverlay);
        rowNusuk = findViewById(R.id.rowNusuk);
        runningBanner = findViewById(R.id.runningBanner);
        findViewById(R.id.btnStopRun).setOnClickListener(v -> {
            AutomationService s = AutomationService.getInstance();
            if (s != null) s.stopAutomation("Stopped by user");
            updateRunning();
            Toast.makeText(this, L.t(this, "Stopped"), Toast.LENGTH_SHORT).show();
        });
        tvCodesStat = findViewById(R.id.tvCodesStat);
        tvQuizStat = findViewById(R.id.tvQuizStat);
        tvQuizReset = findViewById(R.id.tvQuizReset);

        // Edge-to-edge (Android 15+): keep hero clear of status bar, content clear of nav bar
        View hero = findViewById(R.id.hero);
        View content = findViewById(R.id.footer);
        int heroTop = hero.getPaddingTop();
        int contentBottom = content.getPaddingBottom();
        findViewById(R.id.root).setOnApplyWindowInsetsListener((v, insets) -> {
            hero.setPadding(hero.getPaddingLeft(), heroTop + insets.getSystemWindowInsetTop(),
                    hero.getPaddingRight(), hero.getPaddingBottom());
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                    content.getPaddingRight(), contentBottom + insets.getSystemWindowInsetBottom());
            return insets;
        });

        findViewById(R.id.statsStrip).setOnClickListener(v -> startActivity(new Intent(this, StatsActivity.class)));
        tvAppVersion = findViewById(R.id.tvAppVersion);
        renderVersionBadge(null);
        tvAppVersion.setOnClickListener(v -> UpdateChecker.manualCheck(this, this::renderVersionBadge));

        findViewById(R.id.btnEnableAccessibility).setOnClickListener(v -> openAccessibilitySettings());
        findViewById(R.id.btnEnableOverlay).setOnClickListener(v -> openOverlaySettings());
        findViewById(R.id.btnInstallNusuk).setOnClickListener(v -> openNusukStore());

        findViewById(R.id.cardAddCodes).setOnClickListener(v -> launch("CODES"));
        findViewById(R.id.cardPlayQuiz).setOnClickListener(v -> onPlayQuiz());

        findViewById(R.id.btnHelp).setOnClickListener(v -> startActivity(new Intent(this, TutorialActivity.class).putExtra(TutorialActivity.EXTRA_PATH, "/help")));
        findViewById(R.id.btnBell).setOnClickListener(v -> startActivity(new Intent(this, NotificationsActivity.class)));
        tvBellBadge = findViewById(R.id.tvBellBadge);
        findViewById(R.id.btnCredit).setOnClickListener(v -> openFacebook(groupUrl()));
        findViewById(R.id.btnAddKeys).setOnClickListener(v -> startActivity(new Intent(this, AddKeysActivity.class)));
        findViewById(R.id.btnShare).setOnClickListener(v -> Links.share(this));
        setupDevCredit();
        findViewById(R.id.cardFitan).setOnClickListener(v -> openUrl(Links.FITAN_URL));

        KeyRepository.getInstance(this).registerListener(this);

        Notices.ensureChannel(this);
        Notices.schedule(this);
        askNotificationPermission();
    }

    /** Android 13+: announcements need the notification permission. Asked once; Android remembers a "no". */
    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        android.content.SharedPreferences p = getSharedPreferences("notices", MODE_PRIVATE);
        if (p.getBoolean("asked", false)) return;
        p.edit().putBoolean("asked", true).apply();
        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 41);
    }

    @Override
    protected void onPause() {
        super.onPause();
        OverlayService.setAppOpen(false);
        rowAccessibility.removeCallbacks(recheckPermissions);
        Notices.onChange = null;
        CodeDownloader.onProgress = null;
    }

    /** Compact progress while the code list downloads; a retry line if it failed and there are no codes. */
    private void updateCodesBanner() {
        View banner = findViewById(R.id.codesBanner);
        if (banner == null) return;
        int have = KeyRepository.getInstance(this).getTotalCount();
        int total = Math.max(CodeDownloader.serverTotal, have);
        TextView status = findViewById(R.id.tvCodesStatus), pct = findViewById(R.id.tvCodesPct);
        android.widget.ProgressBar bar = findViewById(R.id.codesBar);
        if (CodeDownloader.running && (have == 0 || have < total)) {
            banner.setVisibility(View.VISIBLE);
            banner.setOnClickListener(null);
            status.setText(L.t(this, have == 0 ? "Downloading codes\u2026" : "Updating codes\u2026"));
            bar.setVisibility(View.VISIBLE);
            bar.setProgress(total > 0 ? (int) (have * 1000L / total) : 0);
            pct.setText(total > 0 ? L.t(this, String.format(java.util.Locale.US, "%,d / %,d", have, total)) : "");
        } else if (CodeDownloader.failed && have == 0) {
            banner.setVisibility(View.VISIBLE);
            status.setText(L.t(this, "Couldn\u2019t download codes. Tap to retry"));
            pct.setText("");
            bar.setVisibility(View.GONE);
            banner.setOnClickListener(v -> CodeDownloader.start(this, true));
        } else {
            banner.setVisibility(View.GONE);
        }
    }

    /** Red count on the bell: announcements not opened yet. */
    private void updateBell() {
        int n = Notices.unreadCount(this);
        tvBellBadge.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        tvBellBadge.setText(L.t(this, n > 9 ? "9+" : String.valueOf(n)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        OverlayService.setAppOpen(true);
        if (langAtCreate != null && !langAtCreate.equals(L.lang(this))) { recreate(); return; } // language changed in Settings
        updateBell();
        Notices.onChange = () -> runOnUiThread(this::updateBell);
        CodeDownloader.onProgress = () -> runOnUiThread(this::updateCodesBanner);
        CodeDownloader.start(this, false); // first open: fetch the code list; later: only newer keys
        updateCodesBanner();
        staleChecks = 0;
        QuizSync.syncSoon(this); // shared quiz answers onto the phone
        checkPermissions();
        updateStats();
        updateRunning();
        if (System.currentTimeMillis() - lastStatusFetch > STATUS_MIN_GAP_MS) refreshQuizStatus(null); // not on every resume: saves server calls
        UpdateChecker.autoCheck(this, this::renderVersionBadge);
        Notices.checkSoon(this);
    }

    /** "Version 1.1", or "Update available: Version 1.2" in gold when a newer version is out (tap = check now). */
    private void renderVersionBadge(UpdateChecker.Info newer) {
        if (tvAppVersion == null) return;
        if (newer != null) {
            tvAppVersion.setText(L.t(this, "Update available: Version " + newer.versionName));
            tvAppVersion.setTextColor(0xFFB45309);
            return;
        }
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        tvAppVersion.setText(L.t(this, "Version " + version));
        tvAppVersion.setTextColor(getColor(R.color.text_secondary));
    }

    // ===== Play Quiz access =====

    private static final long STATUS_MIN_GAP_MS = 5 * 60_000;
    private static long lastStatusFetch = 0;

    private void refreshQuizStatus(Runnable then) {
        lastStatusFetch = System.currentTimeMillis();
        QuizSolver.fetchStatus(this, new QuizSolver.JsonCallback() {
            @Override
            public void onResult(JSONObject json) {
                quizStatus = json;
                Links.saveGroupUrl(MainActivity.this, json.optString("groupUrl", ""));
                showResetTime(json);
                if (then != null) then.run();
            }

            @Override
            public void onFailure(String code, String message) {
                if (isFinishing()) return;
                quizStatus = null;
                if (then != null) {
                    if ("NETWORK".equals(code)) Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    else showLimitDialog(message);
                }
            }
        });
    }

    private void onPlayQuiz() {
        if (quizStatus == null) {
            refreshQuizStatus(this::decidePlayQuiz);
        } else {
            decidePlayQuiz();
            refreshQuizStatus(null); // keep the count fresh
        }
    }

    /** The server's daily reset time (11:00 PM Bangladesh time), shown in this phone's time zone.
     *  The server sends it only when this phone has a daily limit; otherwise the line is hidden. */
    private void showResetTime(JSONObject st) {
        if (isFinishing()) return;
        long at = st.optLong("resetAt", 0);
        // Only once today's limit is used up (the server says so with a TRIAL_ENDED denial), not while questions are left.
        JSONObject denied = st.optJSONObject("denied");
        boolean limitReached = denied != null && "TRIAL_ENDED".equals(denied.optString("code"));
        tvQuizReset.setVisibility(at > 0 && limitReached ? View.VISIBLE : View.GONE);
        if (at <= 0 || !limitReached) return;
        java.util.Date d = new java.util.Date(at * 1000);
        String time = android.text.format.DateFormat.getTimeFormat(this).format(d);
        boolean bdTime = java.util.TimeZone.getDefault().getOffset(d.getTime()) == 6 * 3600_000;
        tvQuizReset.setText(L.t(this, "Daily limit resets every day at " + time + (bdTime ? "" : " (your time)")));
    }

    /** Every limit (the admin's global Play Quiz settings, pause) is decided by the server: the app
     *  only shows the server's own answer. `denied` = {code, message} when this phone can't play now. */
    private void decidePlayQuiz() {
        JSONObject st = quizStatus;
        if (st == null) return;
        JSONObject denied = st.optJSONObject("denied");
        if (denied == null) {
            launch("QUIZ");
            return;
        }
        String code = denied.optString("code"), message = denied.optString("message", "Play Quiz isn't available right now.");
        boolean waitItOut = "QUIZ_OFF".equals(code); // paused by the admin
        if (waitItOut) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        else showLimitDialog(message);
    }

    /** A Play Quiz limit was reached: show the server's message, with a link to the group. */
    private void showLimitDialog(String message) {
        if (isFinishing()) return;
        if (limitDialog != null && limitDialog.isShowing()) limitDialog.dismiss();
        String group = groupUrl();
        limitDialog = new AlertDialog.Builder(this)
                .setTitle(L.t(this, "Play Quiz"))
                .setMessage(message != null && !message.isEmpty() ? message : L.t(this, "Play Quiz isn't available right now."))
                .setPositiveButton(L.t(this, "OK"), null)
                .setNeutralButton(L.t(this, "Facebook group"), (dlg, which) -> openFacebook(group))
                .show();
    }

    private void updateRunning() {
        AutomationService s = AutomationService.getInstance();
        boolean running = s != null && s.isRunning();
        runningBanner.setVisibility(running ? View.VISIBLE : View.GONE);
        if (running) {
            ((TextView) findViewById(R.id.tvRunningTitle)).setText(
                    L.t(this, s.getCurrentMode() == AutomationService.AutomationMode.QUIZ ? "Playing quiz" : "Adding codes"));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        KeyRepository.getInstance(this).unregisterListener(this);
    }

    private void launch(String mode) {
        if ("CODES".equals(mode) && !KeyRepository.getInstance(this).isLoaded()) {
            Toast.makeText(this, L.t(this, "Loading codes\u2026"), Toast.LENGTH_SHORT).show();
            return;
        }
        if ("CODES".equals(mode) && KeyRepository.getInstance(this).getTotalCount() == 0) {
            Toast.makeText(this, L.t(this, CodeDownloader.running ? "Codes are still downloading\u2026" : "No codes yet. Connect to the internet and tap the banner to retry."), Toast.LENGTH_SHORT).show();
            CodeDownloader.start(this, false);
            return;
        }
        if (!isNusukInstalled()) {
            Toast.makeText(this, L.t(this, "Install the Nusuk app first"), Toast.LENGTH_SHORT).show();
            openNusukStore();
            return;
        }
        if (!AutomationService.isServiceRunning()) {
            Toast.makeText(this, isAccessibilitySwitchedOn()
                    ? L.t(this, "Android stopped Nusuk Auto. In Accessibility, turn it off and on again.")
                    : L.t(this, "Enable the accessibility service first"), Toast.LENGTH_LONG).show();
            openAccessibilitySettings();
            return;
        }
        if (!isOverlayGranted()) {
            Toast.makeText(this, L.t(this, "Allow the floating overlay first"), Toast.LENGTH_SHORT).show();
            openOverlaySettings();
            return;
        }

        // Start overlay with the chosen mode preselected
        Intent overlayIntent = new Intent(this, OverlayService.class);
        overlayIntent.setAction("SET_MODE");
        overlayIntent.putExtra("mode", mode);
        startService(overlayIntent);

        // Launch Nusuk App
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage("com.moh.nusukapp");
        if (launchIntent == null) {
            launchIntent = new Intent();
            launchIntent.setClassName("com.moh.nusukapp", "com.moh.nusukapp.activity.MainActivity");
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(launchIntent);
            finish();
        } catch (Exception e) {
            Log.e("MainActivity", "Failed to launch Nusuk", e);
            Toast.makeText(this, L.t(this, "Could not launch Nusuk app: " + e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private int staleChecks = 0;
    private final Runnable recheckPermissions = () -> { if (!isFinishing()) checkPermissions(); };

    private void checkPermissions() {
        // The Settings switch can stay on while Android has stopped the service (after a crash,
        // an app update, or a battery saver killing it): only a connected service counts.
        boolean accOk = AutomationService.isServiceRunning();
        boolean accStale = !accOk && isAccessibilitySwitchedOn();
        tvAccessibilityLabel.setText(L.t(this, accStale ? "Accessibility stopped: turn it off and on" : "Accessibility service"));
        btnEnableAccessibility.setText(L.t(this, accStale ? "Fix" : "Enable"));
        // Android binds the service a moment after the app starts (or after an update):
        // look again shortly before calling it stopped for real.
        // Meanwhile the row stays hidden, so it doesn't flash on every start.
        rowAccessibility.removeCallbacks(recheckPermissions);
        if (accStale && staleChecks < 4) {
            staleChecks++;
            rowAccessibility.postDelayed(recheckPermissions, 750);
            accOk = true;
        }
        boolean overlayOk = isOverlayGranted();
        boolean nusukOk = isNusukInstalled();
        rowNusuk.setVisibility(nusukOk ? View.GONE : View.VISIBLE);
        rowAccessibility.setVisibility(accOk ? View.GONE : View.VISIBLE);
        rowOverlay.setVisibility(overlayOk ? View.GONE : View.VISIBLE);
        permBanner.setVisibility(accOk && overlayOk && nusukOk ? View.GONE : View.VISIBLE);
    }

    private void updateStats() {
        tvCodesStat.setText(L.t(this, String.valueOf(Stats.codesToday(this))));
        tvQuizStat.setText(L.t(this, String.valueOf(Stats.quizToday(this))));
    }

    private void openAccessibilitySettings() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    /** The footer credit (name + chevron) opens the Developer page. */
    private void setupDevCredit() {
        findViewById(R.id.btnSettings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnSettingsRow).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnDevRow).setOnClickListener(v -> startActivity(new Intent(this, DeveloperActivity.class)));
    }

    private String groupUrl() {
        return Links.groupUrl(this);
    }

    private void openFacebook(String url) {
        Links.openFacebook(this, url);
    }

    private void openUrl(String url) {
        Links.open(this, url);
    }

    private boolean isNusukInstalled() {
        try {
            getPackageManager().getPackageInfo(NUSUK_PACKAGE, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void openNusukStore() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + NUSUK_PACKAGE)));
        } catch (Exception e) {
            openUrl("https://play.google.com/store/apps/details?id=" + NUSUK_PACKAGE);
        }
    }

    /** Android 12+: fade the splash out as soon as the first frame is ready (no forced wait). */
    private void setupSplash() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        getSplashScreen().setOnExitAnimationListener(splashView -> splashView.animate()
                .alpha(0f)
                .setDuration(260)
                .withEndAction(splashView::remove)
                .start());
    }

    private boolean isOverlayGranted() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    /** The Settings switch only; see checkPermissions for why that isn't enough. */
    private boolean isAccessibilitySwitchedOn() {
        try {
            String enabledServices = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledServices != null && enabledServices.contains(getPackageName())) {
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    @Override
    public void onKeysChanged() {
        runOnUiThread(() -> {
            updateStats();
            updateRunning();
            updateCodesBanner();
        });
    }
}
