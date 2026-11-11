package com.mundo.proxy.gms

import android.content.Context
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe lazy initializer for GMS intercept hooks.
 *
 * Problem: MundoCore.init(context) only runs in the host's main process (com.bgmi).
 * BlackBox starts each cloned app in a SEPARATE child process (e.g. com.bgmi:p0).
 * That child process never calls MundoCore.init(), so GmsSignInInterceptor is uninitialized
 * when the cloned app calls bindService("com.google.android.gms").
 *
 * Fix: This object provides ensureInitialized() which is called from every GMS bindService
 * intercept point (IActivityManagerProxy, ActivityManagerCommonProxy). It retrieves the host
 * application context via ActivityThread reflection — this works in any process that runs
 * under the host package (com.bgmi), including all BlackBox virtual processes.
 */
object GmsHookInstaller {

    private const val TAG = "MundoGms"
    private val installed = AtomicBoolean(false)

    @Volatile
    private var hostContext: Context? = null

    /**
     * Full init from host's main process. Called by MundoCore.init(context).
     * No-ops if already installed.
     */
    @JvmStatic
    fun install(context: Context) {
        if (installed.compareAndSet(false, true)) {
            hostContext = context.applicationContext
            Log.w(TAG, "GmsHookInstaller.install: main-process init [${getProcessName()}]")
        }
    }

    /**
     * Lazy init for child (virtual) processes.
     *
     * Called from every GMS bindService intercept point before forwarding to
     * GmsSignInInterceptor. Uses ActivityThread reflection to obtain the host
     * Application context — works before Application.onCreate() completes.
     *
     * If context is not yet available (too early in startup), sets nothing and
     * returns false so the caller can fall through to real GMS gracefully.
     */
    @JvmStatic
    fun ensureInitialized(): Boolean {
        if (installed.get()) return true
        if (!installed.compareAndSet(false, true)) return hostContext != null

        val ctx = getContextViaReflection()
        return if (ctx != null) {
            hostContext = ctx
            Log.w(TAG, "GmsHookInstaller.ensureInitialized: lazy init in [${getProcessName()}]")
            true
        } else {
            // Reset flag so we retry on the next bindService call
            installed.set(false)
            Log.w(TAG, "GmsHookInstaller.ensureInitialized: no context yet, will retry [${getProcessName()}]")
            false
        }
    }

    @JvmStatic
    fun isInstalled(): Boolean = installed.get() && hostContext != null

    @JvmStatic
    fun getHostContext(): Context? = hostContext

    // ----- Reflection helpers -----

    private fun getContextViaReflection(): Context? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAT = atClass.getMethod("currentActivityThread").invoke(null)
                ?: return null
            // getApplication() returns the host Application — same as what MundoCore.init received
            atClass.getMethod("getApplication").invoke(currentAT) as? Context
        } catch (e: Exception) {
            Log.w(TAG, "GmsHookInstaller: ActivityThread reflection failed: ${e.message}")
            null
        }
    }

    fun getProcessName(): String {
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                android.app.Application.getProcessName()
            } else {
                val atClass = Class.forName("android.app.ActivityThread")
                val currentAT = atClass.getMethod("currentActivityThread").invoke(null)
                atClass.getMethod("getProcessName").invoke(currentAT) as? String ?: "unknown"
            }
        } catch (_: Exception) { "unknown" }
    }

    /**
     * True when the current process is a BlackBox virtual app process.
     * Virtual processes are named like com.bgmi:p0, com.bgmi:p1, com.bgmi:virtual, etc.
     * NOT: com.bgmi (main) or com.bgmi:core (GmsAuthService).
     */
    fun isVirtualProcess(): Boolean {
        val pn = getProcessName()
        val ctx = hostContext ?: return false
        val pkg = ctx.packageName
        return pn != pkg && pn != "$pkg:core" && pn.startsWith("$pkg:")
    }
}
