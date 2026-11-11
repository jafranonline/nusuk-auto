package com.nusuk.automator.quiz;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Batches what the server must hear about a quiz run into few calls (/api/quiz/batch):
 * - `answered`: questions this phone answered from its own store (no API call per question;
 *   the server still counts them against the Play Quiz limits), and
 * - `results`: what Nusuk said about each answer (right / wrong), which verifies or retires the
 *   shared answer for everyone.
 *
 * Sent every BATCH_SIZE items, after a wrong result (so a bad shared answer is retired fast), on
 * stop, and before every answer while the phone is close to its limit (the server decides the
 * limit; the app never counts on its own). A refusal stops the run.
 */
public final class QuizBatch {
    private static final String TAG = "QuizBatch";
    private static final int BATCH_SIZE = 100;
    /** Flush before each answer while this few (or fewer) questions are left, or `left` is unknown. */
    private static final int NEAR_LIMIT = 3;

    public interface DeniedListener {
        void onDenied(String code, String message);
    }

    private static final ExecutorService exec = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static int pendingAnswered = 0;
    private static final List<JSONObject> pendingResults = new ArrayList<>();
    /** Questions left according to the server: -1 = unlimited, -2 = not known yet. */
    private static int left = -2;
    private static boolean sending = false;
    private static final int MAX_PENDING = 500;

    private QuizBatch() {}

    /** A new run: the server's count is unknown until the first reply. */
    public static synchronized void reset() {
        left = -2;
    }

    /** Server told us how many questions are left (null in JSON = unlimited). */
    static synchronized void setLeft(JSONObject reply) {
        if (reply == null || !reply.has("left")) return;
        left = reply.isNull("left") ? -1 : reply.optInt("left", -2);
    }

    /** One question answered from the phone's store. */
    public static void answeredLocally(Context ctx, DeniedListener l) {
        synchronized (QuizBatch.class) {
            pendingAnswered++;
        }
        maybeFlush(ctx, l, false);
    }

    /** Nusuk's verdict on an answer the app gave (skip blind guesses: they say nothing about a cached answer). */
    public static void result(Context ctx, String question, List<String> options, String answer, boolean right, DeniedListener l) {
        if (question == null || answer == null || options == null || options.size() < 2) return;
        try {
            JSONObject r = new JSONObject();
            r.put("question", question);
            r.put("options", new JSONArray(options));
            r.put("answer", answer);
            r.put("right", right);
            synchronized (QuizBatch.class) {
                pendingResults.add(r);
            }
        } catch (Exception ignored) {}
        maybeFlush(ctx, l, !right);
    }

    /** Send whatever is pending (end of a run). */
    public static void flush(Context ctx, DeniedListener l) {
        maybeFlush(ctx, l, true);
    }

    private static void maybeFlush(Context ctx, DeniedListener l, boolean force) {
        final Context app = ctx.getApplicationContext();
        final int n;
        final List<JSONObject> res;
        synchronized (QuizBatch.class) {
            if (sending) return; // the running send picks up the rest afterwards
            boolean near = left == -2 || (left >= 0 && left - pendingAnswered <= NEAR_LIMIT);
            boolean full = pendingAnswered + pendingResults.size() >= BATCH_SIZE;
            if (pendingAnswered == 0 && pendingResults.isEmpty()) return;
            if (!force && !full && !(near && pendingAnswered > 0)) return;
            n = pendingAnswered;
            res = new ArrayList<>(pendingResults);
            pendingAnswered = 0;
            pendingResults.clear();
            sending = true;
        }
        exec.execute(() -> {
            boolean ok = false, more;
            try {
                JSONObject p = new JSONObject();
                p.put("answered", n);
                p.put("results", new JSONArray(res));
                QuizSolver.Result r = QuizSolver.post(app, "/api/quiz/batch", p, 8000);
                if (r.status >= 200 && r.status < 300) {
                    ok = true;
                    setLeft(r.body);
                    Log.d(TAG, "Sent " + n + " answered, " + res.size() + " results; left=" + r.body.opt("left"));
                } else if (QuizSolver.isDenial(r)) {
                    ok = true; // counted what was allowed; the run must stop
                    String code = r.body.optString("code"), msg = r.body.optString("error", "Play Quiz is not available.");
                    Log.w(TAG, "Denied: " + code + " " + msg);
                    synchronized (QuizBatch.class) { left = 0; }
                    if (l != null) main.post(() -> l.onDenied(code, msg));
                } else {
                    Log.w(TAG, "Batch HTTP " + r.status + " " + r.body.optString("error"));
                    // The server will never accept this batch (bad request): drop it, or it blocks every later send.
                    if (r.status >= 400 && r.status < 500 && r.status != 408 && r.status != 429) ok = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "Batch failed, kept for the next send: " + e);
            }
            synchronized (QuizBatch.class) {
                if (!ok) { // put it back so nothing is lost (capped, so a long outage can't grow without limit)
                    pendingAnswered += n;
                    pendingResults.addAll(0, res);
                    while (pendingResults.size() > MAX_PENDING) pendingResults.remove(0);
                }
                sending = false;
                more = ok && (pendingAnswered > 0 || !pendingResults.isEmpty());
            }
            if (more) maybeFlush(app, l, false);
        });
    }
}
