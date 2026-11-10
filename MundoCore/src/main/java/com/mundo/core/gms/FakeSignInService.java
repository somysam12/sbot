package com.mundo.core.gms;

import android.accounts.Account;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/**
 * Fake Binder that implements com.google.android.gms.auth.api.signin.internal.ISignInService.
 *
 * Transaction codes from ISignInService.aidl (microG play-services-auth):
 *   silentSignIn(ISignInCallbacks, GoogleSignInOptions) = 100
 *   signOut    (ISignInCallbacks, GoogleSignInOptions) = 101
 *   revokeAccess(ISignInCallbacks, GoogleSignInOptions) = 102
 *
 * ISignInCallbacks callbacks:
 *   onSignIn (GoogleSignInAccount, Status) = 100
 *   onSignOut(Status)                      = 101
 *
 * We only implement silentSignIn properly; signOut/revokeAccess return OK status.
 */
public class FakeSignInService extends Binder {

    private static final String TAG = "FakeSignInService";

    // Transaction codes
    private static final int TRANSACTION_SILENT_SIGN_IN  = 100;
    private static final int TRANSACTION_SIGN_OUT        = 101;
    private static final int TRANSACTION_REVOKE_ACCESS   = 102;

    // ISignInCallbacks transaction codes
    private static final int CB_ON_SIGN_IN   = 100;
    private static final int CB_ON_SIGN_OUT  = 101;

    // Status codes (Google Play Services internal)
    private static final int STATUS_OK             = 0;
    private static final int STATUS_SIGN_IN_FAILED = 10;
    private static final int STATUS_CANCELED       = 16;

    private final Context ctx;
    private final String  callerPkg;

    public FakeSignInService(Context ctx, String callerPkg) {
        this.ctx       = ctx.getApplicationContext();
        this.callerPkg = callerPkg;
        attachInterface(null, "com.google.android.gms.auth.api.signin.internal.ISignInService");
    }

    @Override
    public String getInterfaceDescriptor() {
        return "com.google.android.gms.auth.api.signin.internal.ISignInService";
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        switch (code) {
            case TRANSACTION_SILENT_SIGN_IN:
                data.enforceInterface("com.google.android.gms.auth.api.signin.internal.ISignInService");
                IBinder callbackBinder = data.readStrongBinder();
                // GoogleSignInOptions is the next parcelable — we ignore it for silent sign-in
                handleSilentSignIn(callbackBinder);
                if (reply != null) reply.writeNoException();
                return true;

            case TRANSACTION_SIGN_OUT:
                data.enforceInterface("com.google.android.gms.auth.api.signin.internal.ISignInService");
                IBinder outCb = data.readStrongBinder();
                sendSignOutCallback(outCb);
                if (reply != null) reply.writeNoException();
                return true;

            case TRANSACTION_REVOKE_ACCESS:
                data.enforceInterface("com.google.android.gms.auth.api.signin.internal.ISignInService");
                IBinder revCb = data.readStrongBinder();
                sendSignOutCallback(revCb); // same response shape as sign-out
                if (reply != null) reply.writeNoException();
                return true;

            default:
                return super.onTransact(code, data, reply, flags);
        }
    }

    // ---- sign-in flow -------------------------------------------------------

    private void handleSilentSignIn(IBinder callbackBinder) {
        // Run on a background thread — we may hit the network
        new Thread(() -> {
            String email = GmsTokenStore.getDefaultAccount(ctx);
            if (email == null) {
                Log.d(TAG, "silentSignIn: no account stored");
                sendSignInFailCallback(callbackBinder, STATUS_SIGN_IN_FAILED);
                return;
            }
            try {
                GoogleSignInAccount account = buildSignInAccount(email);
                if (account != null) {
                    sendSignInSuccessCallback(callbackBinder, account);
                } else {
                    sendSignInFailCallback(callbackBinder, STATUS_SIGN_IN_FAILED);
                }
            } catch (Exception e) {
                Log.w(TAG, "silentSignIn exception: " + e.getMessage());
                sendSignInFailCallback(callbackBinder, STATUS_SIGN_IN_FAILED);
            }
        }, "GmsSignIn").start();
    }

