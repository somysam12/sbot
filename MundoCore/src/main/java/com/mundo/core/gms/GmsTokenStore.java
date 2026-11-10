package com.mundo.core.gms;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Persistent token cache for virtual Google accounts.
 * Stores master tokens, oauth2 access tokens and id tokens per account email.
 */
public class GmsTokenStore {

    private static final String TAG   = "GmsTokenStore";
    private static final String PREFS = "gms_tokens";

    private static final String KEY_ACCOUNTS         = "accounts";
    private static final String KEY_MASTER_PREFIX    = "master_";
    private static final String KEY_EMAIL_PREFIX     = "email_";
    private static final String KEY_DISPLAY_PREFIX   = "display_";
    private static final String KEY_TOKEN_PREFIX     = "tok_";   // tok_<email>_<service>
    private static final String KEY_EXPIRY_PREFIX    = "exp_";   // exp_<email>_<service>
    private static final String KEY_ID_TOKEN_PREFIX  = "idt_";   // idt_<email>
    private static final String KEY_ACCT_ID_PREFIX   = "aid_";   // aid_<email>

    // ---- accounts -----------------------------------------------------------

    public static void addAccount(Context ctx, String email, String masterToken,
                                  String displayName) {
        SharedPreferences p = prefs(ctx);
        Set<String> accounts = new HashSet<>(p.getStringSet(KEY_ACCOUNTS, new HashSet<>()));
        accounts.add(email);
        p.edit()
                .putStringSet(KEY_ACCOUNTS, accounts)
                .putString(KEY_MASTER_PREFIX + email, masterToken)
                .putString(KEY_EMAIL_PREFIX  + email, email)
                .putString(KEY_DISPLAY_PREFIX + email, displayName != null ? displayName : "")
                .apply();
        Log.d(TAG, "account stored: " + email);
    }

    public static void removeAccount(Context ctx, String email) {
        SharedPreferences.Editor ed = prefs(ctx).edit();
        Set<String> accounts = new HashSet<>(prefs(ctx).getStringSet(KEY_ACCOUNTS, new HashSet<>()));
        accounts.remove(email);
        ed.putStringSet(KEY_ACCOUNTS, accounts)
                .remove(KEY_MASTER_PREFIX  + email)
                .remove(KEY_EMAIL_PREFIX   + email)
                .remove(KEY_DISPLAY_PREFIX + email)
                .remove(KEY_ID_TOKEN_PREFIX + email)
                .remove(KEY_ACCT_ID_PREFIX  + email)
                .apply();
    }

    public static Set<String> getAccounts(Context ctx) {
        return prefs(ctx).getStringSet(KEY_ACCOUNTS, new HashSet<>());
    }

    public static boolean hasAccount(Context ctx, String email) {
        return prefs(ctx).contains(KEY_MASTER_PREFIX + email);
    }

    public static String getMasterToken(Context ctx, String email) {
        return prefs(ctx).getString(KEY_MASTER_PREFIX + email, null);
    }

    public static String getDisplayName(Context ctx, String email) {
        return prefs(ctx).getString(KEY_DISPLAY_PREFIX + email, email);
    }

    // ---- access tokens ------------------------------------------------------

    public static void storeToken(Context ctx, String email, String service,
                                  String token, long expiryEpochSecs) {
        prefs(ctx).edit()
                .putString(KEY_TOKEN_PREFIX + email + "_" + service, token)
                .putLong(KEY_EXPIRY_PREFIX  + email + "_" + service, expiryEpochSecs)
                .apply();
    }

    public static String getCachedToken(Context ctx, String email, String service) {
        SharedPreferences p = prefs(ctx);
        long expiry = p.getLong(KEY_EXPIRY_PREFIX + email + "_" + service, 0);
        if (System.currentTimeMillis() / 1000 >= expiry - 60) return null; // expired
        return p.getString(KEY_TOKEN_PREFIX + email + "_" + service, null);
    }

    // ---- id token / account id ----------------------------------------------

    public static void storeIdToken(Context ctx, String email, String idToken) {
        prefs(ctx).edit().putString(KEY_ID_TOKEN_PREFIX + email, idToken).apply();
    }

    public static String getIdToken(Context ctx, String email) {
        return prefs(ctx).getString(KEY_ID_TOKEN_PREFIX + email, null);
    }

    public static void storeAccountId(Context ctx, String email, String acctId) {
        prefs(ctx).edit().putString(KEY_ACCT_ID_PREFIX + email, acctId).apply();
    }

    public static String getAccountId(Context ctx, String email) {
        return prefs(ctx).getString(KEY_ACCT_ID_PREFIX + email, null);
    }

    // ---- first (default) account --------------------------------------------

    public static String getDefaultAccount(Context ctx) {
        Set<String> accounts = getAccounts(ctx);
        return accounts.isEmpty() ? null : accounts.iterator().next();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
