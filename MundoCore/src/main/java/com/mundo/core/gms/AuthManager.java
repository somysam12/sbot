/*
 * SPDX-FileCopyrightText: 2023 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 *
 * Adapted for MundoCore virtual space:
 *   - Package changed to com.mundo.core.gms
 *   - AccountManager replaced with GmsTokenStore + GmsTokenStore.getUserData/setUserData
 *   - PackageUtils.firstSignatureDigest replaced with GmsSignatureHelper.getCallerCertSha1
 *   - isTrustGooglePermitted → true (virtual space always trusts Google auth)
 *   - ErrorResolverKt, SettingsContract, AuthPrefs, AccountManager removed
 *   - accountExists() uses GmsTokenStore.hasAccount()
 *   - requestAuthWithBackground/ForegroundResolution removed (needs ErrorResolverKt)
 */

package com.mundo.core.gms;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static android.content.pm.ApplicationInfo.FLAG_SYSTEM;
import static android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;

public class AuthManager {

    private static final String TAG = "GmsAuthManager";
    public static final String PERMISSION_TREE_BASE = "com.google.android.googleapps.permission.GOOGLE_AUTH.";
    public static final int ONE_HOUR_IN_SECONDS = 60 * 60;

    public Map<Object, Object> dynamicFields = new HashMap<>();
    private final Context context;
    private final String accountName;
    private final String packageName;
    private final String service;
    private String packageSignature;
    private String accountType;

    private int delegationType;
    private String delegateeUserId;
    private String oauth2Foreground;
    private String oauth2Prompt;
    private String itCaveatTypes;
    private String tokenRequestOptions;
    public String includeEmail;
    public String includeProfile;
    public String includeGrantedScopes;
    public boolean isGmsApp;
    public boolean ignoreStoredPermission = false;
    public boolean forceRefreshToken = false;
    public boolean isPermitted = false;

    public AuthManager(Context context, String accountName, String packageName, String service) {
        this.context = context.getApplicationContext();
        this.accountName = accountName;
        this.packageName = packageName != null ? packageName : AuthRequest.GMS_PACKAGE_NAME;
        this.service = service;
    }

    public String getAccountType() {
        if (accountType == null)
            accountType = "com.google";
        return accountType;
    }

    public void setAccountType(String accountType) {
        this.accountType = accountType;
    }

    public void setPackageSignature(String packageSignature) {
        this.packageSignature = packageSignature;
    }

    public String getPackageSignature() {
        if (packageSignature == null)
            packageSignature = GmsSignatureHelper.getCallerCertSha1(packageName);
        return packageSignature;
    }

    public String buildTokenKey(String service) {
        Uri.Builder builder = Uri.EMPTY.buildUpon();
        if (delegationType != 0 && delegateeUserId != null)
            builder.appendQueryParameter("delegation_type", Integer.toString(delegationType))
                    .appendQueryParameter("delegatee_user_id", delegateeUserId);
        if (tokenRequestOptions != null)
            builder.appendQueryParameter("token_request_options", tokenRequestOptions);
        if (includeEmail != null)
            builder.appendQueryParameter("include_email", includeEmail);
        if (includeProfile != null)
            builder.appendQueryParameter("include_profile", includeProfile);
        String query = builder.build().getEncodedQuery();
        return packageName + ":" + getPackageSignature() + ":" + service + (query != null ? ("?" + query) : "");
    }

    public String buildTokenKey() {
        return buildTokenKey(service);
    }

    public String buildPermKey() {
        return "perm." + buildTokenKey();
    }

    public void setPermitted(boolean value) {
        isPermitted = value;
        GmsTokenStore.setUserData(context, accountName, buildPermKey(), value ? "1" : "0");
    }

