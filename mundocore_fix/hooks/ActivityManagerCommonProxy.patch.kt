/*
 * PATCH FILE — ActivityManagerCommonProxy.patch.kt
 *
 * Apply these changes to: MundoCore/hooks/ActivityManagerCommonProxy.kt
 * (the file modified in Section 8.2 of MundoCore_Complete_Session_Log.md)
 *
 * ActivityManagerCommonProxy handles the API 29+ path for ActivityManager hooks.
 * It needs the same GmsHookInstaller.ensureInitialized() lazy-init and the
 * new bindServiceInstance() override for API 31+.
 *
 * Find the existing proxyBindService (or bindService) block and replace it,
 * then add bindServiceInstance directly below it.
 */

// ─────────────────────────────────────────────────────────────────────────────
// CHANGE 1: Replace the existing bindService / proxyBindService with this
// ─────────────────────────────────────────────────────────────────────────────

    // The method name may be "bindService" or "proxyBindService" depending on
    // how ActivityManagerCommonProxy is structured in your BlackBox fork.
    // Replace whichever handles the GMS check with this body:

    override fun bindService(
        caller: IApplicationThread,
        token: IBinder,
        service: Intent,
        resolvedType: String?,
        connection: IServiceConnection,
        flags: Int,
        userId: Int
    ): Int {
        if (isGmsIntent(service)) {
            if (GmsHookInstaller.ensureInitialized()) {
                Log.w("SignInIntercept", "CommonProxy.bindService GMS intercepted [${GmsHookInstaller.getProcessName()}]")
                GmsSignInInterceptor.onBindService(caller, service, connection, flags)
                return 0
            }
        }
        return callOriginalBindService(caller, token, service, resolvedType, connection, flags, userId)
    }

// ─────────────────────────────────────────────────────────────────────────────
// CHANGE 2: ADD bindServiceInstance directly after bindService — API 31+ fix
// ─────────────────────────────────────────────────────────────────────────────

    @TargetApi(31)
    override fun bindServiceInstance(
        caller: IApplicationThread,
        token: IBinder,
        service: Intent,
        resolvedType: String?,
        connection: IServiceConnection,
        flags: Int,
        instanceName: String?,
        userId: Int
    ): Int {
        if (isGmsIntent(service)) {
            if (GmsHookInstaller.ensureInitialized()) {
                Log.w("SignInIntercept", "CommonProxy.bindServiceInstance GMS intercepted [${GmsHookInstaller.getProcessName()}]")
                GmsSignInInterceptor.onBindService(caller, service, connection, flags)
                return 0
            }
        }
        return callOriginalBindServiceInstance(caller, token, service, resolvedType, connection, flags, instanceName, userId)
    }

    // callOriginalBindServiceInstance — add this if not already present in your proxy:
    // Uses reflection to call the hidden API (bindServiceInstance was added in API 31)
    @TargetApi(31)
    private fun callOriginalBindServiceInstance(
        caller: IApplicationThread,
        token: IBinder,
        service: Intent,
        resolvedType: String?,
        connection: IServiceConnection,
        flags: Int,
        instanceName: String?,
        userId: Int
    ): Int {
        return try {
            val method = original.javaClass.getMethod(
                "bindServiceInstance",
                IApplicationThread::class.java,
                IBinder::class.java,
                Intent::class.java,
                String::class.java,
                IServiceConnection::class.java,
                Int::class.java,
                String::class.java,
                Int::class.java
            )
            method.invoke(original, caller, token, service, resolvedType, connection, flags, instanceName, userId) as? Int ?: 0
        } catch (e: NoSuchMethodException) {
            // API < 31 device or method removed — fall back to bindService
            Log.w("SignInIntercept", "bindServiceInstance not found, falling back to bindService: ${e.message}")
            original.bindService(caller, token, service, resolvedType, connection, flags, userId)
        } catch (e: Exception) {
            Log.w("SignInIntercept", "bindServiceInstance reflection failed: ${e.message}")
            0
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// HELPER
// ─────────────────────────────────────────────────────────────────────────────

    private fun isGmsIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val pkg = intent.component?.packageName ?: intent.`package`
        return pkg == GmsConstants.GMS_PACKAGE
    }

// ─────────────────────────────────────────────────────────────────────────────
// IMPORT additions needed at top of ActivityManagerCommonProxy.kt:
// ─────────────────────────────────────────────────────────────────────────────
/*
import android.annotation.TargetApi
import com.mundo.proxy.gms.GmsHookInstaller
import com.mundo.proxy.gms.GmsSignInInterceptor
import com.mundo.core.gms.GmsConstants
import android.util.Log
*/
