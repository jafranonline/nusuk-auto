package com.nusuk.automator;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Random;

/**
 * Human-like pacing so answers don't reach Nusuk's server at machine speed (it may throttle that).
 * Normal (default): think time before each answer, a skewed pause between quizzes (median ~9 s,
 * 4-25 s) and an occasional longer break. Fast: no extra waiting.
 * The pref key "on" is kept from the old on/off switch: on = Normal, off = Fast.
 */
final class QuizPace {
    private static final String PREFS = "quiz_pace";
    private static final String KEY_ON = "on";
    private static final Random RANDOM = new Random();

    // Between quizzes: log-normal, median 9 s, clamped.
    private static final double GAP_MEDIAN_MS = 9000, GAP_SIGMA = 0.55;
    private static final long GAP_MIN_MS = 4000, GAP_MAX_MS = 25000;
    // Think time before tapping an answer.
    private static final long THINK_MIN_MS = 1500, THINK_MAX_MS = 5000;
    // Rest break every 8-15 quizzes, 30-90 s.
    private static final int BREAK_EVERY_MIN = 8, BREAK_EVERY_MAX = 15;
    private static final long BREAK_MIN_MS = 30000, BREAK_MAX_MS = 90000;

    private static int untilBreak = nextBreakInterval();

    private QuizPace() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** True = Normal (human-like), false = Fast. */
    static boolean isOn(Context c) {
        return prefs(c).getBoolean(KEY_ON, true);
    }

    static void setOn(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_ON, on).apply();
    }

    private static int nextBreakInterval() {
        return BREAK_EVERY_MIN + RANDOM.nextInt(BREAK_EVERY_MAX - BREAK_EVERY_MIN + 1);
    }

    /** Call when a run starts, so the break counter doesn't carry over from the last run. */
    static synchronized void resetRun() {
        untilBreak = nextBreakInterval();
    }

    /** Wait before tapping the answer (Normal only; Fast returns 0). Longer questions read slower. */
    static long thinkDelayMs(Context c, int questionLength) {
        if (!isOn(c)) return 0;
        long base = THINK_MIN_MS + RANDOM.nextInt((int) (THINK_MAX_MS - THINK_MIN_MS) + 1);
        long extra = Math.min(1500, Math.max(0, questionLength - 60) * 12L);
        return base + extra;
    }

    /** Pause after a finished quiz (Normal only; Fast returns 0): skewed gap, sometimes a long break. */
    static synchronized long gapDelayMs(Context c) {
        if (!isOn(c)) return 0;
        if (--untilBreak <= 0) {
            untilBreak = nextBreakInterval();
            return BREAK_MIN_MS + (long) (RANDOM.nextDouble() * (BREAK_MAX_MS - BREAK_MIN_MS));
        }
        double ms = GAP_MEDIAN_MS * Math.exp(GAP_SIGMA * RANDOM.nextGaussian());
        return Math.max(GAP_MIN_MS, Math.min(GAP_MAX_MS, (long) ms));
    }
}
