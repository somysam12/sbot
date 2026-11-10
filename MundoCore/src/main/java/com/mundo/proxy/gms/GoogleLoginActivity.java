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

import com.mundo.core.gms.AuthManager;
import com.mundo.core.gms.AuthRequest;
import com.mundo.core.gms.AuthResponse;
import com.mundo.core.gms.GmsCheckin;
import com.mundo.core.gms.GmsSignatureHelper;
import com.mundo.core.gms.GmsTokenStore;
import com.mundo.utils.Slog;

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

                // Exchange oauth_token for a master (ac2dm) token using AuthRequest (microG approach)
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

                // Pre-fetch openid token to warm the cache using AuthManager (microG approach)
                try {
                    AuthManager idMgr = new AuthManager(ctx, email,
                            AuthRequest.GMS_PACKAGE_NAME, "oauth2:profile email openid");
                    idMgr.requestAuth(false);
                } catch (Exception ignored) {}

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

    // Exchange oauth_token cookie for a master (ac2dm) token — uses AuthRequest (microG approach)
    private String exchangeForMasterToken(Context ctx, String oauthToken) {
        try {
            AuthResponse resp = new AuthRequest()
                    .fromContext(ctx)
                    .source("android")
                    .accountType("HOSTED_OR_GOOGLE")
                    .app(AuthRequest.GMS_PACKAGE_NAME, GmsSignatureHelper.GMS_CERT_SHA1)
                    .caller(AuthRequest.GMS_PACKAGE_NAME, GmsSignatureHelper.GMS_CERT_SHA1)
                    .email("")
                    .token(oauthToken)
                    .service("ac2dm")
                    .hasPermission(true)
                    .getResponse();
            if (resp.token != null) return resp.token;
            if (resp.auth  != null) return resp.auth;
            Slog.w(TAG, "ac2dm exchange returned no token: " + resp);
        } catch (Exception e) {
            Slog.w(TAG, "exchangeForMasterToken: " + e.getMessage());
        }
        return null;
    }

    // Resolve email address from master token — uses AuthRequest (microG approach)
    private String resolveEmail(Context ctx, String masterToken) {
        try {
            AuthResponse resp = new AuthRequest()
                    .fromContext(ctx)
                    .source("android")
                    .accountType("HOSTED_OR_GOOGLE")
                    .app(AuthRequest.GMS_PACKAGE_NAME, GmsSignatureHelper.GMS_CERT_SHA1)
                    .caller(AuthRequest.GMS_PACKAGE_NAME, GmsSignatureHelper.GMS_CERT_SHA1)
                    .email("")
                    .token(masterToken)
                    .service("oauth2:https://www.googleapis.com/auth/userinfo.email")
                    .hasPermission(true)
                    .getResponse();
            return resp.email;
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
