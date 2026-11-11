package com.nusuk.automator.quiz;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Random;

/**
 * Human-like pacing so answers don't reach Nusuk's server at machine speed (it may throttle that).
 * The only wait is "thinking" time right before the answer is tapped: a random time between the
 * shortest and longest wait chosen in Settings. Everything else (choosing the door, starting the
 * next quiz) happens at once. Fast = no waiting at all.
 * The pref key "on" is kept from the old on/off switch: on = Normal, off = Fast.
 */
public final class QuizPace {
    private static final String PREFS = "quiz_pace";
    private static final String KEY_ON = "on", KEY_MIN = "minSec", KEY_MAX = "maxSec";
    private static final Random RANDOM = new Random();

    static final int DEFAULT_MIN_SEC = 2, DEFAULT_MAX_SEC = 5;
    static final int LIMIT_MIN_SEC = 1, LIMIT_MAX_SEC = 60;

    private QuizPace() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** True = Normal (human-like), false = Fast. */
    public static boolean isOn(Context c) {
        return prefs(c).getBoolean(KEY_ON, true);
    }

    public static void setOn(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_ON, on).apply();
    }

    public static int minSec(Context c) {
        return clamp(prefs(c).getInt(KEY_MIN, DEFAULT_MIN_SEC));
    }

    public static int maxSec(Context c) {
        return Math.max(minSec(c), clamp(prefs(c).getInt(KEY_MAX, DEFAULT_MAX_SEC)));
    }

    /** Saves the range in seconds; the longest wait is never below the shortest. */
    public static void setRange(Context c, int min, int max) {
        min = clamp(min);
        max = Math.max(min, clamp(max));
        prefs(c).edit().putInt(KEY_MIN, min).putInt(KEY_MAX, max).apply();
    }

    private static int clamp(int s) {
        return Math.max(LIMIT_MIN_SEC, Math.min(LIMIT_MAX_SEC, s));
    }

    /** Call when a run starts (kept for callers; nothing is carried between runs any more). */
    public static void resetRun() {}

    /** Wait before tapping the answer: random in the chosen range (Normal only; Fast returns 0). */
    public static long thinkDelayMs(Context c) {
        if (!isOn(c)) return 0;
        long lo = minSec(c) * 1000L, hi = maxSec(c) * 1000L;
        return lo + (long) (RANDOM.nextDouble() * (hi - lo));
    }
}
