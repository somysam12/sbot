/*
 * PATCH FILE — IActivityManagerProxy.kt
 *
 * Apply these changes to: MundoCore/hooks/IActivityManagerProxy.kt
 * (the file modified in Section 8.1 of MundoCore_Complete_Session_Log.md)
 *
 * TWO changes needed:
 *   1. In bindService() — add GmsHookInstaller.ensureInitialized() before the GMS check
 *   2. ADD bindServiceInstance() override below bindService() — covers Android API 31+
 *      where the framework calls bindServiceInstance() instead of bindService()
 *
 * Both changes are shown as COMPLETE replacement blocks below.
 */

// ─────────────────────────────────────────────────────────────────────────────
// CHANGE 1: Replace the existing bindService() override with this version
// ─────────────────────────────────────────────────────────────────────────────

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
            // Lazy-init in child (virtual) processes — no-op in main process (already installed)
            if (GmsHookInstaller.ensureInitialized()) {
                Log.w("SignInIntercept", "bindService GMS intercepted in [${GmsHookInstaller.getProcessName()}]")
                GmsSignInInterceptor.onBindService(caller, service, connection, flags)
                return 0
            }
            Log.w("SignInIntercept", "bindService GMS: interceptor not ready, passing through")
        }
        return original.bindService(caller, token, service, resolvedType, connection, flags, userId)
    }

// ─────────────────────────────────────────────────────────────────────────────
// CHANGE 2: ADD this new method directly after bindService() — API 31+ fix
// Android 12+ calls bindServiceInstance() instead of bindService().
// Without this override, GMS bindService calls on modern Android are NEVER intercepted.
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
                Log.w("SignInIntercept", "bindServiceInstance GMS intercepted in [${GmsHookInstaller.getProcessName()}]")
                GmsSignInInterceptor.onBindService(caller, service, connection, flags)
                return 0
            }
            Log.w("SignInIntercept", "bindServiceInstance GMS: interceptor not ready, passing through")
        }
        return original.bindServiceInstance(caller, token, service, resolvedType, connection, flags, instanceName, userId)
    }

// ─────────────────────────────────────────────────────────────────────────────
// HELPER — add this private method to IActivityManagerProxy (or add to companion/object)
// ─────────────────────────────────────────────────────────────────────────────

    private fun isGmsIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val pkg = intent.component?.packageName ?: intent.`package`
        return pkg == GmsConstants.GMS_PACKAGE
    }

// ─────────────────────────────────────────────────────────────────────────────
// IMPORT additions needed at top of IActivityManagerProxy.kt
// Add these if not already present:
// ─────────────────────────────────────────────────────────────────────────────
/*
import android.annotation.TargetApi
import com.mundo.proxy.gms.GmsHookInstaller
import com.mundo.proxy.gms.GmsSignInInterceptor
import com.mundo.core.gms.GmsConstants
import android.util.Log
*/