    private GoogleSignInAccount buildSignInAccount(String email) {
        // Fetch a fresh id-token (openid scope) + profile scopes
        String service = "oauth2:profile email openid";
        GmsAuthManager mgr = new GmsAuthManager(ctx, email, callerPkg, null);
        String accessToken = mgr.requestToken(service);
        if (accessToken == null) {
            Log.w(TAG, "buildSignInAccount: could not get access token for " + email);
            // Try to still return an account with just cached id-token
            String cachedId = GmsTokenStore.getIdToken(ctx, email);
            if (cachedId == null) return null;
        }

        String idToken     = GmsTokenStore.getIdToken(ctx, email);
        String accountId   = GmsTokenStore.getAccountId(ctx, email);
        String displayName = GmsTokenStore.getDisplayName(ctx, email);
        long   expiry      = System.currentTimeMillis() / 1000 + 3600;

        return new GoogleSignInAccount(
                accountId   != null ? accountId   : "",
                idToken     != null ? idToken     : "",
                email,
                displayName != null ? displayName : email,
                "", // givenName — not separately stored
                "", // familyName
                null,  // photoUrl
                null,  // serverAuthCode
                expiry,
                accountId   != null ? accountId   : ""
        );
    }

    // ---- callback helpers ---------------------------------------------------

    private void sendSignInSuccessCallback(IBinder cb, GoogleSignInAccount account) {
        try {
            Parcel data  = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInterfaceToken(
                    "com.google.android.gms.auth.api.signin.internal.ISignInCallbacks");
            // Write GoogleSignInAccount parcelable (nullable)
            data.writeInt(1); // non-null marker
            account.writeToParcel(data, 0);
            // Write Status parcelable — just status code OK
            writeOkStatus(data);
            cb.transact(CB_ON_SIGN_IN, data, reply, 0);
            data.recycle();
            reply.recycle();
            Log.d(TAG, "sendSignInSuccessCallback done for " + account.email);
        } catch (Exception e) {
            Log.w(TAG, "sendSignInSuccessCallback: " + e.getMessage());
        }
    }

    private void sendSignInFailCallback(IBinder cb, int statusCode) {
        try {
            Parcel data  = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInterfaceToken(
                    "com.google.android.gms.auth.api.signin.internal.ISignInCallbacks");
            data.writeInt(0); // null account
            writeStatus(data, statusCode);
            cb.transact(CB_ON_SIGN_IN, data, reply, 0);
            data.recycle();
            reply.recycle();
        } catch (Exception e) {
            Log.w(TAG, "sendSignInFailCallback: " + e.getMessage());
        }
    }

    private void sendSignOutCallback(IBinder cb) {
        try {
            Parcel data  = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInterfaceToken(
                    "com.google.android.gms.auth.api.signin.internal.ISignInCallbacks");
            writeOkStatus(data);
            cb.transact(CB_ON_SIGN_OUT, data, reply, 0);
            data.recycle();
            reply.recycle();
        } catch (Exception e) {
            Log.w(TAG, "sendSignOutCallback: " + e.getMessage());
        }
    }

    // Status SafeParcel: field 1 = statusCode(int), field 2 = statusMessage(string)
    private void writeOkStatus(Parcel dest) {
        writeStatus(dest, STATUS_OK);
    }

    private void writeStatus(Parcel dest, int code) {
        // Status is a SafeParcel object
        dest.writeInt(SafeParcelHelper.SAFE_PARCEL_INDICATOR);
        int start = dest.dataPosition();
        dest.writeInt(0); // size placeholder
        SafeParcelHelper.writeInt(dest, 1, code); // statusCode
        if (code != STATUS_OK) {
            SafeParcelHelper.writeString(dest, 2, "Sign-in failed: " + code);
        }
        int end = dest.dataPosition();
        dest.setDataPosition(start);
        dest.writeInt(end - start - 4);
        dest.setDataPosition(end);
    }
}
