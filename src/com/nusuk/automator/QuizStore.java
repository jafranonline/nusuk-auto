package com.nusuk.automator;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * On-device quiz answer store (SQLite), checked before the server.
 *
 * Rows are (question, answer) pairs keyed by the normalized text of both, so option order and
 * the option set don't matter: a stored answer is used only if it is one of the options on
 * screen right now. "verified" = Nusuk said it was right on this phone (or it came from the
 * seed, which was verified by the Mac quiz.py). Answers Nusuk marked wrong go to `wrong` and
 * are never picked again (and are sent to the server as `wrong` so the AI avoids them).
 *
 * Seeded once from assets/quiz_seed.json (same format as the project's quiz_answers.json).
 * Reads are tiny indexed lookups (fine on the main thread); writes go to a background thread.
 *
 * server_answers: a copy of every answer the server knows (question, options, answer, how many
 * phones verified it), synced from /api/quiz/answers. Keyed by the server's own hash of
 * question + option set, so a known question is answered with no API call at all.
 */
public final class QuizStore extends SQLiteOpenHelper {
    private static final String TAG = "QuizStore";
    private static final String DB_NAME = "quiz.db";
    private static final int DB_VERSION = 2; // 2: server_answers (every answer known to the server)
    private static final String SEED_ASSET = "quiz_seed.json";

    private static QuizStore instance;
    private final Context appContext;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();

    public static synchronized QuizStore get(Context c) {
        if (instance == null) instance = new QuizStore(c.getApplicationContext());
        return instance;
    }

    private QuizStore(Context c) {
        super(c, DB_NAME, null, DB_VERSION);
        appContext = c;
        setWriteAheadLoggingEnabled(true);
    }

    /**
     * Same idea as the server's normQ (lowercase, every run of non-letters/digits -> one space),
     * but Unicode-aware so Arabic/Urdu/Bangla questions don't all collapse to "".
     * For plain English text it gives exactly the server's (and quiz.py's) result.
     */
    public static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE answers (" +
                "qnorm TEXT NOT NULL, anorm TEXT NOT NULL, question TEXT NOT NULL, answer TEXT NOT NULL," +
                "source TEXT NOT NULL, verified INTEGER NOT NULL DEFAULT 0, hits INTEGER NOT NULL DEFAULT 0," +
                "updated_at INTEGER NOT NULL, PRIMARY KEY (qnorm, anorm))");
        db.execSQL("CREATE TABLE wrong (" +
                "qnorm TEXT NOT NULL, anorm TEXT NOT NULL, answer TEXT NOT NULL, updated_at INTEGER NOT NULL," +
                "PRIMARY KEY (qnorm, anorm))");
        createServerTable(db);
        seed(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // Never drop learned answers: only add.
        if (oldV < 2) createServerTable(db);
    }

    private static void createServerTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS server_answers (h TEXT PRIMARY KEY, qnorm TEXT NOT NULL," +
                " question TEXT NOT NULL, options TEXT NOT NULL, answer TEXT NOT NULL, verified INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS server_answers_q ON server_answers (qnorm)");
    }

    // ---- the server's answers ----

    /** The server's normQ: lowercase, every run of non a-z0-9 -> one space, trim (ASCII only). */
    static String serverNorm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** The server's cache key: sha256(normQ(question) + "\n" + sorted normQ(options)), base64url, 22 chars. */
    static String serverKey(String question, List<String> options) {
        List<String> o = new ArrayList<>();
        for (String x : options) o.add(serverNorm(x));
        java.util.Collections.sort(o);
        StringBuilder sb = new StringBuilder(serverNorm(question));
        for (String x : o) sb.append('\n').append(x);
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            String b = android.util.Base64.encodeToString(d, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
            return b.substring(0, 22);
        } catch (Exception e) {
            return "";
        }
    }

    /** Applies one page from /api/quiz/answers (answer "" = retired on the server). */
    void applyServer(JSONArray list) {
        if (list == null || list.length() == 0) return;
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject r = list.optJSONObject(i);
                    if (r == null) continue;
                    String h = r.optString("h", ""), a = r.optString("a", "");
                    if (h.isEmpty()) continue;
                    if (a.isEmpty()) { db.delete("server_answers", "h = ?", new String[]{h}); continue; }
                    String q = r.optString("q", "");
                    ContentValues v = new ContentValues();
                    v.put("h", h); v.put("qnorm", norm(q)); v.put("question", q);
                    JSONArray o = r.optJSONArray("o");
                    v.put("options", o != null ? o.toString() : "[]");
                    v.put("answer", a); v.put("verified", r.optInt("v", 0));
                    db.insertWithOnConflict("server_answers", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            Log.e(TAG, "applyServer", e);
        }
    }

    /** The server's answer for exactly this question + option set, as on-screen option text (or null). */
    private Pick serverLookup(String question, List<String> options) {
        String h = serverKey(question, options);
        if (h.isEmpty()) return null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT answer, verified FROM server_answers WHERE h = ?", new String[]{h})) {
            if (c.moveToFirst()) {
                String a = serverNorm(c.getString(0));
                for (String o : options) if (serverNorm(o).equals(a)) return new Pick(o, "shared", c.getInt(1) == 1);
            }
        } catch (Exception e) {
            Log.e(TAG, "serverLookup", e);
        }
        return null;
    }

    private boolean isKnownWrong(String question, String answer) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM wrong WHERE qnorm = ? AND anorm = ?",
                new String[]{norm(question), norm(answer)})) {
            return c.moveToFirst();
        } catch (Exception e) {
            return false;
        }
    }

    /** Answer + where it came from. */
    public static final class Pick {
        public final String answer, source;
        /** Already confirmed (by Nusuk on this phone, or verified on the server): a right result teaches the server nothing. */
        public final boolean verified;
        Pick(String a, String s, boolean v) { answer = a; source = s; verified = v; }
    }

    /**
     * Best known answer with no network: Nusuk-confirmed on this phone, then the server's
     * shared answer, then one this phone got from the server before. Never a known-wrong one.
     */
    public Pick best(String question, List<String> options) {
        String v = lookup(question, options, true);
        if (v != null) return new Pick(v, "local", true);
        Pick s = serverLookup(question, options);
        if (s != null && !isKnownWrong(question, s.answer)) return s;
        String u = lookup(question, options, false);
        return u != null ? new Pick(u, "local", false) : null;
    }

    public int serverCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM server_answers", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void seed(SQLiteDatabase db) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                appContext.getAssets().open(SEED_ASSET), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            JSONObject json = new JSONObject(sb.toString());
            long now = System.currentTimeMillis() / 1000;
            int right = 0, wrong = 0;
            JSONObject r = json.optJSONObject("right");
            if (r != null) for (Iterator<String> it = r.keys(); it.hasNext(); ) {
                String q = it.next(), a = r.optString(q, "");
                if (norm(q).isEmpty() || norm(a).isEmpty()) continue;
                ContentValues v = new ContentValues();
                v.put("qnorm", norm(q)); v.put("anorm", norm(a));
                v.put("question", q); v.put("answer", a);
                v.put("source", "seed"); v.put("verified", 1); v.put("updated_at", now);
                db.insertWithOnConflict("answers", null, v, SQLiteDatabase.CONFLICT_IGNORE);
                right++;
            }
            JSONObject w = json.optJSONObject("wrong");
            if (w != null) for (Iterator<String> it = w.keys(); it.hasNext(); ) {
                String q = it.next();
                JSONArray arr = w.optJSONArray(q);
                if (arr == null) continue;
                for (int i = 0; i < arr.length(); i++) {
                    String a = arr.optString(i, "");
                    if (norm(q).isEmpty() || norm(a).isEmpty()) continue;
                    ContentValues v = new ContentValues();
                    v.put("qnorm", norm(q)); v.put("anorm", norm(a)); v.put("answer", a); v.put("updated_at", now);
                    db.insertWithOnConflict("wrong", null, v, SQLiteDatabase.CONFLICT_IGNORE);
                    wrong++;
                }
            }
            Log.i(TAG, "Seeded " + right + " answers, " + wrong + " known-wrong");
        } catch (Exception e) {
            Log.w(TAG, "No seed loaded", e);
        }
    }

    /** Opens (and on first run, creates + seeds) the DB off the main thread. */
    public void warmUp() {
        writer.execute(() -> {
            try { getWritableDatabase(); } catch (Exception e) { Log.e(TAG, "warmUp", e); }
        });
    }

    /**
     * The stored answer for this question that is one of `options` (returned as the on-screen
     * option text), or null. Verified answers win; known-wrong answers are skipped.
     */
    public String lookup(String question, List<String> options) {
        String v = lookup(question, options, true);
        return v != null ? v : lookup(question, options, false);
    }

    private String lookup(String question, List<String> options, boolean verified) {
        String qn = norm(question);
        if (qn.isEmpty() || options == null || options.isEmpty()) return null;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT a.anorm FROM answers a WHERE a.qnorm = ? AND a.verified = ? AND NOT EXISTS " +
                "(SELECT 1 FROM wrong w WHERE w.qnorm = a.qnorm AND w.anorm = a.anorm) " +
                "ORDER BY a.updated_at DESC", new String[]{qn, verified ? "1" : "0"})) {
            while (c.moveToNext()) {
                String an = c.getString(0);
                for (String o : options) if (norm(o).equals(an)) return o;
            }
        } catch (Exception e) {
            Log.e(TAG, "lookup", e);
        }
        return null;
    }

    /** Options Nusuk already marked wrong for this question (sent to the server as `wrong`). */
    public List<String> wrongFor(String question) {
        List<String> out = new ArrayList<>();
        String qn = norm(question);
        if (qn.isEmpty()) return out;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT answer FROM wrong WHERE qnorm = ? ORDER BY updated_at DESC LIMIT 5", new String[]{qn})) {
            while (c.moveToNext()) out.add(c.getString(0));
        } catch (Exception e) {
            Log.e(TAG, "wrongFor", e);
        }
        return out;
    }

    /** An answer from the server (not yet confirmed by Nusuk). Never downgrades a verified row. */
    public void putUnverified(String question, String answer, String source) {
        write(question, answer, (db, qn, an, now) -> {
            ContentValues v = new ContentValues();
            v.put("qnorm", qn); v.put("anorm", an); v.put("question", question); v.put("answer", answer);
            v.put("source", source); v.put("verified", 0); v.put("updated_at", now);
            db.insertWithOnConflict("answers", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        });
    }

    /** Nusuk said this answer is right. */
    public void markRight(String question, String answer, String source) {
        write(question, answer, (db, qn, an, now) -> {
            // No UPSERT: SQLite < 3.24 on Android 8-10 (minSdk 26). Update, else insert.
            db.execSQL("UPDATE answers SET verified = 1, hits = hits + 1, updated_at = ? WHERE qnorm = ? AND anorm = ?",
                    new Object[]{now, qn, an});
            ContentValues v = new ContentValues();
            v.put("qnorm", qn); v.put("anorm", an); v.put("question", question); v.put("answer", answer);
            v.put("source", source); v.put("verified", 1); v.put("hits", 1); v.put("updated_at", now);
            db.insertWithOnConflict("answers", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            db.delete("wrong", "qnorm = ? AND anorm = ?", new String[]{qn, an});
        });
    }

    /** Nusuk said this answer is wrong: drop it and never pick it again. */
    public void markWrong(String question, String answer) {
        write(question, answer, (db, qn, an, now) -> {
            db.delete("answers", "qnorm = ? AND anorm = ?", new String[]{qn, an});
            ContentValues v = new ContentValues();
            v.put("qnorm", qn); v.put("anorm", an); v.put("answer", answer); v.put("updated_at", now);
            db.insertWithOnConflict("wrong", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        });
    }

    public int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM answers", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private interface Op { void run(SQLiteDatabase db, String qn, String an, long now); }

    private void write(String question, String answer, Op op) {
        String qn = norm(question), an = norm(answer);
        if (qn.isEmpty() || an.isEmpty()) return;
        writer.execute(() -> {
            try {
                SQLiteDatabase db = getWritableDatabase();
                db.beginTransaction();
                try {
                    op.run(db, qn, an, System.currentTimeMillis() / 1000);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            } catch (Exception e) {
                Log.e(TAG, "write", e);
            }
        });
    }
}