    public boolean isPermitted() {
        if (isPermitted) return true;
        if (!service.startsWith("oauth")) {
            if (context.getPackageManager().checkPermission(
                    PERMISSION_TREE_BASE + service, packageName) == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        String perm = getUserData(buildPermKey());
        return "1".equals(perm);
    }

    public void setExpiry(long expiry) {
        GmsTokenStore.setUserData(context, accountName, "EXP." + buildTokenKey(), Long.toString(expiry));
    }

    public String getUserData(String key) {
        return GmsTokenStore.getUserData(context, accountName, key);
    }

    public void setUserData(String key, String value) {
        GmsTokenStore.setUserData(context, accountName, key, value);
    }

    public void setDelegation(int delegationType, String delegateeUserId) {
        if (delegationType != 0 && delegateeUserId != null) {
            this.delegationType = delegationType;
            this.delegateeUserId = delegateeUserId;
        } else {
            this.delegationType = 0;
            this.delegateeUserId = null;
        }
    }

    public void setOauth2Foreground(String oauth2Foreground) {
        this.oauth2Foreground = oauth2Foreground;
    }

    public void setOauth2Prompt(String oauth2Prompt) {
        this.oauth2Prompt = oauth2Prompt;
    }

    public void setItCaveatTypes(String itCaveatTypes) {
        this.itCaveatTypes = itCaveatTypes;
    }

    public void setTokenRequestOptions(String tokenRequestOptions) {
        this.tokenRequestOptions = tokenRequestOptions;
    }

    public void putDynamicFiled(Object key, Object value) {
        this.dynamicFields.put(key, value);
    }

    public boolean accountExists() {
        return GmsTokenStore.hasAccount(context, accountName);
    }

    public String peekAuthToken() {
        return GmsTokenStore.getUserData(context, accountName, buildTokenKey());
    }

    public String getAuthToken() {
        if (service.startsWith("weblogin:")) return null;
        if (System.currentTimeMillis() / 1000L >= getExpiry() - 300L) {
            Log.d(TAG, "token present, but expired");
            return null;
        }
        return peekAuthToken();
    }

    public String buildExpireKey() {
        return "EXP." + buildTokenKey();
    }

    public long getExpiry() {
        String exp = getUserData(buildExpireKey());
        if (exp == null) return -1;
        try { return Long.parseLong(exp); } catch (Exception e) { return -1; }
    }

    public void setAuthToken(String auth) {
        setAuthToken(service, auth);
    }

    public void setAuthToken(String service, String auth) {
        GmsTokenStore.setUserData(context, accountName, buildTokenKey(service), auth);
    }

    public void invalidateAuthToken() {
        String auth = peekAuthToken();
        invalidateAuthToken(auth);
    }

    public void invalidateAuthToken(String auth) {
        GmsTokenStore.setUserData(context, accountName, buildTokenKey(), null);
        GmsTokenStore.setUserData(context, accountName, buildExpireKey(), null);
    }

    public void storeResponse(AuthResponse response) {
        if (service.startsWith("weblogin:")) return;
        if (response.accountId != null)
            setUserData("GoogleUserId", response.accountId);
        if (response.Sid != null)
            setAuthToken("SID", response.Sid);
        if (response.LSid != null)
            setAuthToken("LSID", response.LSid);
        if (response.auth != null && (response.expiry != 0 || response.storeConsentRemotely)) {
            setAuthToken(response.auth);
            if (response.expiry > 0) {
                setExpiry(response.expiry);
            } else {
                setExpiry(System.currentTimeMillis() / 1000 + ONE_HOUR_IN_SECONDS);
            }
        }
    }

    private boolean isSystemApp() {
        try {
            int flags = context.getPackageManager().getApplicationInfo(packageName, 0).flags;
            return (flags & FLAG_SYSTEM) > 0 || (flags & FLAG_UPDATED_SYSTEM_APP) > 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public AuthResponse requestAuth(boolean legacy) throws IOException {
        // Always trust in virtual space — the guest app already has permission
        boolean trusted = true;

        if (trusted || isPermitted()) {
            String token = getAuthToken();
            if (token != null && !forceRefreshToken) {
                AuthResponse response = new AuthResponse();
                response.issueAdvice = "stored";
                response.auth = token;
                if (service.startsWith("oauth2:")) {
                    response.grantedScopes = service.substring(7);
                }
                response.expiry = getExpiry();
                return response;
            }
        }

        String masterToken = GmsTokenStore.getMasterToken(context, accountName);
        if (masterToken == null) {
            Log.w(TAG, "No master token for " + accountName);
            AuthResponse empty = new AuthResponse();
            return empty;
        }

        AuthRequest request = new AuthRequest().fromContext(context)
                .source("android")
                .app(packageName, getPackageSignature())
                .email(accountName)
                .token(masterToken)
                .service(service)
                .delegation(delegationType, delegateeUserId)
                .oauth2Foreground(oauth2Foreground)
                .oauth2Prompt(oauth2Prompt)
                .oauth2IncludeProfile(includeProfile)
                .oauth2IncludeEmail(includeEmail)
                .includeGrantedScopes(includeGrantedScopes)
                .itCaveatTypes(itCaveatTypes)
                .tokenRequestOptions(tokenRequestOptions)
                .systemPartition(isSystemApp())
                .hasPermission(!ignoreStoredPermission && isPermitted())
                .putDynamicFiledMap(dynamicFields);
        if (isGmsApp) {
            request.appIsGms();
        }
        if (legacy) {
            request.callerIsGms().calledFromAccountManager();
        } else {
            request.callerIsApp();
        }
        AuthResponse response = request.getResponse();
        storeResponse(response);
        return response;
    }

    public String getService() {
        return service;
    }
}
