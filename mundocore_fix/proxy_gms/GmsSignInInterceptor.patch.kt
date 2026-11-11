/*
 * PATCH FILE — GmsSignInInterceptor.patch.kt
 *
 * Apply these changes to: MundoCore/proxy/gms/GmsSignInInterceptor.kt
 * (the file created in Section 7 of MundoCore_Complete_Session_Log.md)
 *
 * ONE change needed:
 *   Replace the beginning of onBindService() to use GmsHookInstaller
 *   instead of a direct context reference that is null in child processes.
 *
 * The session log shows onBindService() looked up hostContext directly.
 * The fix: call GmsHookInstaller.ensureInitialized() first; use
 * GmsHookInstaller.getHostContext() for all context operations.
 */

// ─────────────────────────────────────────────────────────────────────────────
// CHANGE: Replace the top of onBindService() with this guard block
// (insert BEFORE any context usage; keep the rest of the method as-is)
// ─────────────────────────────────────────────────────────────────────────────

    @JvmStatic
    fun onBindService(
        caller: Any?,               // IApplicationThread
        service: Intent,
        connection: Any?,           // IServiceConnection
        flags: Int
    ) {
        // ── NEW: lazy-init guard ───────────────────────────────────────────
        // In child virtual processes (com.bgmi:p0, etc.), MundoCore.init() was
        // never called. GmsHookInstaller.ensureInitialized() retrieves the host
        // Application context via ActivityThread reflection.
        if (!GmsHookInstaller.ensureInitialized()) {
            Log.w(TAG, "onBindService: GmsHookInstaller not ready — cannot intercept")
            return
        }
        val context = GmsHookInstaller.getHostContext() ?: run {
            Log.w(TAG, "onBindService: hostContext null after init — cannot intercept")
            return
        }
        // ── END NEW ───────────────────────────────────────────────────────

        // Keep the rest of onBindService() body unchanged from the session log.
        // The original body used a private `hostContext` field — replace those
        // references with the local `context` variable above.
        //
        // Example (adjust to match your actual implementation):
        Log.w(TAG, "onBindService: intercepted bindService to ${GmsConstants.GMS_PACKAGE} " +
                "in process [${GmsHookInstaller.getProcessName()}]")

        // 1. Determine which GMS service is being requested
        val serviceId = service.getIntExtra("android.intent.extra.GMS_SERVICE_ID",
            GmsConstants.SIGN_IN_SERVICE_ID)

        if (serviceId != GmsConstants.SIGN_IN_SERVICE_ID) {
            // Not Sign-In — bind real GMS for this service (with package rewriting)
            bindRealGms(context, service, connection, flags)
            return
        }

        // 2. Deliver FakeGmsServiceBroker to the calling app
        try {
            val fakeBroker = FakeGmsServiceBroker(context)
            deliverBroker(connection, fakeBroker)
        } catch (e: Exception) {
            Log.w(TAG, "onBindService: failed to deliver FakeGmsServiceBroker: ${e.message}")
            // Fall through to real GMS so the app doesn't hang
            bindRealGms(context, service, connection, flags)
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// ALSO: If GmsSignInInterceptor has an installHooks(context) method called from
// MundoCore.init(), replace its body with:
// ─────────────────────────────────────────────────────────────────────────────

    @JvmStatic
    fun installHooks(context: Context) {
        GmsHookInstaller.install(context)
        Log.w(TAG, "installHooks: GmsHookInstaller installed from main process")
        // Any other one-time setup (e.g. GmsTokenStore init) can go here
    }

// ─────────────────────────────────────────────────────────────────────────────
// IMPORT additions:
// ─────────────────────────────────────────────────────────────────────────────
/*
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mundo.proxy.gms.GmsHookInstaller
import com.mundo.core.gms.GmsConstants
import com.mundo.core.gms.FakeGmsServiceBroker
*/
