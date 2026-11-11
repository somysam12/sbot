# MundoCore GMS Fix — Integration Guide

## Root Cause

Google Sign-In silently fails in cloned apps (e.g. BGMI) because:

1. `MundoCore.init(context)` runs only in the host's **main process** (`com.bgmi`, PID 13301).
2. BlackBox starts each cloned app in a **separate child process** (`com.bgmi:p0`, PID 16709).
3. `GmsSignInInterceptor` is uninitialized in that child process.
4. When BGMI calls `bindService("com.google.android.gms")`, the hook fires but the interceptor has no context → silent failure, real GMS rejects the virtual package → login screen returns with no error.

**Secondary cause (Android 12+ devices):** `bindService()` was replaced by `bindServiceInstance()` in API 31. If the hook only covers `bindService()`, it never fires on modern Android.

## Fix Summary

| File | Change |
|---|---|
| `GmsHookInstaller.kt` | **NEW** — thread-safe lazy init via `ActivityThread` reflection |
| `GmsChildProcessProvider.kt` | **NEW** — ContentProvider for early process init |
| `IActivityManagerProxy.kt` | Add lazy-init guard + `bindServiceInstance()` override |
| `ActivityManagerCommonProxy.kt` | Same two changes |
| `GmsSignInInterceptor.kt` | Use `GmsHookInstaller` instead of direct context field |
| `AndroidManifest.xml` | Register `GmsChildProcessProvider` |

---

## Step-by-Step Integration

### Step 1 — Copy new files

Copy these two new files into your MundoCore source tree:

```
mundocore_fix/hooks/GmsHookInstaller.kt
  → MundoCore/src/main/java/com/mundo/proxy/gms/GmsHookInstaller.kt

mundocore_fix/proxy_gms/GmsChildProcessProvider.kt
  → MundoCore/src/main/java/com/mundo/proxy/gms/GmsChildProcessProvider.kt
```

### Step 2 — Patch `IActivityManagerProxy.kt`

File: `MundoCore/hooks/IActivityManagerProxy.kt`

**2a. Find the existing `bindService()` override and replace its body:**

```kotlin
override fun bindService(
    caller: IApplicationThread, token: IBinder, service: Intent,
    resolvedType: String?, connection: IServiceConnection, flags: Int, userId: Int
): Int {
    if (isGmsIntent(service)) {
        if (GmsHookInstaller.ensureInitialized()) {
            Log.w("SignInIntercept", "bindService GMS intercepted [${GmsHookInstaller.getProcessName()}]")
            GmsSignInInterceptor.onBindService(caller, service, connection, flags)
            return 0
        }
        Log.w("SignInIntercept", "bindService GMS: interceptor not ready, passing through")
    }
    return original.bindService(caller, token, service, resolvedType, connection, flags, userId)
}
```

**2b. ADD `bindServiceInstance()` immediately after `bindService()` (API 31+ fix):**

```kotlin
@TargetApi(31)
override fun bindServiceInstance(
    caller: IApplicationThread, token: IBinder, service: Intent,
    resolvedType: String?, connection: IServiceConnection, flags: Int,
    instanceName: String?, userId: Int
): Int {
    if (isGmsIntent(service)) {
        if (GmsHookInstaller.ensureInitialized()) {
            Log.w("SignInIntercept", "bindServiceInstance GMS intercepted [${GmsHookInstaller.getProcessName()}]")
            GmsSignInInterceptor.onBindService(caller, service, connection, flags)
            return 0
        }
    }
    return original.bindServiceInstance(caller, token, service, resolvedType, connection, flags, instanceName, userId)
}
```

**2c. ADD helper (if not already present):**

```kotlin
private fun isGmsIntent(intent: Intent?): Boolean {
    if (intent == null) return false
    val pkg = intent.component?.packageName ?: intent.`package`
    return pkg == GmsConstants.GMS_PACKAGE
}
```

**2d. Add imports:**
```kotlin
import android.annotation.TargetApi
import com.mundo.proxy.gms.GmsHookInstaller
```

### Step 3 — Patch `ActivityManagerCommonProxy.kt`

File: `MundoCore/hooks/ActivityManagerCommonProxy.kt`

Apply the same changes as Step 2 — same `isGmsIntent()` guard with `GmsHookInstaller.ensureInitialized()`, plus the new `bindServiceInstance()` override.

See `mundocore_fix/hooks/ActivityManagerCommonProxy.patch.kt` for the complete code blocks.

### Step 4 — Patch `GmsSignInInterceptor.kt`

File: `MundoCore/proxy/gms/GmsSignInInterceptor.kt`

**Replace the top of `onBindService()` with the lazy-init guard:**

```kotlin
@JvmStatic
fun onBindService(caller: Any?, service: Intent, connection: Any?, flags: Int) {
    // Lazy-init for child virtual processes
    if (!GmsHookInstaller.ensureInitialized()) {
        Log.w(TAG, "onBindService: GmsHookInstaller not ready — cannot intercept")
        return
    }
    val context = GmsHookInstaller.getHostContext() ?: run {
        Log.w(TAG, "onBindService: hostContext null — cannot intercept")
        return
    }
    // ... rest of the method unchanged, use `context` instead of `hostContext` field
}
```

**Replace `installHooks()` body** (called from `MundoCore.init()`):
```kotlin
@JvmStatic
fun installHooks(context: Context) {
    GmsHookInstaller.install(context)
}
```

### Step 5 — Patch `AndroidManifest.xml`

File: `MundoCore/src/main/AndroidManifest.xml`

Add inside `<application>`:
```xml
<provider
    android:name="com.mundo.proxy.gms.GmsChildProcessProvider"
    android:authorities="${applicationId}.mundogms"
    android:exported="false"
    android:multiprocess="true"
    android:initOrder="100" />
```

### Step 6 — Rebuild and verify

After applying all patches, rebuild MundoCore.aar and re-link to the host APK.

**Expected logcat output on successful login (filter: `MundoGms OR SignInIntercept`):**

```
W/MundoGms: GmsChildProcessProvider.onCreate: process=[com.bgmi:p0]
W/MundoGms: GmsChildProcessProvider: virtual process detected, early GMS init
W/MundoGms: GmsHookInstaller.install: main-process init [com.bgmi:p0]   ← OR lazy-init
W/SignInIntercept: bindService GMS intercepted in [com.bgmi:p0]
W/SignInIntercept: bindService intercepted for com.google.android.gms
W/GmsBroker: getService called, serviceId=91
W/FakeSignIn: signIn() called by package=com.pubg.imobile
W/GoogleWebLogin: JsBridge.accountReady() called
W/GmsAuth: Master token received, email=user@gmail.com
W/GmsAuth: SignIn complete — delivering account to com.pubg.imobile
```

If you see `W/SignInIntercept: bindService GMS intercepted` — the fix works. If you still see nothing, check that the AAR was rebuilt and the new AAR is linked in the host project.

---

## Why `GmsHookInstaller.ensureInitialized()` Works

BlackBox installs `IActivityManagerProxy` hooks in EVERY process (main + child).  
The existing `IActivityManagerProxy.bindService()` override fires in `com.bgmi:p0`.  
Before the fix, it called `GmsSignInInterceptor.onBindService()` which silently returned  
because its `hostContext` was null (never set in the child process).

`ensureInitialized()` calls `ActivityThread.currentActivityThread().getApplication()` via  
reflection — this returns the host Application object, which is available even before  
`Application.onCreate()` completes. With a valid context, interception proceeds normally.

The `GmsChildProcessProvider` is belt-and-suspenders: it sets up the context BEFORE any  
`bindService` call, avoiding even the first lazy-init retry.
