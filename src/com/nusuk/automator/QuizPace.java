package com.nusuk.automator;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Random;

/**
 * Optional pause between quizzes, so answers don't reach Nusuk's server at machine speed
 * (it may throttle that). When on, each wait is a random 2-6 s. On by default.
 */
final class QuizPace {
    private static final String PREFS = "quiz_pace";
    private static final String KEY_ON = "on";
    static final long MIN_MS = 2000, MAX_MS = 6000;
    private static final Random RANDOM = new Random();

    private QuizPace() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isOn(Context c) {
        return prefs(c).getBoolean(KEY_ON, true);
    }

    static void setOn(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_ON, on).apply();
    }

    static long randomDelayMs() {
        return MIN_MS + RANDOM.nextInt((int) (MAX_MS - MIN_MS) + 1);
    }
}
