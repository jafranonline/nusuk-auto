package com.nusuk.automator;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.*;
import android.widget.*;

public class OverlayService extends Service implements NusukAccessibilityService.AutomationListener, KeyRepository.ChangeListener {
    private static final String TAG = "OverlayService";
    private static OverlayService instance;
    private static volatile boolean appOpen = false;

    /** Nusuk Auto's own screens are on top: the floating widget hides there and comes back when they leave. */
    static void setAppOpen(boolean open) {
        appOpen = open;
        OverlayService s = instance;
        if (s != null) s.mainHandler.post(s::applyHidden);
    }

    private void applyHidden() {
        if (overlayView != null) overlayView.setVisibility(appOpen ? View.GONE : View.VISIBLE);
    }

    public static OverlayService getInstance() {
        return instance;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private View overlayView;
    private WindowManager.LayoutParams params;

    private View overlayPanel;
    private View overlayBadge;
    private View overlayHeader;

    private TextView tvProgress;
    private TextView tvSubStats;
    private TextView tvOverlayStatus;
    private TextView tvBadgeCount;

    private Button btnModeCodes;
    private Button btnModeQuiz;
    private Button btnCountMinus;
    private Button btnCountPlus;
    private EditText etCount;
    private Button btnAction;
    private View rowPace, rowMode, rowCount;
    private Button btnPause;
    private View btnClose;
    private boolean runningUi = false;
    private View btnPace;
    private boolean updatingCount = false;
    private boolean moveQueued = false;
    private static final int DEFAULT_BATCH = 100, MAX_BATCH = 9999, STEP = 10;

    private NusukAccessibilityService.AutomationMode selectedMode = NusukAccessibilityService.AutomationMode.CODES;
    private int selectedBatchCount = DEFAULT_BATCH;
    private float initialX, initialY;
    private int initialTouchX, initialTouchY;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // The widget lives only on top of Nusuk: it closes once the user has been in another app for
    // AWAY_IDLE_MS (a bit longer mid-run, since Nusuk can hop to a login app and back). Our own
    // screens don't count as "away"; Nusuk gets START_GRACE_MS to open after the widget starts.
    private static final long AWAY_IDLE_MS = 4_000, AWAY_RUNNING_MS = 30_000, START_GRACE_MS = 15_000;
    private static final long FRONT_POLL_MS = 1_000;
    private long lastNusukSeen;
    private volatile boolean destroyed = false;
    private final Runnable awayCheck = new Runnable() {
        @Override public void run() {
            // Reading windows can block for seconds when Nusuk is busy: never on the UI thread.
            // The next check is scheduled only after this one is done, so checks never pile up.
            new Thread(() -> {
                NusukAccessibilityService s = NusukAccessibilityService.getInstance();
                boolean front = s != null && s.isNusukInFront();
                boolean running = s != null && s.isRunning();
                mainHandler.post(() -> {
                    if (destroyed) return;
                    long now = System.currentTimeMillis();
                    if (front || appOpen) lastNusukSeen = now;
                    long limit = running ? AWAY_RUNNING_MS : AWAY_IDLE_MS;
                    if (now - lastNusukSeen >= limit) { stopSelf(); return; }
                    mainHandler.postDelayed(awayCheck, FRONT_POLL_MS);
                });
            }, "nusuk-away-check").start();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        initOverlayView();
        lastNusukSeen = System.currentTimeMillis() + START_GRACE_MS - AWAY_IDLE_MS; // Nusuk may take a while to open
        mainHandler.postDelayed(awayCheck, FRONT_POLL_MS);
        KeyRepository.getInstance(this).registerListener(this);
        ensureServiceListener();
    }

    public void ensureServiceListener() {
        NusukAccessibilityService service = NusukAccessibilityService.getInstance();
        if (service != null) {
            service.registerListener(this);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureServiceListener();
        if (intent != null) {
            String action = intent.getAction();
            if ("STOP".equals(action)) {
                stopSelf();
                return START_NOT_STICKY;
            } else if ("START_BATCH".equals(action)) {
                int count = intent.getIntExtra("count", selectedBatchCount);
                if (count <= 0) count = DEFAULT_BATCH;
                selectBatch(count);

                String modeStr = intent.getStringExtra("mode");
                if ("QUIZ".equalsIgnoreCase(modeStr)) {
                    selectMode(NusukAccessibilityService.AutomationMode.QUIZ);
                } else if ("CODES".equalsIgnoreCase(modeStr)) {
                    selectMode(NusukAccessibilityService.AutomationMode.CODES);
                }

                NusukAccessibilityService s = NusukAccessibilityService.getInstance();
                if (s != null) {
                    s.registerListener(this);
                    s.startAutomation(count, selectedMode);
                }
            } else if ("SET_MODE".equals(action)) {
                if ("QUIZ".equalsIgnoreCase(intent.getStringExtra("mode"))) {
                    selectMode(NusukAccessibilityService.AutomationMode.QUIZ);
                } else {
                    selectMode(NusukAccessibilityService.AutomationMode.CODES);
                }
                expand();
            } else if ("PAUSE".equals(action)) {
                NusukAccessibilityService s = NusukAccessibilityService.getInstance();
                if (s != null) {
                    s.pauseAutomation();
                }
            }
        }
        updateUiStats();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        destroyed = true;
        mainHandler.removeCallbacks(awayCheck);
        instance = null;
        KeyRepository.getInstance(this).unregisterListener(this);
        NusukAccessibilityService service = NusukAccessibilityService.getInstance();
        if (service != null) {
            service.unregisterListener(this);
        }

        if (windowManager != null && overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                Log.e(TAG, "Error removing view", e);
            }
        }
    }

    private void initOverlayView() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        // The panel has fixed-height rows: a phone's large font size would clip its labels, so it
        // is drawn at the normal font scale (Display size still applies).
        android.content.res.Configuration cfg = new android.content.res.Configuration(getResources().getConfiguration());
        cfg.fontScale = 1f;
        LayoutInflater inflater = LayoutInflater.from(createConfigurationContext(cfg));
        overlayView = inflater.inflate(R.layout.overlay_layout, null);
        L.view(this, overlayView);

        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 40;
        params.y = 140;

        overlayPanel = overlayView.findViewById(R.id.overlayPanel);
        overlayBadge = overlayView.findViewById(R.id.overlayBadge);
        overlayHeader = overlayView.findViewById(R.id.overlayHeader);

        tvProgress = overlayView.findViewById(R.id.tvProgress);
        tvOverlayStatus = overlayView.findViewById(R.id.tvOverlayStatus);
        thinkBar = overlayView.findViewById(R.id.thinkBar);
        tvBadgeCount = overlayView.findViewById(R.id.tvBadgeCount);

        btnModeCodes = overlayView.findViewById(R.id.btnModeCodes);
        btnModeQuiz = overlayView.findViewById(R.id.btnModeQuiz);

        btnCountMinus = overlayView.findViewById(R.id.btnCountMinus);
        btnCountPlus = overlayView.findViewById(R.id.btnCountPlus);
        etCount = overlayView.findViewById(R.id.etCount);
        btnAction = overlayView.findViewById(R.id.btnAction);
        rowPace = overlayView.findViewById(R.id.rowPace);
        rowMode = overlayView.findViewById(R.id.rowMode);
        rowCount = overlayView.findViewById(R.id.rowCount);
        btnPause = overlayView.findViewById(R.id.btnPause);
        btnPause.setOnClickListener(v -> {
            NusukAccessibilityService s = NusukAccessibilityService.getInstance();
            if (s != null && s.isRunning()) s.pauseAutomation(); // toggles; the label follows onProgress
        });
        btnPace = overlayView.findViewById(R.id.btnPace);
        rowPace.setOnClickListener(v -> {
            QuizPace.setOn(this, !QuizPace.isOn(this));
            renderPace();
        });
        renderPace();

        btnModeCodes.setOnClickListener(v -> selectMode(NusukAccessibilityService.AutomationMode.CODES));
        btnModeQuiz.setOnClickListener(v -> selectMode(NusukAccessibilityService.AutomationMode.QUIZ));

        btnCountMinus.setOnClickListener(v -> { commitCountInput(); selectBatch(selectedBatchCount - STEP); });
        btnCountPlus.setOnClickListener(v -> { commitCountInput(); selectBatch(selectedBatchCount + STEP); });

        // The overlay is not focusable (so it never steals focus from the quiz app). Only while
        // the user types in the count box do we make it focusable so the keyboard can open.
        etCount.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) setWindowFocusable(true);
            return false;
        });
        etCount.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.post(() -> {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(v, 0);
                });
            } else {
                commitCountInput();
            }
        });
        etCount.setOnEditorActionListener((v, actionId, event) -> {
            commitCountInput();
            return true;
        });

        btnAction.setOnClickListener(v -> {
            commitCountInput();
            ensureServiceListener();
            NusukAccessibilityService s = NusukAccessibilityService.getInstance();
            if (s == null) {
                // Usually the switch is still on but Android stopped the service: turning it off
                // and on reconnects it. Open the screen so it's one step away.
                Toast.makeText(this, L.t(this, "Nusuk Auto isn't connected. In Accessibility, turn Nusuk Auto off and on again."), Toast.LENGTH_LONG).show();
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {}
                return;
            }

            s.registerListener(this);
            if (s.isRunning()) {
                s.stopAutomation("Stopped by user");
            } else {
                s.startAutomation(selectedBatchCount, selectedMode);
            }
        });

        View btnMin = overlayView.findViewById(R.id.btnMinimize);
        if (btnMin != null) btnMin.setOnClickListener(v -> { commitCountInput(); minimize(); });
        if (overlayBadge != null) overlayBadge.setOnClickListener(v -> expand());
        btnClose = overlayView.findViewById(R.id.btnClose);
        if (btnClose != null) btnClose.setOnClickListener(v -> stopSelf());

        // Draggable touch handling on Header and Badge. A touch that barely moves is a
        // tap: it is passed on as a click (tapping the badge re-opens the full panel).
        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        final boolean[] dragging = {false};
        View.OnTouchListener dragListener = (v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = (int) event.getRawX();
                    initialTouchY = (int) event.getRawY();
                    dragging[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (event.getRawX() - initialTouchX);
                    int dy = (int) (event.getRawY() - initialTouchY);
                    if (!dragging[0] && Math.hypot(dx, dy) < touchSlop) return true;
                    dragging[0] = true;
                    params.x = (int) (initialX + dx);
                    params.y = (int) (initialY + dy);
                    // At most one window update per frame: touch events come faster than the screen draws.
                    if (!moveQueued) {
                        moveQueued = true;
                        overlayView.postOnAnimation(() -> {
                            moveQueued = false;
                            try { windowManager.updateViewLayout(overlayView, params); } catch (Exception ignored) {}
                        });
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging[0]) v.performClick();
                    return true;
            }
            return false;
        };

        if (overlayHeader != null) overlayHeader.setOnTouchListener(dragListener);
        if (overlayBadge != null) overlayBadge.setOnTouchListener(dragListener);

        windowManager.addView(overlayView, params);
        applyHidden();
        selectMode(NusukAccessibilityService.AutomationMode.QUIZ); // Default to Quiz since it's the active focus
        selectBatch(DEFAULT_BATCH);
        updateUiStats();
    }

    private void setWindowFocusable(boolean on) {
        if (params == null || overlayView == null) return;
        boolean has = (params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0;
        if (on == has) return;
        if (on) params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        try {
            windowManager.updateViewLayout(overlayView, params);
        } catch (Exception e) {
            Log.e(TAG, "setWindowFocusable failed", e);
        }
    }

    /** Applies what was typed in the count box, closes the keyboard and returns focus to the app below. */
    private void commitCountInput() {
        if (etCount == null) return;
        int n = selectedBatchCount;
        try {
            String t = etCount.getText().toString().trim();
            if (!t.isEmpty()) n = Integer.parseInt(t);
        } catch (NumberFormatException ignored) {}
        selectBatch(n);
        etCount.clearFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etCount.getWindowToken(), 0);
        setWindowFocusable(false);
    }

    /** While a run is going only the status, progress and Pause/Stop show; the setup rows come back after. */
    private void setRunningUi(boolean running) {
        if (runningUi == running) return;
        runningUi = running;
        int setup = running ? View.GONE : View.VISIBLE;
        if (rowMode != null) rowMode.setVisibility(setup);
        if (rowCount != null) rowCount.setVisibility(setup);
        if (rowPace != null) rowPace.setVisibility(running || selectedMode != NusukAccessibilityService.AutomationMode.QUIZ ? View.GONE : View.VISIBLE);
        if (btnPause != null) btnPause.setVisibility(running ? View.VISIBLE : View.GONE);
        if (btnClose != null) btnClose.setVisibility(running ? View.GONE : View.VISIBLE); // only minimize while running
    }

    private void setActionIdle() {
        setRunningUi(false);
        if (btnAction == null) return;
        btnAction.setText(L.t(this, selectedMode == NusukAccessibilityService.AutomationMode.QUIZ ? "▶  Start quiz" : "▶  Start codes"));
        btnAction.setBackgroundResource(R.drawable.ov_start_btn);
        btnAction.setTextColor(0xFF1E3A5C);
    }

    private void setActionRunning(int done, int target) {
        setRunningUi(true);
        NusukAccessibilityService s = NusukAccessibilityService.getInstance();
        if (btnPause != null) btnPause.setText(L.t(this, s != null && s.isPaused() ? "▶  Resume" : "❚❚  Pause"));
        if (btnAction == null) return;
        btnAction.setText(L.t(this, "■  Stop"));
        btnAction.setBackgroundResource(R.drawable.button_stop);
        btnAction.setTextColor(0xFFFFFFFF);
    }

    private void selectMode(NusukAccessibilityService.AutomationMode mode) {
        selectedMode = mode;
        boolean isCodes = (mode == NusukAccessibilityService.AutomationMode.CODES);
        if (btnModeCodes != null) {
            btnModeCodes.setBackgroundResource(isCodes ? R.drawable.chip_selected : R.drawable.chip_unselected);
            btnModeCodes.setTextColor(isCodes ? 0xFFFFFFFF : 0xFFAFC4DA);
        }
        if (btnModeQuiz != null) {
            btnModeQuiz.setBackgroundResource(!isCodes ? R.drawable.chip_selected : R.drawable.chip_unselected);
            btnModeQuiz.setTextColor(!isCodes ? 0xFFFFFFFF : 0xFFAFC4DA);
        }
        if (rowPace != null) rowPace.setVisibility(isCodes || runningUi ? View.GONE : View.VISIBLE);

        updateUiStats();
    }

    private void renderPace() {
        if (btnPace == null) return;
        boolean on = QuizPace.isOn(this);
        btnPace.setBackgroundResource(on ? R.drawable.ov_switch_on : R.drawable.ov_switch_off);
        rowPace.setContentDescription(L.t(this, "Quiz pace: " + L.t(this, on ? "Normal" : "Fast")));
    }

    private void selectBatch(int count) {
        count = Math.max(1, Math.min(MAX_BATCH, count));
        selectedBatchCount = count;
        if (etCount != null && !L.t(this, String.valueOf(count)).contentEquals(etCount.getText())) {
            updatingCount = true;
            etCount.setText(L.t(this, String.valueOf(count)));
            etCount.setSelection(etCount.getText().length());
            updatingCount = false;
        }

        NusukAccessibilityService s = NusukAccessibilityService.getInstance();
        if (s == null || !s.isRunning()) {
            if (tvProgress != null) tvProgress.setText(L.t(this, "0 / " + selectedBatchCount));
            if (tvBadgeCount != null) tvBadgeCount.setText(L.t(this, "0 / " + selectedBatchCount));
        }
    }

    private int passThroughCount = 0;

    /** True if screen point (x, y) is under the visible overlay. */
    public boolean covers(int x, int y) {
        if (overlayView == null || !overlayView.isShown()) return false;
        int[] loc = new int[2];
        overlayView.getLocationOnScreen(loc);
        return x >= loc[0] && x < loc[0] + overlayView.getWidth()
                && y >= loc[1] && y < loc[1] + overlayView.getHeight();
    }

    /** While true, touches go through the overlay to the app underneath. Reference-counted. */
    public void setPassThrough(boolean on) {
        mainHandler.post(() -> {
            passThroughCount = Math.max(0, passThroughCount + (on ? 1 : -1));
            boolean want = passThroughCount > 0;
            boolean has = (params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0;
            if (want == has || overlayView == null) return;
            if (want) params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try {
                windowManager.updateViewLayout(overlayView, params);
            } catch (Exception e) {
                Log.e(TAG, "setPassThrough failed", e);
            }
        });
    }

    private void minimize() {
        if (overlayPanel != null) overlayPanel.setVisibility(View.GONE);
        if (overlayBadge != null) overlayBadge.setVisibility(View.VISIBLE);
        windowManager.updateViewLayout(overlayView, params);
    }

    private void expand() {
        if (overlayBadge != null) overlayBadge.setVisibility(View.GONE);
        if (overlayPanel != null) overlayPanel.setVisibility(View.VISIBLE);
        windowManager.updateViewLayout(overlayView, params);
        // The badge may have been dragged to an edge; keep the full panel on screen
        overlayView.post(() -> {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int maxX = Math.max(0, dm.widthPixels - overlayView.getWidth());
            int maxY = Math.max(0, dm.heightPixels - overlayView.getHeight());
            int x = Math.min(Math.max(0, params.x), maxX);
            int y = Math.min(Math.max(0, params.y), maxY);
            if (x != params.x || y != params.y) {
                params.x = x;
                params.y = y;
                windowManager.updateViewLayout(overlayView, params);
            }
        });
    }

    private void updateUiStats() {
        mainHandler.post(() -> {
            KeyRepository repo = KeyRepository.getInstance(this);
            NusukAccessibilityService s = NusukAccessibilityService.getInstance();

            if (selectedMode == NusukAccessibilityService.AutomationMode.QUIZ) {
                if (tvSubStats != null && (s == null || !s.isRunning())) {
                    tvSubStats.setText(L.t(this, "✓ 0   ✗ 0"));
                }
                if (s == null || !s.isRunning()) {
                    setActionIdle();
                    if (tvBadgeCount != null) {
                        tvBadgeCount.setText(L.t(this, "0 / " + selectedBatchCount));
                    }
                }
            } else {
                if (tvSubStats != null && (s == null || !s.isRunning())) {
                    int already = repo.getAlreadyCount();
                    tvSubStats.setText(L.t(this, "✓ " + repo.getSuccessCount() + "   ✗ " + repo.getInvalidCount() + (already > 0 ? "   ↺ " + already : "")));
                }
                if (s == null || !s.isRunning()) {
                    setActionIdle();
                    if (tvBadgeCount != null) {
                        tvBadgeCount.setText(L.t(this, "0 / " + selectedBatchCount));
                    }
                }
            }

            if (overlayView != null) overlayView.invalidate();
        });
    }

    @Override
    public void onProgress(String status, String currentCode, int done, int target, int success, int invalid, int remaining) {
        Log.i(TAG, "Overlay onProgress -> " + done + "/" + target + " [" + status + "] ✓=" + success + " ✗=" + invalid);
        mainHandler.post(() -> {
            if (tvProgress != null) tvProgress.setText(L.t(this, done + " / " + target));
            if (tvSubStats != null) tvSubStats.setText(L.t(this, "✓ " + success + "   ✗ " + invalid));
            mainHandler.removeCallbacks(thinkTick);
            if (thinkBar != null) thinkBar.setVisibility(View.GONE);
            if (tvOverlayStatus != null) tvOverlayStatus.setText(L.t(this, status));
            thinkDots = 0;
            if ("Thinking...".equals(status)) thinkTick.run(); // live countdown until the answer is tapped

            if (tvBadgeCount != null) tvBadgeCount.setText(L.t(this, done + " / " + target));
            setActionRunning(done, target);
            if (overlayView != null) overlayView.invalidate();
        });
    }

    private View thinkBar;
    private int thinkDots = 0;

    /** Counts down the "thinking" wait: whole seconds in the status line + a bar along the bottom that empties. */
    private final Runnable thinkTick = new Runnable() {
        @Override public void run() {
            long left = NusukAccessibilityService.thinkDeadline - System.currentTimeMillis();
            if (left <= 0 || tvOverlayStatus == null) {
                if (thinkBar != null) thinkBar.setVisibility(View.GONE);
                return;
            }
            // No seconds: "Thinking" with dots that cycle, and the bar shows how long is left.
            int dots = 1 + (int) ((System.currentTimeMillis() / 400) % 3);
            if (dots != thinkDots) { // text (and so layout) changes only when the dots do, not every tick
                thinkDots = dots;
                String base = L.t(OverlayService.this, "Thinking...").replaceAll("\\.+$", "");
                StringBuilder sb = new StringBuilder(base);
                for (int i = 0; i < dots; i++) sb.append('.');
                tvOverlayStatus.setText(sb);
            }
            if (thinkBar != null) {
                long total = Math.max(1, NusukAccessibilityService.thinkTotalMs);
                thinkBar.setVisibility(View.VISIBLE);
                thinkBar.setPivotX(0);
                thinkBar.setScaleX(Math.min(1f, left / (float) total));
            }
            mainHandler.postDelayed(this, 50);
        }
    };

    @Override
    public void onStopped(String reason) {
        Log.i(TAG, "Overlay onStopped -> " + reason);
        mainHandler.post(() -> {
            mainHandler.removeCallbacks(thinkTick);
            if (thinkBar != null) thinkBar.setVisibility(View.GONE);
            if (tvOverlayStatus != null) tvOverlayStatus.setText(L.t(this, reason));
            setActionIdle();
            // Run finished: the count goes back to the default and the counters start from 0 again.
            selectBatch(DEFAULT_BATCH);
            if (tvProgress != null) tvProgress.setText(L.t(this, "0 / " + selectedBatchCount));
            if (tvBadgeCount != null) tvBadgeCount.setText(L.t(this, "0 / " + selectedBatchCount));
            updateUiStats();
            if (overlayView != null) overlayView.invalidate();
        });
    }

    @Override
    public void onKeysChanged() {
        if (selectedMode == NusukAccessibilityService.AutomationMode.CODES) {
            updateUiStats();
        }
    }
}
