package com.nusuk.automator;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NusukAccessibilityService extends AccessibilityService {
    private static final String TAG = "NusukAutoService";
    /** Verbose per-step logging. Off in release: the compiler drops every `if (DEBUG)` block. */
    private static final boolean DEBUG = false;
    private static final String NUSUK_PKG = "com.moh.nusukapp";
    private static NusukAccessibilityService instance;

    public enum AutomationMode {
        CODES,
        QUIZ
    }

    public interface AutomationListener {
        void onProgress(String status, String currentCode, int done, int target, int success, int invalid, int remaining);
        void onStopped(String reason);
    }

    private final List<AutomationListener> listeners = new ArrayList<>();

    // The automation loop runs on its own thread, so reading Nusuk's screen (one IPC call per
    // node) never blocks our overlay's UI thread. All loop state is touched only on this thread;
    // the public start/pause/stop methods post onto it.
    // Not final: if the loop thread ever gets stuck (a call into Nusuk that never returns),
    // Start replaces it instead of silently doing nothing until the app is killed.
    private volatile HandlerThread loopThread = startLoopThread();
    private volatile Handler handler = new Handler(loopThread.getLooper());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile long loopBeat = 0;     // last time the loop thread ran a task
    private static final long LOOP_STUCK_MS = 8000;

    private static HandlerThread startLoopThread() {
        HandlerThread t = new HandlerThread("nusuk-loop");
        t.start();
        return t;
    }

    private volatile boolean isRunning = false;
    private volatile boolean isPaused = false;
    private volatile boolean loopScheduled = false;
    private volatile AutomationMode currentMode = AutomationMode.CODES;
    private int targetBatchCount = 0;
    private int batchProcessed = 0;

    // Codes mode state
    private String currentCode = null;
    private boolean checkSubmitted = false;
    private long checkSubmittedTime = 0;
    private int codeAttempts = 0;          // submissions of currentCode that got no answer
    private int notReadyCount = 0;         // times the typed code didn't land in the field
    private int clearWaits = 0;            // steps spent waiting for an old error line to go away
    private long lastCollectTime = 0;      // when "Add another code" was tapped
    private long restoreWaitSince = 0;     // when this run started waiting for the server history
    private long lastRestoreKick = 0;
    // After a reinstall / "Clear data" the phone has forgotten which codes it already tried.
    // Never type one until the phone's history is back from the server.
    private static final long RESTORE_WAIT_MS = 60_000;
    // The red error line from the PREVIOUS code can still be on screen after a new submit.
    // An error only counts for the current code if it wasn't there at submit time, or it
    // went away and came back.
    private boolean staleErrorAtSubmit = false;
    private boolean sawErrorGone = false;

    // Nusuk can take >1s to answer; never move on while a check may still be in flight.
    private static final long CHECK_TIMEOUT_MS = 4000;
    private static final int MAX_CODE_ATTEMPTS = 2;
    // Let the "add code" screen settle after closing the Congratulations dialog,
    // otherwise the first Check tap can be swallowed by the transition.
    private static final long COLLECT_SETTLE_MS = 600;
    private static final int MAX_CLEAR_WAITS = 6;  // x150ms

    // Quiz mode state
    private int quizCorrectCount = 0;
    private int quizWrongCount = 0;
    private int doorIndex = 0;
    private boolean quizSolvingInProgress = false;
    private long quizStartTime = 0;
    // When the optional pause between quizzes ends (0 = no pause pending). See QuizPace.
    private long nextQuizAt = 0;
    private int quizReq = 0;               // id of the answer request in flight; late replies are ignored
    private String lastQuestion = null;
    private List<String> lastOptions = null;
    private String lastAnswer = null;      // the option we picked for lastQuestion
    private String lastSource = null;      // "local" | "server" | "ai" | "guess"
    private boolean didScrollForQuestion = false;
    // Read before the one scroll that reveals more options: after scrolling, the question can be
    // above the visible area, so the top text would be an OPTION. Merged with the next read.
    private QuizQuestionData preScrollRead = null;
    private long lastForegroundAttempt = 0;
    private volatile long lastAutoTapTime = 0; // last tap/click we made (to tell our side effects from the user's)

    // Finding the challenge from any Nusuk page (see navigateToChallenge)
    private long lostSince = 0;            // when we first saw a page we don't know (0 = on a known page)
    private long lastHomeTap = 0;
    private int navBacks = 0, navScrolls = 0;
    private boolean navRelaunched = false;
    private boolean navThisStep = false;  // set by navigateToChallenge during the current step
    private static final long LOST_GRACE_MS = 800;    // let a page finish loading before acting
    private static final long LOST_GIVE_UP_MS = 45_000;
    private static final int MAX_NAV_BACKS = 4, MAX_NAV_SCROLLS = 6;
    // Longer than QuizSolver's worst case (5s connect + 9s read), so a slow reply is never
    // overtaken by a second request for the same question.
    private static final long QUIZ_TIMEOUT_MS = 16000;

    // Screen size for the few fallback taps that have no node to aim at. The constants below
    // were measured on a 1080x2400 phone and are scaled to the real screen (see sx/sy).
    private int screenW = 1080, screenH = 2400;
    private static final int REF_W = 1080, REF_H = 2400;

    // 4 Doors: found by their titles; these positions (1080x2400) are only the fallback.
    private static final String[] DOOR_TITLES = {"biography", "quran", "makkah", "madinah"};
    private static final int[][] DOORS = {
        {275, 858},  // Door 1: The Prophet’s Biography
        {805, 859},  // Door 2: The Quran
        {275, 1497}, // Door 3: Makkah and the Grand Mosque
        {805, 1497}  // Door 4: Madinah and the Prophet’s Mosque
    };

    private final Runnable loopRunnable = () -> {
        loopScheduled = false;
        loopBeat = System.currentTimeMillis();
        stepLoop();
    };

    /**
     * Every task we post (delayed taps, server replies) runs through this. An exception in a
     * posted task is not caught by the step loop's try/catch and would kill the whole app, which
     * is most likely right after an answer, when Nusuk's screen is changing under us.
     */
    private Runnable safe(Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (Throwable t) {
                Log.e(TAG, "Posted task failed (recovered)", t);
                if (isRunning && !isPaused) reschedule(600);
            }
        };
    }

    /**
     * Any crash of our app is appended to Android/data/com.nusuk.automator/files/crash.txt
     * (readable over USB: adb pull /sdcard/Android/data/com.nusuk.automator/files/crash.txt),
     * then Android's normal crash handling runs.
     */
    private static boolean crashRecorderInstalled = false;

    private void installCrashRecorder() {
        if (crashRecorderInstalled) return;
        crashRecorderInstalled = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        final java.io.File dir = getExternalFilesDir(null);
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            try {
                if (dir != null) {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, "crash.txt"), true)) {
                        w.write("==== " + new java.util.Date() + " thread=" + thread.getName()
                                + " mode=" + currentMode + " running=" + isRunning + "\n" + sw + "\n");
                    }
                }
            } catch (Throwable ignored) {}
            if (previous != null) previous.uncaughtException(thread, e);
        });
    }

    /** Appends a line to crash.txt (same file as crashes) for problems we recovered from. */
    private void recordProblem(String what) {
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) return;
            try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, "crash.txt"), true)) {
                w.write("==== " + new java.util.Date() + " " + what + " mode=" + currentMode + "\n");
            }
        } catch (Throwable ignored) {}
    }

    // The system Back button closes Nusuk when it lands on Nusuk's own screen, so: at most one
    // press per BACK_COOLDOWN_MS, and every press is logged with its reason.
    private static final long BACK_COOLDOWN_MS = 2500;
    private volatile long lastBackTime = 0;

    private boolean pressBack(String why) {
        long now = System.currentTimeMillis();
        if (now - lastBackTime < BACK_COOLDOWN_MS) {
            Log.w(TAG, "Back NOT pressed (cooldown): " + why);
            return false;
        }
        lastBackTime = now;
        Log.w(TAG, "Pressing Back: " + why);
        return performGlobalAction(GLOBAL_ACTION_BACK);
    }

    /** True when a Nusuk window is currently on screen (used to auto-close the floating widget). */
    public boolean isNusukVisible() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) return true; // can't tell: don't close anything
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                CharSequence pkg = r.getPackageName();
                if (pkg != null && NUSUK_PKG.contentEquals(pkg)) return true;
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    public static NusukAccessibilityService getInstance() {
        return instance;
    }

    public static boolean isServiceRunning() {
        return instance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "NusukAccessibilityService connected!");
        installCrashRecorder();
        QuizStore.get(this).warmUp(); // create + seed the answer DB off the main thread
        OverlayService overlay = OverlayService.getInstance();
        if (overlay != null) {
            registerListener(overlay);
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // Android is letting go of this service: it must not look connected any more.
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        handler.post(safe(() -> doStop("Service destroyed")));
        loopThread.quitSafely();
        // A new connection can arrive before the old one is destroyed: never clear it.
        if (instance == this) instance = null;
        Log.i(TAG, "NusukAccessibilityService destroyed!");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isRunning || isPaused) return;

        // Only wake the loop early if nothing is pending (avoids two loops racing)
        if (!loopScheduled) reschedule(100);
    }

    /** The one place the automation loop is scheduled: at most one pending step. */
    private void reschedule(long delayMs) {
        handler.removeCallbacks(loopRunnable);
        loopScheduled = true;
        handler.postDelayed(loopRunnable, Math.max(0, delayMs));
    }

    private void scheduleNextStep(long delayMs) {
        if (!isRunning || isPaused) return;
        reschedule(delayMs);
    }

    @Override
    public void onInterrupt() {
        stopAutomation("Interrupted");
    }

    public synchronized void registerListener(AutomationListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public synchronized void unregisterListener(AutomationListener listener) {
        listeners.remove(listener);
    }

    private List<AutomationListener> listenersSnapshot() {
        OverlayService overlay = OverlayService.getInstance();
        if (overlay != null) registerListener(overlay);
        synchronized (this) {
            return new ArrayList<>(listeners);
        }
    }

    private void notifyProgress(String status) {
        KeyRepository repo = KeyRepository.getInstance(this);
        if (DEBUG) Log.d(TAG, "notifyProgress: " + status);

        int success = (currentMode == AutomationMode.QUIZ) ? quizCorrectCount : repo.getSuccessCount();
        int invalid = (currentMode == AutomationMode.QUIZ) ? quizWrongCount : repo.getInvalidCount();
        int remaining = (currentMode == AutomationMode.QUIZ) ? Math.max(0, targetBatchCount - batchProcessed) : repo.getRemainingCount();
        String currentItem = (currentMode == AutomationMode.QUIZ) ? lastQuestion : currentCode;

        for (AutomationListener l : listenersSnapshot()) {
            try {
                l.onProgress(status, currentItem, batchProcessed, targetBatchCount, success, invalid, remaining);
            } catch (Exception e) {
                Log.e(TAG, "Progress listener error", e);
            }
        }
    }

    private void notifyStopped(String reason) {
        for (AutomationListener l : listenersSnapshot()) {
            try {
                l.onStopped(reason);
            } catch (Exception e) {
                Log.e(TAG, "Stopped listener error", e);
            }
        }
    }

    private boolean onLoopThread() {
        return Looper.myLooper() == handler.getLooper();
    }

    // ---------- public controls (any thread) ----------

    public void startAutomation(int count, AutomationMode mode) {
        final AutomationMode m = mode != null ? mode : AutomationMode.CODES;
        currentMode = m;
        isRunning = true; // visible to the UI right away
        isPaused = false;
        final long asked = System.currentTimeMillis();
        handler.post(safe(() -> {
            loopBeat = System.currentTimeMillis();
            doStart(count, m);
        }));
        // The loop thread should pick this up at once. If it hasn't after LOOP_STUCK_MS it is
        // blocked: start a fresh one (before, Start then did nothing until the app was cleared).
        mainHandler.postDelayed(() -> {
            if (loopBeat >= asked || !isRunning || instance != this) return;
            Log.e(TAG, "Loop thread stuck for " + LOOP_STUCK_MS + "ms -> replacing it");
            recordProblem("loop thread stuck, replaced");
            HandlerThread old = loopThread;
            HandlerThread fresh = startLoopThread();
            handler = new Handler(fresh.getLooper());
            loopThread = fresh;
            loopScheduled = false;
            old.quit(); // drops its queue; a blocked call finishes on its own and the thread ends
            handler.post(safe(() -> {
                loopBeat = System.currentTimeMillis();
                doStart(count, m);
            }));
        }, LOOP_STUCK_MS);
    }

    public void startAutomation(int count) {
        startAutomation(count, currentMode);
    }

    public void pauseAutomation() {
        handler.post(safe(this::doPause));
    }

    public void stopAutomation(String reason) {
        if (onLoopThread()) {
            doStop(reason);
        } else {
            isRunning = false;
            handler.post(safe(() -> doStop(reason)));
        }
    }

    public boolean isRunning() {
        return isRunning;
    }

    public boolean isPaused() {
        return isPaused;
    }

    public AutomationMode getCurrentMode() {
        return currentMode;
    }

    // ---------- controls (loop thread) ----------

    private void doStart(int count, AutomationMode mode) {
        this.targetBatchCount = count > 0 ? count : 10;
        this.currentMode = mode;
        Log.i(TAG, "startAutomation: mode=" + currentMode + ", count=" + targetBatchCount);
        this.batchProcessed = 0;
        this.isRunning = true;
        this.isPaused = false;

        // Fresh shared dead-code list and backup before the first code.
        if (mode == AutomationMode.CODES) CodeSync.syncSoon(this, 0);

        // Reset Codes state
        this.currentCode = null;
        this.checkSubmitted = false;
        this.checkSubmittedTime = 0;
        this.codeAttempts = 0;
        this.notReadyCount = 0;
        this.clearWaits = 0;
        this.lastCollectTime = 0;
        this.restoreWaitSince = 0;
        this.lastRestoreKick = 0;

        resetNavigation();

        // Reset Quiz state
        this.quizCorrectCount = 0;
        this.quizWrongCount = 0;
        this.doorIndex = 0;
        this.quizSolvingInProgress = false;
        this.quizReq++;
        this.nextQuizAt = 0;
        this.lastQuestion = null;
        this.lastAnswer = null;
        this.lastSource = null;
        this.didScrollForQuestion = false;
        this.preScrollRead = null;
        if (mode == AutomationMode.QUIZ) {
            QuizBatch.reset();
            QuizSync.syncSoon(this); // newest shared answers before the first question
        }

        notifyProgress("Starting " + (currentMode == AutomationMode.QUIZ ? "Quiz" : "Codes") + "...");
        reschedule(0);
    }

    private void doPause() {
        this.isPaused = !this.isPaused;
        if (this.isPaused) {
            handler.removeCallbacks(loopRunnable);
            loopScheduled = false;
            notifyProgress("Paused");
        } else {
            notifyProgress("Resuming...");
            reschedule(0);
        }
    }

    private void doStop(String reason) {
        Log.i(TAG, "stopAutomation: " + reason);
        this.isRunning = false;
        this.isPaused = false;
        this.currentCode = null;
        this.checkSubmitted = false;
        this.quizSolvingInProgress = false;
        this.quizReq++; // any reply still in flight is now stale
        this.lastQuestion = null;
        if (currentMode == AutomationMode.QUIZ) QuizBatch.flush(this, null); // send the run's last counts
        handler.removeCallbacks(loopRunnable);
        loopScheduled = false;
        notifyStopped(reason);
    }

    private void stepLoop() {
        if (!isRunning || isPaused) return;

        navThisStep = false;
        if (currentMode == AutomationMode.QUIZ) {
            stepQuizLoop();
        } else {
            stepCodesLoop();
        }
        if (!navThisStep) lostSince = 0; // this step was on a page we know
    }

    // ==========================================
    // SCREEN SNAPSHOT
    // One walk over Nusuk's windows per step; every check below searches this in-memory list
    // instead of walking the tree again (each getChild() is a call into another process).
    // ==========================================
    private static final class Item {
        final AccessibilityNodeInfo node;
        final String text;      // trimmed getText(), "" if none
        final String lower;     // text, lowercase
        final String descLower; // content description, lowercase
        final String cls;
        final boolean editable, clickable, enabled;
        final Rect bounds = new Rect();

        Item(AccessibilityNodeInfo n) {
            node = n;
            CharSequence t = n.getText(), d = n.getContentDescription(), c = n.getClassName();
            text = t != null ? t.toString().trim() : "";
            lower = text.toLowerCase();
            descLower = d != null ? d.toString().trim().toLowerCase() : "";
            cls = c != null ? c.toString() : "";
            editable = n.isEditable() || cls.toLowerCase().contains("edit");
            clickable = n.isClickable();
            enabled = n.isEnabled();
            n.getBoundsInScreen(bounds);
        }
    }

    private static final class Screen {
        final List<AccessibilityNodeInfo> roots;
        final List<Item> items = new ArrayList<>();

        Screen(List<AccessibilityNodeInfo> roots) {
            this.roots = roots;
            ArrayDeque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
            for (int r = roots.size() - 1; r >= 0; r--) stack.push(roots.get(r));
            while (!stack.isEmpty()) {
                AccessibilityNodeInfo n = stack.pop();
                items.add(new Item(n));
                for (int i = n.getChildCount() - 1; i >= 0; i--) { // keep document order
                    AccessibilityNodeInfo ch = n.getChild(i);
                    if (ch != null) stack.push(ch);
                }
            }
        }

        boolean isEmpty() { return roots.isEmpty(); }

        /** First node whose TEXT contains `sub` (case-insensitive). */
        Item find(String sub) {
            String s = sub.toLowerCase();
            for (Item it : items) if (it.lower.contains(s)) return it;
            return null;
        }

        boolean has(String sub) { return find(sub) != null; }

        /** First node whose text or description equals `label` (case-insensitive). */
        Item exact(String label) {
            String s = label.toLowerCase();
            for (Item it : items) if (it.lower.equals(s) || it.descLower.equals(s)) return it;
            return null;
        }

        Item firstEditable() {
            for (Item it : items) if (it.editable) return it;
            return null;
        }

        String allTextLower() {
            StringBuilder sb = new StringBuilder();
            for (Item it : items) if (!it.lower.isEmpty()) sb.append(it.lower).append(' ');
            return sb.toString();
        }
    }

    private static final long CACHE_MAX_AGE_MS = 1000;
    private long lastCacheClear = 0;

    private Screen snapshot() {
        updateScreenSize();
        long t0 = android.os.SystemClock.uptimeMillis();
        // The node cache is kept fresh only by the events we get, and notificationTimeout merges
        // those: it can go stale (a Congratulations page long gone kept being "seen" for a minute).
        // Drop it at most once a second: what we act on is never older than that, while most
        // reads stay cheap cache hits (a full re-read costs Nusuk's UI thread ~5x more).
        if (Build.VERSION.SDK_INT >= 33 && t0 - lastCacheClear > CACHE_MAX_AGE_MS) {
            clearCache();
            lastCacheClear = t0;
        }
        Screen s = new Screen(getNusukRoots());
        long ms = android.os.SystemClock.uptimeMillis() - t0;
        if (ms > 300) Log.w(TAG, "Slow screen read: " + s.items.size() + " nodes in " + ms + "ms");
        return s;
    }

    private void updateScreenSize() {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                screenW = dm.widthPixels;
                screenH = dm.heightPixels;
            }
        } catch (Exception ignored) {}
    }

    /** Reference (1080x2400) coordinates -> this screen. */
    private int sx(int x) { return Math.round(x * (float) screenW / REF_W); }
    private int sy(int y) { return Math.round(y * (float) screenH / REF_H); }

    // ==========================================
    // QUIZ AUTOMATION LOGIC
    // ==========================================
    private void stepQuizLoop() {
        try {
            if (targetBatchCount > 0 && batchProcessed >= targetBatchCount) {
                stopAutomation("Done (" + batchProcessed + " quizzes, " + quizCorrectCount + " ✓)");
                return;
            }

            Screen screen = snapshot();
            if (screen.isEmpty()) {
                if (DEBUG) Log.d(TAG, "Waiting for Nusuk window...");
                scheduleNextStep(500);
                return;
            }
            if (DEBUG) {
                String all = screen.allTextLower();
                Log.d(TAG, "Visible texts: " + (all.length() > 200 ? all.substring(0, 200) + "..." : all));
            }

            // 1. RESULT SCREEN: "Play again"
            Item playAgainBtn = screen.find("Play again");
            if (playAgainBtn != null) {
                Log.i(TAG, "Detected Result Screen (Play again button found)");
                if (lastQuestion != null) {
                    boolean isCorrect = isQuizResultCorrect(screen);

                    QuizStore store = QuizStore.get(this);
                    if (isCorrect) {
                        quizCorrectCount++;
                        Stats.questionSolved(this);
                        KeyRepository.getInstance(this).notifyChanged();
                        if (lastAnswer != null) store.markRight(lastQuestion, lastAnswer, lastSource);
                        if (lastAnswer != null && !"guess".equals(lastSource))
                            QuizBatch.result(this, lastQuestion, lastOptions, lastAnswer, true, batchDenied);
                        Log.i(TAG, "✓ Quiz Correct! (" + quizCorrectCount + " correct, " + quizWrongCount + " wrong, src=" + lastSource + ")");
                        notifyProgress("✓ Correct! (" + (batchProcessed + 1) + "/" + targetBatchCount + ")");
                    } else {
                        quizWrongCount++;
                        if (lastAnswer != null) store.markWrong(lastQuestion, lastAnswer);
                        // A blind guess says nothing about the server's cached answer: don't clear it.
                        if (!"guess".equals(lastSource)) {
                            Log.w(TAG, "✗ Quiz Wrong (" + lastSource + "): " + lastQuestion + " -> " + lastAnswer + " of " + lastOptions);
                            QuizBatch.result(this, lastQuestion, lastOptions, lastAnswer, false, batchDenied);
                        } else {
                            Log.w(TAG, "✗ Quiz Wrong (guess, not reported): " + lastQuestion);
                        }
                        notifyProgress("✗ Wrong answer (" + (batchProcessed + 1) + "/" + targetBatchCount + ")");
                    }

                    batchProcessed++;
                    lastQuestion = null;
                    lastAnswer = null;
                    lastSource = null;
                    quizSolvingInProgress = false;

                    if (targetBatchCount > 0 && batchProcessed >= targetBatchCount) {
                        stopAutomation("Done (" + batchProcessed + " quizzes, " + quizCorrectCount + " ✓)");
                        return;
                    }
                    // Optional human-like pause before the next quiz (Nusuk may throttle instant answers)
                    nextQuizAt = QuizPace.isOn(this) ? System.currentTimeMillis() + QuizPace.randomDelayMs() : 0;
                }

                // Re-checked every second, so turning the pause off mid-wait takes effect at once.
                long waitLeft = nextQuizAt - System.currentTimeMillis();
                if (nextQuizAt > 0 && waitLeft > 0 && QuizPace.isOn(this)) {
                    notifyProgress("Next quiz in " + ((waitLeft + 999) / 1000) + "s (" + batchProcessed + "/" + targetBatchCount + ")");
                    scheduleNextStep(Math.min(1000, waitLeft));
                    return;
                }
                nextQuizAt = 0;

                notifyProgress("Playing next quiz (" + batchProcessed + "/" + targetBatchCount + ")...");
                tapNode(playAgainBtn.node);
                scheduleNextStep(800);
                return;
            }

            // 2. MODAL CONFIRMATION: "Open the door"
            Item openDoorBtn = screen.find("Open the door");
            if (openDoorBtn != null) {
                Log.i(TAG, "Detected Modal: 'Open the door'");
                notifyProgress("Confirming door...");
                tapNode(openDoorBtn.node);
                scheduleNextStep(700);
                return;
            }

            // 3. QUIZ QUESTION SCREEN: "Check your answer"
            if (screen.has("Check your answer")) {
                if (DEBUG) Log.d(TAG, "Quiz Question Screen");
                if (quizSolvingInProgress) {
                    if (System.currentTimeMillis() - quizStartTime > QUIZ_TIMEOUT_MS) {
                        Log.w(TAG, "Quiz answer timeout -> dropping that request, re-reading the question");
                        quizSolvingInProgress = false;
                        quizReq++;
                    } else {
                        scheduleNextStep(200);
                        return;
                    }
                }

                QuizQuestionData data = extractQuizData(screen);
                if (didScrollForQuestion && preScrollRead != null) data = mergeAfterScroll(preScrollRead, data);
                if (data == null || data.question == null || data.options.size() < 2) {
                    if (DEBUG) Log.d(TAG, "Not enough quiz options visible -> scrolling down");
                    scrollDown();
                    scheduleNextStep(400);
                    return;
                }

                if (data.options.size() < 4 && !didScrollForQuestion) {
                    if (DEBUG) Log.d(TAG, "Found " + data.options.size() + " options, scrolling once to reveal more");
                    didScrollForQuestion = true;
                    preScrollRead = data;
                    scrollDown();
                    scheduleNextStep(350);
                    return;
                }
                didScrollForQuestion = false;
                preScrollRead = null;

                quizSolvingInProgress = true;
                quizStartTime = System.currentTimeMillis();
                final int req = ++quizReq;
                lastQuestion = data.question;
                final List<String> currentOpts = new ArrayList<>(data.options);
                lastOptions = currentOpts;
                final List<Rect> currentBounds = new ArrayList<>(data.optionBounds);
                final List<AccessibilityNodeInfo> currentNodes = new ArrayList<>(data.optionNodes);

                String preview = data.question.length() > 30 ? data.question.substring(0, 30) + "..." : data.question;
                final String question = data.question;
                QuizStore store = QuizStore.get(this);

                // 1st: answers on the phone (confirmed here, or the server's shared list synced to
                // the phone). No API call per question: the server counts them in batches
                // (the Play Quiz limits still apply; a refusal stops the run).
                QuizStore.Pick known = store.best(question, currentOpts);
                if (known != null) {
                    int idx = currentOpts.indexOf(known.answer);
                    lastAnswer = known.answer;
                    lastSource = known.source;
                    Log.i(TAG, "Known answer (" + known.source + ") -> Option " + idx);
                    notifyProgress("Selected (saved): " + known.answer);
                    selectAndCheck(req, idx, currentOpts, currentNodes, currentBounds, 800);
                    QuizBatch.answeredLocally(this, batchDenied);
                    return;
                }

                // 2nd: the server (shared cache, then AI). Tell it which options are known wrong.
                Log.i(TAG, "Asking server: " + currentOpts.size() + " options");
                notifyProgress("Solving: " + preview);

                QuizSolver.solveQuiz(this, question, data.options, store.wrongFor(question), onLoop(new QuizSolver.QuizCallback() {
                    @Override
                    public void onSuccess(String answer, boolean cached) {
                        if (req != quizReq || !isRunning) return; // stale reply (timed out, stopped, next question)
                        int matchIdx = findBestMatchingOption(answer, currentOpts);
                        Log.i(TAG, "Server answer -> Option " + matchIdx + " (cached=" + cached + ")");
                        notifyProgress("Selected: " + currentOpts.get(matchIdx));
                        lastAnswer = currentOpts.get(matchIdx);
                        lastSource = cached ? "server" : "ai";
                        QuizStore.get(NusukAccessibilityService.this).putUnverified(question, lastAnswer, lastSource);
                        selectAndCheck(req, matchIdx, currentOpts, currentNodes, currentBounds, 800);
                    }

                    @Override
                    public void onDenied(String code, String message) {
                        if (req != quizReq || !isRunning) return;
                        handleQuizDenied(code, message);
                    }

                    @Override
                    public void onError(String errorMessage) {
                        if (req != quizReq || !isRunning) return;
                        // Guess, but never an option this phone already saw marked wrong.
                        List<String> bad = QuizStore.get(NusukAccessibilityService.this).wrongFor(question);
                        int g = 0;
                        for (int i = 0; i < currentOpts.size(); i++) {
                            boolean known = false;
                            for (String w : bad) if (QuizStore.norm(w).equals(QuizStore.norm(currentOpts.get(i)))) known = true;
                            if (!known) { g = i; break; }
                        }
                        Log.e(TAG, "QuizSolver error: " + errorMessage + " -> guessing option " + g);
                        lastAnswer = currentOpts.get(g);
                        lastSource = "guess";
                        selectAndCheck(req, g, currentOpts, currentNodes, currentBounds, 600);
                    }
                }));
                return;
            }

            // 4. CHOOSE A DOOR SCREEN: "Choose a door"
            Item heading = screen.find("Choose a door");
            if (heading != null) {
                int door = doorIndex % 4;
                doorIndex++;
                notifyProgress("Selecting Door " + (door + 1) + "...");
                Item title = findDoor(screen, heading, DOOR_TITLES[door]);
                if (title != null) {
                    Log.i(TAG, "Choose a Door -> Door " + (door + 1) + " (by title)");
                    tapNode(title.node);
                } else {
                    int x = sx(DOORS[door][0]), y = sy(DOORS[door][1]);
                    Log.i(TAG, "Choose a Door -> Door " + (door + 1) + " at scaled (" + x + ", " + y + ")");
                    tapCoordinates(x, y);
                }
                scheduleNextStep(600);
                return;
            }

            // 5. AUTO-NAVIGATION: If on other screens, navigate to Play tab
            Item playTab = screen.exact("Play");
            if (playTab != null) {
                Log.i(TAG, "Detected Play tab -> navigating");
                notifyProgress("Navigating to Play...");
                tapNode(playTab.node);
                scheduleNextStep(700);
                return;
            }

            // 6. AUTO-NAVIGATION: If on main Nusuk home screen with "Start the challenge"
            Item startChallengeBtn = screen.find("Start the challenge");
            if (startChallengeBtn != null) {
                Log.i(TAG, "Detected 'Start the challenge' button -> tapping");
                notifyProgress("Starting challenge...");
                tapNode(startChallengeBtn.node);
                scheduleNextStep(1000);
                return;
            }

            navigateToChallenge(screen);

        } catch (Throwable t) {
            Log.e(TAG, "stepQuizLoop uncaught error", t);
            scheduleNextStep(600);
        }
    }

    /** The door's title text below the "Choose a door" heading (not the heading itself). */
    private Item findDoor(Screen screen, Item heading, String titleSub) {
        for (Item it : screen.items) {
            if (it == heading || it.lower.isEmpty()) continue;
            if (it.bounds.top < heading.bounds.bottom) continue;
            if (it.lower.contains(titleSub)) return it;
        }
        return null;
    }

    /** Replies from QuizSolver arrive on the main thread; hop onto the loop thread. */
    private QuizSolver.QuizCallback onLoop(QuizSolver.QuizCallback cb) {
        return new QuizSolver.QuizCallback() {
            @Override public void onSuccess(String answer, boolean cached) { handler.post(safe(() -> cb.onSuccess(answer, cached))); }
            @Override public void onError(String errorMessage) { handler.post(safe(() -> cb.onError(errorMessage))); }
            @Override public void onDenied(String code, String message) { handler.post(safe(() -> cb.onDenied(code, message))); }
        };
    }

    // ==========================================
    // CODES AUTOMATION LOGIC
    // ==========================================
    private void stepCodesLoop() {
        try {
            KeyRepository repo = KeyRepository.getInstance(this);

            Screen screen = snapshot();
            if (screen.isEmpty()) {
                if (DEBUG) Log.d(TAG, "Waiting for Nusuk window...");
                scheduleNextStep(500);
                return;
            }

            // 1. SUCCESS CHECK across all visible Nusuk windows (e.g. dialogs)
            Item addAnotherBtn = screen.find("Add another code to get a key");
            Item congratsNode = screen.find("Congratulations");
            if (addAnotherBtn != null || congratsNode != null) {
                if (currentCode != null) {
                    repo.markSuccess(currentCode);
                    batchProcessed++;
                    Log.i(TAG, "✓ Success: " + currentCode + " (" + batchProcessed + "/" + targetBatchCount + ")");
                    notifyProgress("✓ Success: " + currentCode);
                    currentCode = null;
                    checkSubmitted = false;
                    codeAttempts = 0;
                }

                boolean done = targetBatchCount > 0 && batchProcessed >= targetBatchCount;
                if (!done) notifyProgress("Collecting key (" + batchProcessed + "/" + targetBatchCount + ")");
                // "Add another code to get a key" closes the Congratulations page (also when done).
                // One press: the node if we have it, else its usual spot scaled to this screen.
                if (addAnotherBtn != null) tapNode(addAnotherBtn.node);
                else tapCoordinates(sx(540), sy(2274));
                if (done) {
                    stopAutomation("Done (" + batchProcessed + " codes)");
                    return;
                }
                lastCollectTime = System.currentTimeMillis();
                scheduleNextStep(250);
                return;
            }

            // 2. Logged out of Nusuk: never type codes into the login form
            if (screen.has("Login Or Register") || screen.has("Login to your account")) {
                stopAutomation("Log in to Nusuk first");
                return;
            }

            // 3. The code field, only on the screen that has "Check the code"
            Item edit = screen.has("Check the code") ? screen.firstEditable() : null;

            if (edit != null) {
                AccessibilityNodeInfo editText = edit.node;
                Item checkBtn = findCheckButton(screen, edit);

                // Phase A: Inject new code and submit
                if (!checkSubmitted || currentCode == null) {
                    long sinceCollect = System.currentTimeMillis() - lastCollectTime;
                    if (sinceCollect < COLLECT_SETTLE_MS) {
                        scheduleNextStep(COLLECT_SETTLE_MS - sinceCollect);
                        return;
                    }

                    // An error line left over from the previous code: clear the field and give
                    // Nusuk a moment to drop it, so it can't be read as this code's result.
                    boolean oldError = findErrorNode(screen, edit, checkBtn) != null;
                    if (oldError && clearWaits < MAX_CLEAR_WAITS) {
                        if (clearWaits == 0) setText(editText, "");
                        clearWaits++;
                        scheduleNextStep(150);
                        return;
                    }
                    clearWaits = 0;

                    // Adding codes is never blocked: if the history can't be loaded in time, go on
                    // (a code the account already has just comes back as "already used").
                    if (currentCode == null && repo.needsRestore() && restoreWaitSince >= 0) {
                        long now = System.currentTimeMillis();
                        if (restoreWaitSince == 0) restoreWaitSince = now;
                        if (now - restoreWaitSince > RESTORE_WAIT_MS) {
                            Log.w(TAG, "Used-code history not loaded after " + RESTORE_WAIT_MS + "ms -> continuing without it");
                            notifyProgress("Couldn't load your used codes, continuing");
                            restoreWaitSince = -1; // don't wait again this run
                            scheduleNextStep(300);
                            return;
                        }
                        if (now - lastRestoreKick > 10_000) { // retry now, not after the backoff
                            lastRestoreKick = now;
                            CodeSync.syncSoon(this, 0);
                        }
                        notifyProgress("Loading your used codes...");
                        scheduleNextStep(1000);
                        return;
                    }
                    if (restoreWaitSince > 0) restoreWaitSince = 0;

                    // currentCode is kept after a timeout so the same code is retried
                    if (currentCode == null) {
                        currentCode = repo.getNextCode();
                        codeAttempts = 0;
                    }
                    if (currentCode == null) {
                        stopAutomation("All keys tested!");
                        return;
                    }

                    Log.i(TAG, "Injecting code: " + currentCode + " (" + (batchProcessed + 1) + "/" + targetBatchCount + ")"
                            + (codeAttempts > 0 ? " retry " + codeAttempts : ""));
                    notifyProgress("Entering " + currentCode + " (" + (batchProcessed + 1) + "/" + targetBatchCount + ")");

                    setText(editText, currentCode);

                    // The field is re-created when the Keys screen re-renders; a code typed too
                    // early is silently dropped and "Check the code" stays disabled. Verify first.
                    editText.refresh();
                    CharSequence typed = editText.getText();
                    if (checkBtn != null) checkBtn.node.refresh();
                    boolean textOk = typed != null && typed.toString().trim().equalsIgnoreCase(currentCode);
                    boolean btnOk = checkBtn == null || checkBtn.node.isEnabled();
                    if ((!textOk || !btnOk) && notReadyCount < 12) {
                        notReadyCount++;
                        if (DEBUG) Log.d(TAG, "Input not ready (text=" + textOk + ", button=" + btnOk + ") -> retyping");
                        scheduleNextStep(250);
                        return;
                    }
                    notReadyCount = 0;

                    // Press "Check the code" once
                    if (checkBtn != null) {
                        tapNode(checkBtn.node);
                    } else {
                        // No button node: its usual spot, just below the field
                        Rect eb = edit.bounds;
                        tapCoordinates(eb.isEmpty() ? sx(540) : eb.centerX(),
                                eb.isEmpty() ? sy(1770) : eb.bottom + sy(150));
                    }

                    checkSubmitted = true;
                    checkSubmittedTime = System.currentTimeMillis();
                    staleErrorAtSubmit = oldError;
                    sawErrorGone = false;
                    notifyProgress("Checking " + currentCode + " (" + (batchProcessed + 1) + "/" + targetBatchCount + ")");
                    scheduleNextStep(250);
                    return;
                }

                // Phase B: Waiting for result of currentCode
                long elapsed = System.currentTimeMillis() - checkSubmittedTime;
                Item errNode = findErrorNode(screen, edit, checkBtn);
                if (errNode == null) sawErrorGone = true;

                // An error counts only if it is THIS code's: it wasn't on screen at submit,
                // or it disappeared since and came back.
                boolean freshError = errNode != null && (!staleErrorAtSubmit || sawErrorGone);
                if (elapsed >= 250 && freshError) {
                    // "Already used" = this Nusuk account has the code (not a bad code): kept apart
                    // so it's never retried and doesn't count against the code for other phones.
                    String errText = (errNode.lower + " " + errNode.descLower);
                    boolean already = errText.contains("already");
                    Log.i(TAG, (already ? "↺ Already used: " : "✗ Invalid: ") + currentCode + " [" + errText.trim() + "] ("
                            + (batchProcessed + 1) + "/" + targetBatchCount + ")");
                    if (already) repo.markAlready(currentCode); else repo.markInvalid(currentCode);
                    batchProcessed++;
                    notifyProgress((already ? "↺ Already in your account: " : "✗ Invalid: ") + currentCode);
                    currentCode = null;
                    checkSubmitted = false;
                    codeAttempts = 0;

                    if (targetBatchCount > 0 && batchProcessed >= targetBatchCount) {
                        stopAutomation("Done (" + batchProcessed + " codes)");
                        return;
                    }
                    // Next step clears the field and waits for this error line to go.
                    scheduleNextStep(0);
                    return;
                }

                // No answer at all: the code is NOT invalid. Retry it, then defer it
                // (left untested so a later run picks it up again).
                if (elapsed > CHECK_TIMEOUT_MS) {
                    codeAttempts++;
                    checkSubmitted = false;
                    if (codeAttempts < MAX_CODE_ATTEMPTS) {
                        Log.w(TAG, "No answer for " + currentCode + " after " + elapsed + "ms -> retrying");
                        notifyProgress("Retrying " + currentCode);
                    } else {
                        Log.w(TAG, "No answer for " + currentCode + " after " + codeAttempts + " tries -> deferring");
                        repo.defer(currentCode);
                        notifyProgress("Skipped for now: " + currentCode);
                        currentCode = null;
                        codeAttempts = 0;
                    }
                    scheduleNextStep(300);
                    return;
                }

                // Network request still in-flight
                scheduleNextStep(200);
                return;
            }

            // On the quiz result screen there is no Keys tab: go back to the doors page. Only that
            // screen: the "Congratulations" popup also has "Play again", and Back there (or twice)
            // would close Nusuk.
            if (screen.has("Play again") && screen.has("chance to win")
                    && !screen.has("Add another code") && !screen.has("Congratulations")) {
                notifyProgress("Leaving quiz result...");
                pressBack("leave quiz result for the Keys tab");
                scheduleNextStep(700);
                return;
            }

            // 4. AUTO-NAVIGATION: If on Doors/Play screen, navigate to Keys tab
            Item keysTab = screen.exact("Keys");
            if (keysTab != null) {
                notifyProgress("Navigating to Keys...");
                tapNode(keysTab.node);
                scheduleNextStep(500);
                return;
            }

            // 5. AUTO-NAVIGATION: If on main Nusuk home screen with "Start the challenge" button
            Item startChallengeBtn = screen.find("Start the challenge");
            if (startChallengeBtn != null) {
                notifyProgress("Opening Challenge...");
                tapNode(startChallengeBtn.node);
                scheduleNextStep(1000);
                return;
            }

            navigateToChallenge(screen);

        } catch (Throwable t) {
            Log.e(TAG, "stepCodesLoop uncaught error", t);
            scheduleNextStep(600);
        }
    }

    private static void setText(AccessibilityNodeInfo field, String value) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    // ==========================================
    // EXTRACTION & MATCHING HELPERS
    // ==========================================
    public static class QuizQuestionData {
        public String question;
        public List<String> options = new ArrayList<>();
        public List<Rect> optionBounds = new ArrayList<>();
        public List<AccessibilityNodeInfo> optionNodes = new ArrayList<>();
        Item questionItem; // the item read as the question (an option, if it scrolled off the top)
    }

    /** A batch send was refused (limit reached, code revoked...): stop like any refusal. */
    private final QuizBatch.DeniedListener batchDenied = (code, message) ->
            handler.post(safe(() -> { if (isRunning && currentMode == AutomationMode.QUIZ) handleQuizDenied(code, message); }));

    /**
     * After the scroll that reveals more options: keep the question from the read BEFORE the
     * scroll (it may now be off the top, which would turn option 1 into the "question"), and take
     * the options in order from both reads. Bounds/nodes come from the fresh read when possible.
     */
    private QuizQuestionData mergeAfterScroll(QuizQuestionData before, QuizQuestionData after) {
        if (before == null || before.question == null) return after;
        QuizQuestionData m = new QuizQuestionData();
        m.question = before.question;
        List<String> seen = new ArrayList<>();
        // fresh items (current positions) by text
        java.util.Map<String, Integer> freshIdx = new java.util.HashMap<>();
        List<String> freshTexts = new ArrayList<>();
        if (after != null) {
            if (after.question != null) freshTexts.add(after.question);
            freshTexts.addAll(after.options);
        }
        for (String o : before.options) addOption(m, o, before, after, seen);
        for (String t : freshTexts) {
            if (m.options.size() >= 4) break;
            if (t == null || t.equals(m.question)) continue;
            addOption(m, t, before, after, seen);
        }
        if (after != null && !before.question.equals(after.question))
            Log.i(TAG, "Question kept from before the scroll: '" + before.question + "' (top after scroll: '" + after.question + "')");
        return m;
    }

    private static void addOption(QuizQuestionData m, String text, QuizQuestionData before, QuizQuestionData after, List<String> seen) {
        if (text == null || seen.contains(text)) return;
        seen.add(text);
        m.options.add(text);
        int i = after != null ? after.options.indexOf(text) : -1;
        if (i >= 0) { m.optionBounds.add(after.optionBounds.get(i)); m.optionNodes.add(after.optionNodes.get(i)); return; }
        if (after != null && text.equals(after.question) && after.questionItem != null) {
            m.optionBounds.add(new Rect(after.questionItem.bounds)); m.optionNodes.add(after.questionItem.node); return;
        }
        int j = before.options.indexOf(text);
        m.optionBounds.add(j >= 0 ? before.optionBounds.get(j) : new Rect());
        m.optionNodes.add(j >= 0 ? before.optionNodes.get(j) : null);
    }

    private QuizQuestionData extractQuizData(Screen screen) {
        int minTop = sy(300); // skip the header bar
        List<Item> valid = new ArrayList<>();
        for (Item c : screen.items) {
            if (c.text.isEmpty()) continue;
            if (c.bounds.top < minTop) continue;
            String lower = c.lower;
            if (lower.contains("check your answer")) continue;
            if (lower.contains("your key can unlock") || lower.contains("draw for an umrah")) continue;
            if (lower.contains("choose a door") || lower.contains("start the challenge")) continue;
            if (lower.contains("play again")) continue;
            valid.add(c);
        }

        if (valid.size() < 2) return null;

        // Sort by top bounds ascending (stable: same-row items keep document order)
        Collections.sort(valid, (a, b) -> Integer.compare(a.bounds.top, b.bounds.top));

        QuizQuestionData data = new QuizQuestionData();
        data.question = valid.get(0).text;
        data.questionItem = valid.get(0);

        for (int i = 1; i < valid.size() && i <= 4; i++) {
            Item opt = valid.get(i);
            data.options.add(opt.text);
            data.optionBounds.add(new Rect(opt.bounds));
            data.optionNodes.add(opt.node);
        }

        return data;
    }

    private int findBestMatchingOption(String answer, List<String> options) {
        if (answer == null || options == null || options.isEmpty()) return 0;
        String cleanAns = normalizeText(answer);

        // 1. Exact match
        for (int i = 0; i < options.size(); i++) {
            if (normalizeText(options.get(i)).equalsIgnoreCase(cleanAns)) {
                return i;
            }
        }

        // 2. Substring match
        for (int i = 0; i < options.size(); i++) {
            String cleanOpt = normalizeText(options.get(i));
            if (cleanOpt.contains(cleanAns) || cleanAns.contains(cleanOpt)) {
                return i;
            }
        }

        // 3. Word overlap match
        int bestIdx = 0;
        int maxOverlap = -1;
        String[] ansWords = cleanAns.toLowerCase().split("\\s+");
        for (int i = 0; i < options.size(); i++) {
            String optLower = normalizeText(options.get(i)).toLowerCase();
            int overlap = 0;
            for (String w : ansWords) {
                if (w.length() > 2 && optLower.contains(w)) overlap++;
            }
            if (overlap > maxOverlap) {
                maxOverlap = overlap;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private String normalizeText(String text) {
        if (text == null) return "";
        return text.replaceAll("[ﷺ’'\"“”.,;:!?-]", " ").replaceAll("\\s+", " ").trim();
    }

    private void scrollDown() {
        Path path = new Path();
        path.moveTo(sx(540), sy(1600));
        path.lineTo(sx(540), sy(900));
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 200));
        dispatchGesture(builder.build(), null, null);
    }

    // ==========================================
    // ACCESSIBILITY UTILITIES
    // ==========================================
    /**
     * Each node read is a call that Nusuk answers on ITS main thread, so reading child by child
     * (one call per node, every step) keeps Nusuk's UI thread busy. On Android 13+ the subtree
     * comes back in batches (up to 50 nodes per call) and the walk reads from the local cache.
     */
    private static AccessibilityNodeInfo rootOf(AccessibilityWindowInfo window) {
        if (Build.VERSION.SDK_INT >= 33) {
            return window.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST);
        }
        return window.getRoot();
    }

    private List<AccessibilityNodeInfo> getNusukRoots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (int i = windows.size() - 1; i >= 0; i--) {
                AccessibilityWindowInfo window = windows.get(i);
                AccessibilityNodeInfo root = rootOf(window);
                if (root == null) continue;
                CharSequence pkg = root.getPackageName();
                if (DEBUG) Log.d(TAG, "window[" + i + "] pkg=" + pkg);
                if (pkg == null) continue;
                String pkgStr = pkg.toString();
                if (NUSUK_PKG.equals(pkgStr)) {
                    roots.add(root);
                } else if ("com.android.intentresolver".equals(pkgStr)) {
                    // Only a share sheet WE caused (a mis-aimed tap); never the user's own.
                    if (System.currentTimeMillis() - lastAutoTapTime < 3000) {
                        // One press only: while the sheet animates away it is still listed for a
                        // moment, and a second Back would land on Nusuk and close it.
                        pressBack("dismiss share sheet opened by our tap");
                    }
                } else if ("android".equals(pkgStr)) {
                    dismissNusukAnr(root);
                }
            }
        }
        // Usually already found above; only ask again (one more call into Nusuk) when not.
        AccessibilityNodeInfo active = roots.isEmpty() ? getRootInActiveWindow() : null;
        if (active != null) {
            CharSequence pkg = active.getPackageName();
            if (pkg != null && NUSUK_PKG.equals(pkg.toString()) && !roots.contains(active)) {
                roots.add(active);
            }
        }
        // Nusuk is NOT relaunched when it leaves the screen: the user must be free to switch
        // apps. The loop just waits (and says so) until Nusuk is back in front.
        if (roots.isEmpty() && isRunning) {
            long now = System.currentTimeMillis();
            // Our own tap just pushed Nusuk off screen (share sheet, link...): bring it back.
            if (now - lastAutoTapTime < 3000 && now - lastForegroundAttempt > 1500) {
                lastForegroundAttempt = now;
                Log.w(TAG, "Nusuk left the screen right after our tap -> bringing it back");
                try {
                    Intent back = getPackageManager().getLaunchIntentForPackage(NUSUK_PKG);
                    if (back != null) {
                        back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        startActivity(back);
                    }
                } catch (Exception ignored) {}
                return roots;
            }
            if (now - lastForegroundAttempt > 5000) {
                lastForegroundAttempt = now;
                notifyProgress("Paused: open Nusuk to continue");
            }
        }
        return roots;
    }

    // ==========================================
    // NAVIGATION: get from any Nusuk page to the challenge
    // ==========================================
    private void resetNavigation() {
        lostSince = 0;
        lastHomeTap = 0;
        navBacks = 0;
        navScrolls = 0;
        navRelaunched = false;
    }

    /**
     * Called when the page is none we act on (not the challenge, its tabs, or Nusuk's home with
     * "Start the challenge"). Steps, in order, one per call:
     *  1. Nusuk's main bottom bar visible (Home / Journey / Content / Store): tap Home.
     *  2. On Home but the challenge banner is off screen: scroll down to it.
     *  3. A sub-page without the bottom bar (Umrah, Wallet, profile, a booking...): press Back.
     *  4. Still lost: restart Nusuk once (it opens on Home).
     *  5. Still lost after LOST_GIVE_UP_MS: stop and tell the user, instead of hanging.
     */
    private void navigateToChallenge(Screen screen) {
        navThisStep = true;
        long now = System.currentTimeMillis();
        if (lostSince == 0) lostSince = now;
        long lost = now - lostSince;
        if (lost < LOST_GRACE_MS) { // quiet: most of these are just a page still loading
            scheduleNextStep(400);
            return;
        }
        if (lost > LOST_GIVE_UP_MS) {
            stopAutomation("Couldn't find the challenge. Open it in Nusuk, then press Start.");
            return;
        }

        Item home = screen.exact("Home");
        boolean mainBar = home != null && (screen.exact("Journey") != null || screen.exact("Store") != null);
        if (mainBar) {
            if (lastHomeTap == 0 || (navScrolls >= MAX_NAV_SCROLLS && now - lastHomeTap > 15000)) {
                lastHomeTap = now;
                if (navScrolls >= MAX_NAV_SCROLLS) navScrolls = 0;
                Log.i(TAG, "Navigate: not on Home -> tapping Home tab");
                notifyProgress("Opening Nusuk home...");
                tapNode(home.node);
                scheduleNextStep(1200);
                return;
            }
            if (navScrolls < MAX_NAV_SCROLLS) {
                navScrolls++;
                Log.i(TAG, "Navigate: on Home, challenge not visible -> scrolling (" + navScrolls + ")");
                notifyProgress("Looking for the challenge...");
                scrollDown();
                scheduleNextStep(700);
                return;
            }
        } else if (navBacks < MAX_NAV_BACKS && screen.items.size() >= 8) {
            // (few nodes = still loading / splash: Back there would close Nusuk)
            navBacks++;
            notifyProgress("Going back to Nusuk home...");
            lastAutoTapTime = now; // if Back closes Nusuk, getNusukRoots brings it back
            pressBack("navigate: unknown page " + describe(screen));
            scheduleNextStep(1200);
            return;
        }

        if (!navRelaunched) {
            navRelaunched = true;
            navScrolls = 0;
            Log.w(TAG, "Navigate: still lost -> restarting Nusuk " + describe(screen));
            notifyProgress("Restarting Nusuk...");
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(NUSUK_PKG);
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    lastAutoTapTime = now;
                    startActivity(i);
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not restart Nusuk", e);
            }
            scheduleNextStep(3000);
            return;
        }
        scheduleNextStep(800);
    }

    /** First few texts on screen, for the log. */
    private static String describe(Screen screen) {
        String all = screen.allTextLower();
        return "[" + (all.length() > 120 ? all.substring(0, 120) + "..." : all) + "]";
    }

    /** "Nusuk isn't responding" -> Wait. Any other app's ANR dialog is left alone. */
    private void dismissNusukAnr(AccessibilityNodeInfo root) {
        Screen dlg = new Screen(Collections.singletonList(root));
        if (!dlg.has("nusuk")) return;
        // "Nusuk Auto isn't responding" is OUR app: tapping Wait there (and then relaunching
        // Nusuk because it lost focus) made things worse. Only Nusuk's own dialog is answered.
        if (dlg.has(getString(R.string.app_name))) return;
        Item wait = dlg.exact("Wait");
        if (wait != null) {
            Log.i(TAG, "Dismissing Nusuk ANR via Wait");
            tapNode(wait.node);
        }
    }

    private boolean isQuizResultCorrect(Screen screen) {
        String allText = screen.allTextLower();
        return allText.contains("correct")
                && !allText.contains("incorrect") && !allText.contains("wrong") && !allText.contains("better luck");
    }

    /**
     * Nusuk's red error line under the code field. Only text between the field and the Check
     * button counts (when both are known), so a word like "already" elsewhere on the page can't
     * mark a code invalid.
     */
    private Item findErrorNode(Screen screen, Item edit, Item checkBtn) {
        int top = edit != null && !edit.bounds.isEmpty() ? edit.bounds.top : Integer.MIN_VALUE;
        int bottom = checkBtn != null && !checkBtn.bounds.isEmpty() ? checkBtn.bounds.top : Integer.MAX_VALUE;
        for (Item it : screen.items) {
            if (it.editable) continue;
            String s = (it.lower + " " + it.descLower).trim();
            if (s.isEmpty()) continue;
            if (it.bounds.centerY() < top || it.bounds.centerY() > bottom) continue;
            if (s.contains("not correct") || s.contains("already") || s.contains("incorrect")
                    || s.contains("invalid") || s.contains("expired") || s.contains("try again")) {
                return it;
            }
        }
        return null;
    }

    /** "Check the code", else the first button below the code field. No fixed screen band. */
    private Item findCheckButton(Screen screen, Item edit) {
        Item node = screen.find("Check the code");
        if (node != null) return node;
        int below = edit != null && !edit.bounds.isEmpty() ? edit.bounds.bottom : 0;
        for (Item it : screen.items) {
            if (it.cls.contains("Button") && it.bounds.top >= below) return it;
        }
        return null;
    }

    /**
     * Press a button ONCE: an accessibility click on it (or its clickable parent), and only if
     * that isn't possible a screen tap at its centre. Pressing both used to double-fire, and the
     * second press could land on whatever appeared next (e.g. "Share & get a chance to win!",
     * which opens the share sheet and pushes Nusuk to the background).
     */
    private void tapNode(AccessibilityNodeInfo node) {
        if (node == null) return;
        lastAutoTapTime = System.currentTimeMillis();
        if (performClick(node)) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.isEmpty()) {
            tapCoordinates(bounds.centerX(), bounds.centerY());
        }
    }

    /**
     * Tap the chosen option, then "Check your answer" 250ms later, then resume the loop.
     * The option is looked up again on the CURRENT screen by its text (the nodes captured
     * before a network call may be stale); the captured node/bounds are only the fallback.
     */
    private void selectAndCheck(int req, int idx, List<String> opts, List<AccessibilityNodeInfo> nodes,
                                List<Rect> bounds, long nextStepMs) {
        Item fresh = null;
        if (idx >= 0 && idx < opts.size()) {
            String want = opts.get(idx);
            for (Item it : snapshot().items) {
                if (it.text.equals(want)) { fresh = it; break; }
            }
        }
        if (fresh != null) {
            tapNode(fresh.node);
        } else if (idx >= 0 && idx < nodes.size() && nodes.get(idx) != null) {
            tapNode(nodes.get(idx));
        } else if (idx >= 0 && idx < bounds.size() && !bounds.get(idx).isEmpty()) {
            Rect b = bounds.get(idx);
            tapCoordinates(b.centerX(), b.centerY());
        }
        handler.postDelayed(safe(() -> {
            if (req != quizReq || !isRunning) return;
            tapCheckAnswer();
            scheduleNextStep(nextStepMs);
        }), 250);
    }

    /** No access (limit reached, quiz paused...): stop, never guess. The stop reason is the
     *  server's own message, shown like every other stop. */
    private void handleQuizDenied(String code, String message) {
        if (currentMode != AutomationMode.QUIZ) return; // quiz limits never touch adding codes
        quizSolvingInProgress = false;
        stopAutomation(message);
    }

    /** Press "Check your answer" if it is on screen. Never taps a fixed spot. */
    private void tapCheckAnswer() {
        Item btn = snapshot().find("Check your answer");
        if (btn != null) {
            tapNode(btn.node);
            return;
        }
        if (DEBUG) Log.d(TAG, "'Check your answer' not on screen (already submitted?) -> not tapping");
    }

    private void tapCoordinates(int x, int y) {
        lastAutoTapTime = System.currentTimeMillis();
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 100))
                .build();

        // Our own floating panel sits on top of Nusuk. If the tap point is under it, the
        // panel would get the tap (e.g. door 1 lands on the Stop button). Let taps pass
        // through the panel for the duration of the gesture, then give it back.
        OverlayService overlay = OverlayService.getInstance();
        if (overlay == null || !overlay.covers(x, y)) {
            dispatchGesture(gesture, null, null);
            return;
        }
        overlay.setPassThrough(true);
        handler.postDelayed(safe(() -> {
            GestureResultCallback done = new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) { release(); }
                @Override public void onCancelled(GestureDescription g) { release(); }
                private void release() { handler.postDelayed(safe(() -> overlay.setPassThrough(false)), 80); }
            };
            if (!dispatchGesture(gesture, done, handler)) overlay.setPassThrough(false);
        }), 60); // let the window manager apply the flag first
    }

    /** Accessibility click on the node or its nearest clickable parent. True if it was handled. */
    private boolean performClick(AccessibilityNodeInfo node) {
        for (int depth = 0; node != null && depth < 6; depth++, node = node.getParent()) {
            if (node.isClickable() && node.isEnabled()) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }
        return false;
    }
}
