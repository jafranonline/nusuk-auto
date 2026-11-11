package com.nusuk.automator.quiz;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.nusuk.automator.R;
import com.nusuk.automator.core.Access;

/**
 * Talks to the Play Quiz API. Every call carries this phone's device id. The server decides who
 * may get answers (the admin's global Play Quiz limits).
 */
public class QuizSolver {
    private static final String TAG = "QuizSolver";

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface QuizCallback {
        void onSuccess(String answer, boolean cached);
        void onError(String errorMessage);
        /** The server refused: limit reached, quiz paused... `code` is machine-readable. */
        void onDenied(String code, String message);
    }

    public interface JsonCallback {
        void onResult(JSONObject json);
        void onFailure(String code, String message);
    }

    static final class Result {
        int status;
        JSONObject body;
    }

    private static String base(Context c) {
        return c.getString(R.string.api_base);
    }

    static Result post(Context ctx, String path, JSONObject payload, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(base(ctx) + path).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-Device-Id", Access.deviceId(ctx));
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs + 4000);
            conn.setDoOutput(true);
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }
            Result r = new Result();
            r.status = conn.getResponseCode();
            InputStream in = r.status < 400 ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
            }
            try {
                r.body = new JSONObject(sb.toString());
            } catch (Exception e) {
                r.body = new JSONObject();
            }
            return r;
        } finally {
            conn.disconnect();
        }
    }

    /** A refusal the app should act on (has a "code"), as opposed to a transient error. */
    static boolean isDenial(Result r) {
        return r.status >= 400 && r.body.has("code");
    }

    public static void solveQuiz(Context context, final String question, final List<String> options, final QuizCallback callback) {
        solveQuiz(context, question, options, null, callback);
    }

    /** `wrong`: options Nusuk already marked wrong for this question; the server's AI avoids them. */
    public static void solveQuiz(Context context, final String question, final List<String> options,
                                 final List<String> wrong, final QuizCallback callback) {
        final Context ctx = context.getApplicationContext();
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("question", question);
                JSONArray opts = new JSONArray();
                for (String opt : options) opts.put(opt);
                payload.put("options", opts);
                if (wrong != null && !wrong.isEmpty()) {
                    JSONArray w = new JSONArray();
                    for (String x : wrong) w.put(x);
                    payload.put("wrong", w);
                }

                Result r = post(ctx, "/api/quiz", payload, 5000); // 5s connect + 9s read, under the loop's 16s QUIZ_TIMEOUT_MS;
                if (r.status >= 200 && r.status < 300) {
                    String answer = r.body.optString("answer", "");
                    boolean cached = r.body.optBoolean("cached", false);
                    QuizBatch.setLeft(r.body);
                    Log.i(TAG, "Worker answer: '" + answer + "' (cached=" + cached + ", left=" + r.body.opt("left") + ")");
                    mainHandler.post(() -> callback.onSuccess(answer, cached));
                } else if (isDenial(r)) {
                    String code = r.body.optString("code"), msg = r.body.optString("error", "Play Quiz is not available.");
                    Log.w(TAG, "Quiz denied: " + code + " " + msg);
                    mainHandler.post(() -> callback.onDenied(code, msg));
                } else {
                    String err = "HTTP " + r.status + ": " + r.body.optString("error", "");
                    Log.e(TAG, err);
                    mainHandler.post(() -> callback.onError(err));
                }
            } catch (Exception e) {
                Log.e(TAG, "QuizSolver request failed", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    /** Questions left for this phone (see /api/quiz/status). */
    public static void fetchStatus(Context context, final JsonCallback callback) {
        call(context, "/api/quiz/status", new JSONObject(), callback);
    }

    private static void call(Context context, String path, JSONObject payload, final JsonCallback callback) {
        final Context ctx = context.getApplicationContext();
        executor.execute(() -> {
            try {
                Result r = post(ctx, path, payload, 8000);
                if (r.status >= 200 && r.status < 300) {
                    mainHandler.post(() -> callback.onResult(r.body));
                } else {
                    String code = r.body.optString("code", "HTTP_" + r.status);
                    String msg = r.body.optString("error", "Something went wrong. Please try again.");
                    mainHandler.post(() -> callback.onFailure(code, msg));
                }
            } catch (Exception e) {
                Log.e(TAG, path + " failed", e);
                mainHandler.post(() -> callback.onFailure("NETWORK", "Can't reach the server. Check your internet."));
            }
        });
    }
}
