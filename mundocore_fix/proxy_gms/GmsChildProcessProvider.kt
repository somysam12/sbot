package com.mundo.proxy.gms

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log

/**
 * ContentProvider that initializes GMS hooks early in BlackBox virtual processes.
 *
 * Problem: MundoCore.init(context) only runs in the host's main process.
 * BlackBox child processes (com.bgmi:p0, etc.) never call it, so
 * GmsSignInInterceptor is uninitialized when the virtual app calls bindService.
 *
 * Fix: This ContentProvider's onCreate() fires before Application.onCreate()
 * in every process that instantiates it. With android:multiprocess="true" in
 * the manifest, Android creates an instance in each BlackBox virtual process
 * that resolves a ContentProvider authority during startup.
 *
 * GmsHookInstaller.ensureInitialized() in IActivityManagerProxy.bindService()
 * is the primary fix; this ContentProvider is an early-init belt-and-suspenders.
 *
 * Register in AndroidManifest.xml (see AndroidManifest.patch.xml):
 *   <provider
 *       android:name="com.mundo.proxy.gms.GmsChildProcessProvider"
 *       android:authorities="${applicationId}.mundogms"
 *       android:exported="false"
 *       android:multiprocess="true"
 *       android:initOrder="100" />
 */
class GmsChildProcessProvider : ContentProvider() {

    companion object {
        private const val TAG = "MundoGms"
    }

    override fun onCreate(): Boolean {
        val ctx = context ?: return false

        try {
            val processName = GmsHookInstaller.getProcessName()
            val pkg = ctx.packageName

            Log.w(TAG, "GmsChildProcessProvider.onCreate: process=[$processName]")

            // Skip main process — MundoCore.init() handles that
            // Skip :core — that's the GmsAuthService process
            if (processName == pkg || processName == "$pkg:core") {
                return true
            }

            // This is a virtual app process (e.g. com.bgmi:p0)
            if (processName.startsWith("$pkg:")) {
                Log.w(TAG, "GmsChildProcessProvider: virtual process detected, early GMS init")
                GmsHookInstaller.install(ctx)
            }
        } catch (e: Exception) {
            Log.w(TAG, "GmsChildProcessProvider.onCreate error: ${e.message}")
        }

        return true
    }

    // Required stub implementations
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
}
