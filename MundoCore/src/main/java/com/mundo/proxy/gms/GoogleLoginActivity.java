package com.mundo.proxy.gms;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.mundo.core.gms.GmsAuthManager;
import com.mundo.core.gms.GmsCheckin;
import com.mundo.core.gms.GmsSignatureHelper;
import com.mundo.core.gms.GmsTokenStore;
import com.mundo.utils.Slog;

import java.util.Map;

/**
 * WebView activity for Google account login.
 * Opens accounts.google.com/EmbeddedSetup (same as microG LoginActivity).
 * JS bridge "mm" extracts the oauth_token cookie, exchanges it for a master
 * token via the auth endpoint, then stores the account in GmsTokenStore.
 *
 * Launched by GmsSignInInterceptor when a guest app triggers Google sign-in.
 * Returns RESULT_OK when login succeeds; RESULT_CANCELED on failure/cancel.
 */
public class GoogleLoginActivity extends Activity {

    public static final String TAG = "GoogleLoginActivity";
    public static final String ACTION = "com.mundo.gms.GOOGLE_LOGIN";

    // The EmbeddedSetup URL — mirrors microG exactly
    private static final String LOGIN_URL =
            "https://accounts.google.com/EmbeddedSetup/identifier"
            + "?continue=https%3A%2F%2Faccounts.google.com%2FEmbeddedSetup";

    private WebView mWebView;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mWebView = new WebView(this);
        setContentView(mWebView);

        setupWebView(mWebView);
        mWebView.loadUrl(LOGIN_URL);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView(WebView wv) {
        WebSettings settings = wv.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setDatabaseEnabled(true);
        settings.setSaveFormData(false);
        settings.setSavePassword(false);
        // Use the same UA as microG
        settings.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 10; K) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/120.0.0.0 Mobile Safari/537.36");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);

