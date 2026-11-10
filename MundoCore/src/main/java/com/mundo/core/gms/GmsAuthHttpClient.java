package com.mundo.core.gms;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal HTTP form POST client for Google auth endpoints.
 * Adapted from microG HttpFormClient — no reflection magic, plain URLConnection.
 */
public class GmsAuthHttpClient {

    private static final String AUTH_URL      = "https://android.googleapis.com/auth";
    private static final String CHECKIN_URL   = "https://android.clients.google.com/checkin";
    private static final String USER_AGENT    =
            "GoogleAuth/1.4 (generic device); gzip";
    private static final int    TIMEOUT_MS    = 15_000;

    /** POST key=value form to AUTH_URL and return parsed response map. */
    public static Map<String, String> postAuth(Map<String, String> params) throws IOException {
        return postForm(AUTH_URL, params);
    }

    /** POST protobuf bytes to CHECKIN_URL and return raw bytes. */
    public static byte[] postCheckin(byte[] body) throws IOException {
        URL url = new URL(CHECKIN_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-protobuffer");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept-Encoding", "gzip");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("Checkin HTTP " + code);
            }
            return readBytes(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private static Map<String, String> postForm(String endpoint, Map<String, String> params)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);

        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));
            conn.setRequestProperty("User-Agent", USER_AGENT);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            InputStream is = conn.getResponseCode() < 400
                    ? conn.getInputStream() : conn.getErrorStream();
            return parseResponse(readString(is));
        } finally {
            conn.disconnect();
        }
    }

    private static Map<String, String> parseResponse(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null) return map;
        for (String line : raw.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return map;
    }

    private static String readString(InputStream is) throws IOException {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static byte[] readBytes(InputStream is) throws IOException {
        byte[] buf = new byte[8192];
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    private static String encode(String v) {
        try {
            return java.net.URLEncoder.encode(v, "UTF-8");
        } catch (Exception e) {
            return v;
        }
    }
}
