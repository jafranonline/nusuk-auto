package com.nusuk.automator;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Users adding their own keys. {@link #parse} pulls keys out of any pasted text (no special format),
 * {@link #submit} sends them to the live key database (/api/app/keys), which answers which keys were
 * new and which it already had.
 */
final class KeySubmit {
    private static final int CHUNK = 100;   // keys per request (server takes 100)
    private static final Pattern VALID = Pattern.compile("(?<![A-Za-z0-9])NG-[A-Za-z0-9]{8}(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOOKS_LIKE_KEY = Pattern.compile("^NG-?[A-Za-z0-9-]*$", Pattern.CASE_INSENSITIVE);

    private KeySubmit() {}

    /** What was found in the pasted text. Keys differing only in letter case count as one. */
    static final class Parsed {
        final List<String> keys = new ArrayList<>();      // valid, first occurrence of each
        final List<String> invalid = new ArrayList<>();   // looked like a key (NG...) but isn't one
        final List<String> repeated = new ArrayList<>();  // valid but pasted more than once
    }

    static final class Result {
        final List<String> added = new ArrayList<>(), duplicate = new ArrayList<>(), invalid = new ArrayList<>();
        String error; // set when sending stopped early (offline, daily limit...); lists hold what was done until then
    }

    /** "NG-p36wAxup" in any text; also "ng-p36wAxup", dashes of other kinds, one per line, comma separated... */
    static Parsed parse(String text) {
        Parsed out = new Parsed();
        if (text == null) return out;
        String t = text.replaceAll("[\\u2010-\\u2015\\u2212]", "-");
        Set<String> seen = new LinkedHashSet<>();
        Set<String> badSeen = new LinkedHashSet<>();
        for (String tok : t.split("[\\s,;|]+")) {
            tok = tok.replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9-]+$", "");
            if (tok.isEmpty()) continue;
            Matcher m = VALID.matcher(tok);
            boolean any = false;
            while (m.find()) {
                any = true;
                String k = "NG-" + m.group().substring(3);
                if (seen.add(k.toLowerCase(Locale.ROOT))) out.keys.add(k);
                else out.repeated.add(k);
            }
            if (!any && tok.length() >= 3 && LOOKS_LIKE_KEY.matcher(tok).matches() && badSeen.add(tok)) out.invalid.add(tok);
        }
        return out;
    }

    /** Blocking: call off the main thread. */
    static Result submit(Context ctx, List<String> keys) {
        Result r = new Result();
        for (int i = 0; i < keys.size(); i += CHUNK) {
            List<String> part = keys.subList(i, Math.min(keys.size(), i + CHUNK));
            try {
                JSONObject j = post(ctx, part);
                add(r.added, j.optJSONArray("added"));
                add(r.duplicate, j.optJSONArray("duplicate"));
                add(r.invalid, j.optJSONArray("invalid"));
            } catch (Exception e) {
                // ServerError = the server answered with a problem (message is shown); anything else = no connection
                r.error = e instanceof ServerError ? e.getMessage() : "NETWORK";
                return r;
            }
        }
        return r;
    }

    private static final class ServerError extends Exception {
        ServerError(String m) { super(m); }
    }

    private static void add(List<String> to, JSONArray a) {
        if (a != null) for (int i = 0; i < a.length(); i++) to.add(a.optString(i));
    }

    private static JSONObject post(Context ctx, List<String> keys) throws Exception {
        byte[] bytes = new JSONObject().put("keys", new JSONArray(keys)).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(ctx.getString(R.string.api_base) + "/api/app/keys").openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-Device-Id", Access.deviceId(ctx));
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20_000);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = conn.getOutputStream()) { os.write(bytes); }
            int status = conn.getResponseCode();
            InputStream in = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
            }
            if (status == 429 || status == 413) {
                String msg = "Too many requests, try again later.";
                try { msg = new JSONObject(sb.toString()).optString("error", msg); } catch (Exception ignored) {}
                throw new ServerError(msg);
            }
            if (status != 200) throw new ServerError("SERVER " + status);
            return new JSONObject(sb.toString());
        } finally {
            conn.disconnect();
        }
    }
}
