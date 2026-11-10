package com.mundo.core.gms;

import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Fetches Google OAuth tokens using the master token stored in GmsTokenStore.
 * Adapted from microG AuthManager — stripped to the minimal needed for virtual space.
 *
 * Usage:
 *   GmsAuthManager mgr = new GmsAuthManager(ctx, email, callerPkg, callerSig);
 *   String token = mgr.requestToken("oauth2:profile email openid");
 */
public class GmsAuthManager {

    private static final String TAG = "GmsAuthManager";

    // Google's GMS package details expected by the auth endpoint
    private static final String GMS_PKG  = "com.google.android.gms";
    private static final int    GMS_VER  = 252432;

    private final Context ctx;
    private final String  email;
    private final String  callerPkg;
    private final String  callerSig;

    public GmsAuthManager(Context ctx, String email, String callerPkg, String callerSig) {
        this.ctx       = ctx.getApplicationContext();
        this.email     = email;
        this.callerPkg = callerPkg != null ? callerPkg : GMS_PKG;
        // Always use the REAL SHA1 of the guest app's signing cert.
        // Google validates this server-side — wrong sig = token rejected.
        this.callerSig = callerSig != null ? callerSig
                : GmsSignatureHelper.getCallerCertSha1(this.callerPkg);
    }

    /**
     * Returns a cached or freshly-fetched token for the given service string.
     * Service examples:
     *   "oauth2:profile email openid"
     *   "oauth2:https://www.googleapis.com/auth/games"
     *   "ac2dm"  (master token retrieval)
     */
    public String requestToken(String service) {
        String cached = GmsTokenStore.getCachedToken(ctx, email, service);
        if (cached != null) return cached;
        return fetchToken(service, null);
    }

    /**
     * Fetches a token from the Google auth endpoint.
     * @param service    oauth2 scope string or "ac2dm"
     * @param permissive if true, bypass user-not-permitted check
     */
    public String fetchToken(String service, String accessToken) {
        String masterToken = GmsTokenStore.getMasterToken(ctx, email);
        if (masterToken == null) {
            Log.w(TAG, "No master token for " + email);
            return null;
        }

        long androidId = GmsCheckin.getAndroidId(ctx);
        if (androidId == 0) {
            // Try a quick check-in; non-fatal if it fails
            GmsCheckin.checkin(ctx, false);
            androidId = GmsCheckin.getAndroidId(ctx);
        }

        Map<String, String> params = new HashMap<>();
        params.put("accountType",    "HOSTED_OR_GOOGLE");
        params.put("Email",          email);
        params.put("has_permission", "1");
        params.put("EncryptedPasswd", masterToken);
        params.put("service",        service);
        params.put("source",         "android");
        params.put("androidId",      androidId != 0 ? Long.toHexString(androidId) : "0");
        params.put("app",            callerPkg);
        params.put("client_sig",     callerSig);
        params.put("device_country", "us");
        params.put("operatorCountry","us");
        params.put("lang",           "en");
        params.put("sdk_version",    String.valueOf(android.os.Build.VERSION.SDK_INT));

        try {
            Map<String, String> resp = GmsAuthHttpClient.postAuth(params);
            if (resp.containsKey("Error")) {
                Log.w(TAG, "Auth error: " + resp.get("Error") + " detail=" + resp.get("detail"));
                return null;
            }
            String token  = resp.get("Auth");
            String expStr = resp.get("Expiry");
            long   expiry = 0;
            if (expStr != null) {
                try { expiry = Long.parseLong(expStr); } catch (Exception ignored) {}
            }
            if (token != null && !token.isEmpty()) {
                GmsTokenStore.storeToken(ctx, email, service, token, expiry);

                // Persist id-token / account-id when present
                String idToken  = resp.get("id_token");
                String acctId   = resp.get("GooglePlusUser");
                if (idToken != null) GmsTokenStore.storeIdToken(ctx, email, idToken);
                if (acctId  != null) GmsTokenStore.storeAccountId(ctx, email, acctId);
                Log.d(TAG, "token fetched for " + email + " service=" + service);
                return token;
            }
        } catch (Exception e) {
            Log.w(TAG, "requestToken exception: " + e.getMessage());
        }
        return null;
    }
}
