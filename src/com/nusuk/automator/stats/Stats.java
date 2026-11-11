package com.nusuk.automator.stats;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Persistent "today / total" counters for codes added and quiz questions solved. */
public final class Stats {

    private static final String PREFS = "stats";

    private Stats() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private static synchronized void bump(Context c, String key) {
        SharedPreferences p = prefs(c);
        String day = today();
        int todayCount = day.equals(p.getString(key + "_day", "")) ? p.getInt(key + "_today", 0) : 0;
        // per-day history (this update on). Seed today from the old counter so nothing is lost.
        String dk = "d:" + day + ":" + key;
        int perDay = p.contains(dk) ? p.getInt(dk, 0) : todayCount;
        Set<String> days = new HashSet<>(p.getStringSet("days", new HashSet<>()));
        days.add(day);
        p.edit()
                .putString(key + "_day", day)
                .putInt(key + "_today", todayCount + 1)
                .putInt(key + "_total", p.getInt(key + "_total", 0) + 1)
                .putInt(dk, perDay + 1)
                .putStringSet("days", days)
                .apply();
    }

    private static int todayOf(Context c, String key) {
        SharedPreferences p = prefs(c);
        return today().equals(p.getString(key + "_day", "")) ? p.getInt(key + "_today", 0) : 0;
    }

    public static void codeAdded(Context c) { bump(c, "codes"); }
    public static void questionSolved(Context c) { bump(c, "quiz"); }

    public static int codesToday(Context c) { return todayOf(c, "codes"); }
    public static int quizToday(Context c) { return todayOf(c, "quiz"); }
    public static int quizTotal(Context c) { return prefs(c).getInt("quiz_total", 0); }

    /** One row of the date-wise breakdown. */
    public static final class Day {
        public final String date; // yyyy-MM-dd
        public final int codes, quiz;
        Day(String date, int codes, int quiz) { this.date = date; this.codes = codes; this.quiz = quiz; }
    }

    /** Days with activity, newest first (today included from the live counters). */
    public static synchronized List<Day> days(Context c) {
        SharedPreferences p = prefs(c);
        Set<String> set = new HashSet<>(p.getStringSet("days", new HashSet<>()));
        String today = today();
        if (todayOf(c, "codes") > 0 || todayOf(c, "quiz") > 0) set.add(today);
        List<String> sorted = new ArrayList<>(set);
        Collections.sort(sorted, Collections.reverseOrder());
        List<Day> out = new ArrayList<>();
        for (String d : sorted) {
            int codes = d.equals(today) ? todayOf(c, "codes") : p.getInt("d:" + d + ":codes", 0);
            int quiz = d.equals(today) ? todayOf(c, "quiz") : p.getInt("d:" + d + ":quiz", 0);
            if (codes > 0 || quiz > 0) out.add(new Day(d, codes, quiz));
        }
        return out;
    }

    // Codes total comes from KeyRepository (includes codes added before daily tracking existed)
}
