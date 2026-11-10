package com.mundo.proxy.gms;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.mundo.core.gms.FakeGmsServiceBroker;
import com.mundo.core.gms.GmsTokenStore;
import com.mundo.utils.Slog;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Central helper called from the BindService hook in IActivityManagerProxy.
 *
 * When a guest app tries to bind to com.google.android.gms (GMS), we intercept
 * it and return a FakeGmsServiceBroker Binder directly — no real GMS needed.
 *
 * We also detect intents that want to show a Google sign-in UI (startActivity)
 * and redirect them to our GoogleLoginActivity instead.
 */
public class GmsSignInInterceptor {

    private static final String TAG = "GmsSignInInterceptor";

    // GMS package name as seen on the wire
    public static final String GMS_PKG = "com.google.android.gms";

    // Known GMS sign-in service component names
    private static final Set<String> GMS_SERVICE_COMPONENTS = new HashSet<>(Arrays.asList(
            "com.google.android.gms/.chimera.GmsApiService",
            "com.google.android.gms/com.google.android.gms.chimera.GmsApiService",
            "com.google.android.gms/.auth.api.signin.RevealTokenActivity",
            "com.google.android.gms/com.google.android.gms.common.api.internal.GoogleApiActivity",
            "com.google.android.gms/com.google.android.gms.auth.api.signin.internal.SignInHubActivity"
    ));

    // Intent actions that trigger Google sign-in UI
    private static final Set<String> SIGN_IN_ACTIONS = new HashSet<>(Arrays.asList(
            "com.google.android.gms.auth.GOOGLE_SIGN_IN",
            "com.google.android.gms.auth.api.signin.GOOGLE_SIGN_IN",
            "com.google.android.gms.auth.api.signin.internal.SIGN_IN"
    ));

    /**
     * Returns true if the given bind intent targets GMS and should be intercepted.
     */
    public static boolean shouldInterceptBind(Intent intent) {
        if (intent == null) return false;

        // By package name
        if (GMS_PKG.equals(intent.getPackage())) return true;

        // By component
        ComponentName comp = intent.getComponent();
        if (comp != null && GMS_PKG.equals(comp.getPackageName())) return true;

        // By well-known Chimera service action
        String action = intent.getAction();
        if ("com.google.android.c2dm.intent.REGISTER".equals(action)) return false; // skip GCM
        if (action != null && action.startsWith("com.google.android.gms")) return true;

        return false;
    }

    /**
     * Returns a FakeGmsServiceBroker IBinder for the guest to use.
     * Called from the BindService hook in place of calling through to real GMS.
     */
    public static IBinder getFakeBroker(Context ctx) {
        return new FakeGmsServiceBroker(ctx);
    }

    /**
     * Returns true if the given startActivity intent is a Google sign-in UI request
     * that we want to redirect to GoogleLoginActivity.
     */
    public static boolean shouldInterceptSignInUi(Intent intent) {
        if (intent == null) return false;

        String action = intent.getAction();
        if (action != null && SIGN_IN_ACTIONS.contains(action)) return true;

        ComponentName comp = intent.getComponent();
        if (comp != null) {
            String flat = comp.getPackageName() + "/" + comp.getClassName();
            if (GMS_SERVICE_COMPONENTS.contains(flat)) return true;
        }
        return false;
    }

    /**
     * Returns an Intent that opens our GoogleLoginActivity in the host process.
     */
    public static Intent buildLoginActivityIntent(Context ctx) {
        Intent intent = new Intent(ctx, GoogleLoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /**
     * Returns true if there is already a signed-in account stored.
     */
    public static boolean hasSignedInAccount(Context ctx) {
        return GmsTokenStore.getDefaultAccount(ctx) != null;
    }
}
