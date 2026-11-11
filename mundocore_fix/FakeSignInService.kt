package com.mundo.core.gms

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Fake ISignInService Binder stub.
 * Handles Binder transaction codes:
 *   101 = signIn(options, callback)
 *   102 = signOut(callback)
 *   103 = revokeAccess(callback)
 *   104 = silentSignIn(options, callback)
 *
 * Fix #13: use enforceInterface() then reset position on each onTransact call.
 */
class FakeSignInService(
    private val context: Context,
    private val callingPackage: String
) : Binder() {

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        Log.w(GmsConstants.TAG, "FakeSignInService.onTransact: code=$code pkg=$callingPackage")

        // Fix #13: enforce interface then reset position
        data.enforceInterface("com.google.android.gms.auth.api.signin.internal.ISignInService")
        data.setDataPosition(0)

        return when (code) {
            GmsConstants.SIGN_IN_CODE -> {
                handleSignIn(data, reply)
                true
            }
            GmsConstants.SIGN_OUT_CODE -> {
                handleSignOut(data, reply)
                true
            }
            GmsConstants.REVOKE_ACCESS_CODE -> {
                handleRevokeAccess(data, reply)
                true
            }
            GmsConstants.SIGN_IN_SILENT_CODE -> {
                handleSilentSignIn(data, reply)
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }

    private fun handleSignIn(data: Parcel, reply: Parcel?) {
        // Read options Bundle and callback Binder
        val optionsBundle = data.readBundle(javaClass.classLoader)
        val callbackBinder = data.readStrongBinder()

        val options = SignInOptions.fromBundle(optionsBundle)
        Log.w(GmsConstants.TAG, "FakeSignInService.signIn: options=$options callback=${callbackBinder != null}")

        // Check if we have a cached account for this package
        if (GmsTokenStore.hasAccount(callingPackage)) {
            val account = buildCachedAccount(callingPackage, options)
            deliverSignInResult(callbackBinder, account, 0)
        } else {
            // Launch consent/login activity
            launchSignInActivity(callbackBinder, options)
        }

        reply?.writeNoException()
    }

    private fun handleSignOut(data: Parcel, reply: Parcel?) {
        GmsTokenStore.clearAccount(callingPackage)
        Log.w(GmsConstants.TAG, "FakeSignInService.signOut: cleared account for $callingPackage")
        reply?.writeNoException()
    }

    private fun handleRevokeAccess(data: Parcel, reply: Parcel?) {
        GmsTokenStore.clearAccount(callingPackage)
        Log.w(GmsConstants.TAG, "FakeSignInService.revokeAccess: cleared account for $callingPackage")
        reply?.writeNoException()
    }

    private fun handleSilentSignIn(data: Parcel, reply: Parcel?) {
        val optionsBundle = data.readBundle(javaClass.classLoader)
        val callbackBinder = data.readStrongBinder()
        val options = SignInOptions.fromBundle(optionsBundle)

        if (GmsTokenStore.hasAccount(callingPackage)) {
            val account = buildCachedAccount(callingPackage, options)
            Log.w(GmsConstants.TAG, "FakeSignInService.silentSignIn: returning cached account")
            deliverSignInResult(callbackBinder, account, 0)
        } else {
            Log.w(GmsConstants.TAG, "FakeSignInService.silentSignIn: no cached account, error=4 (SIGN_IN_REQUIRED)")
            deliverSignInError(callbackBinder, 4) // 4 = SIGN_IN_REQUIRED
        }

        reply?.writeNoException()
    }

    private fun buildCachedAccount(pkg: String, options: SignInOptions): GoogleSignInAccount {
        return GoogleSignInAccount(
            email = GmsTokenStore.getEmail(pkg) ?: "",
            id = GmsTokenStore.getAccountId(pkg) ?: "",
            displayName = GmsTokenStore.getDisplayName(pkg) ?: "",
            photoUrl = GmsTokenStore.getPhotoUrl(pkg),
            idToken = if (options.requestIdToken) GmsTokenStore.getIdToken(pkg) else null
        )
    }

    private fun launchSignInActivity(callbackBinder: IBinder?, options: SignInOptions) {
        val extras = android.os.Bundle().apply {
            putString("callingPackage", callingPackage)
            putBinder("callback", callbackBinder)
            putBoolean("requestIdToken", options.requestIdToken)
        }
        val intent = Intent(context, Class.forName("com.mundo.proxy.gms.ConsentSignInActivity"))
            .apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtras(extras)
            }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(GmsConstants.TAG, "FakeSignInService: failed to launch sign-in activity: ${e.message}")
            deliverSignInError(callbackBinder, 8) // 8 = INTERNAL_ERROR
        }
    }

    // Fix #4: deliverResult callback — deliver sign-in result back to virtual app
    fun deliverSignInResult(callbackBinder: IBinder?, account: GoogleSignInAccount, errorCode: Int) {
        if (callbackBinder == null) {
            Log.w(GmsConstants.TAG, "FakeSignInService.deliverResult: null callback binder")
            return
        }
        try {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("com.google.android.gms.auth.api.signin.internal.ISignInCallbacks")
                data.writeInt(errorCode)
                if (errorCode == 0) {
                    val resultBundle = GoogleSignInAccount.buildResultBundle(account)
                    data.writeBundle(resultBundle)
                }
                // Binder code 1 = onSignInComplete
                callbackBinder.transact(1, data, reply, 0)
                reply.readException()
                Log.w(GmsConstants.TAG, "FakeSignInService.deliverResult: delivered account ${account.email}")
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (e: Exception) {
            Log.w(GmsConstants.TAG, "FakeSignInService.deliverResult: failed: ${e.message}")
        }
    }

    private fun deliverSignInError(callbackBinder: IBinder?, errorCode: Int) {
        deliverSignInResult(callbackBinder, GoogleSignInAccount("", "", ""), errorCode)
    }

    companion object {
        fun deliverSignInResult(callbackBinder: IBinder?, account: GoogleSignInAccount, errorCode: Int) {
            if (callbackBinder == null) return
            try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken("com.google.android.gms.auth.api.signin.internal.ISignInCallbacks")
                    data.writeInt(errorCode)
                    if (errorCode == 0) {
                        val resultBundle = GoogleSignInAccount.buildResultBundle(account)
                        data.writeBundle(resultBundle)
                    }
                    callbackBinder.transact(1, data, reply, 0)
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } catch (e: Exception) {
                Log.w(GmsConstants.TAG, "FakeSignInService.deliverResult (static): ${e.message}")
            }
        }
    }
}
