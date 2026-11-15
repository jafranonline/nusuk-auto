package com.nusuk.automator.core;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import com.nusuk.automator.R;

/** The app's one JSON POST to the server API (device id header, fixed-length body). */
public final class Http {
    private Http() {}

    public static final class Response {
        public final int status;
        public final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    /** POSTs `payload` to api_base + path. Throws on network errors; HTTP errors come back as a status. */
    public static Response postJson(Context ctx, String path, JSONObject payload, int connectMs, int readMs) throws Exception {
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(ctx.getString(R.string.api_base) + path).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-Device-Id", Access.deviceId(ctx));
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }
            int status = conn.getResponseCode();
            InputStream in = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    char[] buf = new char[16384];
                    int n;
                    while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
                }
            }
            return new Response(status, sb.toString());
        } finally {
            conn.disconnect();
        }
    }
}