        wv.addJavascriptInterface(new MmBridge(), "mm");
        wv.setWebChromeClient(new WebChromeClient());

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                // microG: on "close" event the page navigates to this dummy URL
                if (url.startsWith("https://accounts.google.com/appaccess")) {
                    onPageClose(view);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Inject JS to wire the mm.close() bridge that Google's script calls
                view.evaluateJavascript(
                        "window.addEventListener('message', function(e) { "
                        + "  if (e.data && e.data.type === 'close') { mm.closeView(); } "
                        + "  if (e.data && e.data.type === 'show')  { mm.showView(); } "
                        + "});",
                        null);
            }
        });
    }

    private void onPageClose(WebView view) {
        // Extract oauth_token cookie from accounts.google.com
        String cookies = CookieManager.getInstance().getCookie("https://accounts.google.com");
        String oauthToken = extractCookie(cookies, "oauth_token");
        if (oauthToken == null) {
            Slog.w(TAG, "oauth_token cookie not found after login close");
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        retrieveMasterToken(oauthToken);
    }

    private void retrieveMasterToken(final String oauthToken) {
        // Run off main thread — network call
        new Thread(() -> {
            try {
                Context ctx = getApplicationContext();
                // First do check-in if we don't have an androidId yet
                GmsCheckin.checkin(ctx, false);

                // Exchange oauth_token for a master (ac2dm) token
                GmsAuthManager mgr = new GmsAuthManager(ctx, "__auth__", null, null);
                // The oauth_token from the cookie IS the EncryptedPasswd for first exchange
                String masterToken = exchangeForMasterToken(ctx, oauthToken);
                if (masterToken == null) {
                    Slog.w(TAG, "master token exchange failed");
                    runOnUiThread(() -> { setResult(RESULT_CANCELED); finish(); });
                    return;
                }

                // Determine email
                String email = resolveEmail(ctx, masterToken);
                if (email == null || email.isEmpty()) {
                    Slog.w(TAG, "could not resolve email from master token");
                    runOnUiThread(() -> { setResult(RESULT_CANCELED); finish(); });
                    return;
                }

                // Store account
                GmsTokenStore.addAccount(ctx, email, masterToken, email);

                // Pre-fetch openid token to warm the cache
                GmsAuthManager idMgr = new GmsAuthManager(ctx, email, null, null);
                idMgr.requestToken("oauth2:profile email openid");

                Slog.d(TAG, "Google login success: " + email);
                runOnUiThread(() -> {
                    Intent result = new Intent();
                    result.putExtra("email", email);
                    setResult(RESULT_OK, result);
                    finish();
                });
            } catch (Exception e) {
                Slog.w(TAG, "retrieveMasterToken error: " + e);
                runOnUiThread(() -> { setResult(RESULT_CANCELED); finish(); });
            }
        }, "GmsLogin").start();
    }

    private String exchangeForMasterToken(Context ctx, String oauthToken) {
        try {
            java.util.Map<String, String> params = new java.util.HashMap<>();
            long androidId = GmsCheckin.getAndroidId(ctx);
            params.put("accountType",    "HOSTED_OR_GOOGLE");
            params.put("Email",          "");
            params.put("has_permission", "1");
            params.put("EncryptedPasswd", oauthToken);
            params.put("service",        "ac2dm");
            params.put("source",         "android");
            params.put("androidId",      androidId != 0 ? Long.toHexString(androidId) : "0");
            params.put("app",            "com.google.android.gms");
            params.put("client_sig",     "38918a453d07199354f8b19af05ec6562ced5788");
            params.put("device_country", "us");
            params.put("operatorCountry","us");
            params.put("lang",           "en");
            params.put("sdk_version",    String.valueOf(android.os.Build.VERSION.SDK_INT));

            Map<String, String> resp =
                    com.mundo.core.gms.GmsAuthHttpClient.postAuth(params);
            if (resp.containsKey("Token")) return resp.get("Token");
            if (resp.containsKey("Auth"))  return resp.get("Auth");
            Slog.w(TAG, "ac2dm exchange returned: " + resp);
        } catch (Exception e) {
            Slog.w(TAG, "exchangeForMasterToken: " + e.getMessage());
        }
        return null;
    }

    private String resolveEmail(Context ctx, String masterToken) {
        try {
            java.util.Map<String, String> params = new java.util.HashMap<>();
            long androidId = GmsCheckin.getAndroidId(ctx);
            params.put("accountType",    "HOSTED_OR_GOOGLE");
            params.put("Email",          "");
            params.put("has_permission", "1");
            params.put("EncryptedPasswd", masterToken);
            params.put("service",        "oauth2:https://www.googleapis.com/auth/userinfo.email");
            params.put("source",         "android");
            params.put("androidId",      androidId != 0 ? Long.toHexString(androidId) : "0");
            params.put("app",            "com.google.android.gms");
            params.put("client_sig",     "38918a453d07199354f8b19af05ec6562ced5788");
            params.put("device_country", "us");
            params.put("operatorCountry","us");
            params.put("lang",           "en");
            params.put("sdk_version",    String.valueOf(android.os.Build.VERSION.SDK_INT));

            Map<String, String> resp =
                    com.mundo.core.gms.GmsAuthHttpClient.postAuth(params);
            return resp.get("Email");
        } catch (Exception e) {
            Slog.w(TAG, "resolveEmail: " + e.getMessage());
        }
        return null;
    }

    private static String extractCookie(String cookies, String name) {
        if (cookies == null) return null;
        for (String part : cookies.split(";")) {
            part = part.trim();
            if (part.startsWith(name + "=")) {
                return part.substring(name.length() + 1).trim();
            }
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        if (mWebView != null) {
            mWebView.destroy();
            mWebView = null;
        }
        super.onDestroy();
    }

    // ---- JS bridge ----------------------------------------------------------

    private class MmBridge {
        @JavascriptInterface
        public void closeView() {
            runOnUiThread(() -> onPageClose(mWebView));
        }

        @JavascriptInterface
        public void showView() {
            // Google calls this to signal the page is ready to display
        }

        @JavascriptInterface
        public String getAndroidId() {
            long id = GmsCheckin.getAndroidId(getApplicationContext());
            return id != 0 ? Long.toHexString(id) : "0";
        }

        @JavascriptInterface
        public String getDroidGuardResult(String challenge) {
            return ""; // Not implementing DroidGuard — Google usually falls back gracefully
        }
    }
}
