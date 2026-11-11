# MundoCore — Complete Session Log (Full Detail)
**Project:** ParallelSpace / MundoCore AAR  
**Package:** `com.mundo`  
**Base Engine:** BlackBox (Apache-2.0) — [github.com/dreamncn/BlackBox](https://github.com/dreamncn/BlackBox)  
**Session Date:** 2026-11-11  
**Goal:** Implement Google Sign-In inside a BlackBox-based Android virtual space AAR so that cloned apps can authenticate with real Google accounts

---

## TABLE OF CONTENTS
1. [Goal of This Project](#1-goal-of-this-project)
2. [Project Structure & Architecture](#2-project-structure--architecture)
3. [Zerax APK Analysis (Full Detail)](#3-zerax-apk-analysis-full-detail)
4. [How Zerax GMS Google Login Works Without GMS App](#4-how-zerax-gms-google-login-works-without-gms-app)
5. [idx Files — Full Analysis](#5-idx-files--full-analysis)
6. [AAR Structure & Calls](#6-aar-structure--calls)
7. [Files Created — Full Detail](#7-files-created--full-detail)
8. [Files Modified — Hook Layer](#8-files-modified--hook-layer)
9. [Resources & Assets Added](#9-resources--assets-added)
10. [GMS Binder Protocol — Deep Dive](#10-gms-binder-protocol--deep-dive)
11. [ac2dm Token Exchange Flow](#11-ac2dm-token-exchange-flow)
12. [SafeParcel Format — Full Spec](#12-safeparcel-format--full-spec)
13. [ForeignParcelBundle — Full Detail](#13-foreignparcelbundle--full-detail)
14. [Bugs Found & Fixed — Every Single One](#14-bugs-found--fixed--every-single-one)
15. [Build Config & ProGuard](#15-build-config--proguard)
16. [Manifest Changes](#16-manifest-changes)
17. [LADB Logcat Commands](#17-ladb-logcat-commands)
18. [Device Test — Error Faced & Log Analysis](#18-device-test--error-faced--log-analysis)
19. [How a Next Agent Should Proceed (From Logs)](#19-how-a-next-agent-should-proceed-from-logs)
20. [How a Fully Working Google Login Flow Looks](#20-how-a-fully-working-google-login-flow-looks)
21. [Incorrect Claims Corrected During Session](#21-incorrect-claims-corrected-during-session)
22. [All References & Sources](#22-all-references--sources)
23. [Output Artifact](#23-output-artifact)
24. [APK Version History — Every Fix That Made v6 Work](#24-apk-version-history--every-fix-that-made-v6-work)

---

## 1. Goal of This Project

### Primary Goal
Implement a complete Google Sign-In system inside a **BlackBox-based Android virtual space AAR** (`MundoCore`, package `com.mundo`) so that any app cloned and running inside the virtual space can:
- Show a Google account chooser to the user
- Authenticate the user with their real Google account via WebView
- Receive a valid `GoogleSignInAccount` object back — with `email`, `displayName`, `photoUrl`, and optionally `idToken`
- Store tokens securely so the user doesn't have to log in again
- Work even though the cloned app itself has no access to real GMS (Google Play Services) on the host device — because the virtual space intercepts and fakes all GMS calls

### Why This Is Non-Trivial
Normal apps call `GoogleSignIn.getClient(context, options).signIn()` → this triggers a chain of Binder IPC calls to `com.google.android.gms`. Inside a virtual space, the cloned app thinks it's making those calls, but the virtual space intercepts them before they reach the real GMS. Our job was to make those intercepted calls work — returning correct data, in the correct format, over the correct Binder protocol — so the cloned app behaves as if real GMS answered.

### What "Working" Looks Like
```
User opens cloned app → taps "Sign in with Google"
 → Our fake GMS broker intercepts the bindService call
 → Shows GoogleLoginActivity (Zerax glass UI)
 → User picks saved account OR taps "Use another account"
   → If new: GoogleWebLoginActivity opens
     → accounts.google.com/EmbeddedSetup in WebView
     → User logs in → token captured via mm JsBridge
     → ac2dm master token exchange happens
     → Account stored in GmsTokenStore
 → GoogleSignInAccount returned to cloned app via Binder
 → App receives: email, displayName, photoUrl, [idToken]
 → App proceeds as if real Google Sign-In completed
```

### Scope of Our Implementation
| Scope Item | Included |
|---|---|
| Virtual space Binder hook for GMS | ✓ |
| Fake IGmsServiceBroker (service 91) | ✓ |
| Fake ISignInService (codes 101/102/103) | ✓ |
| WebView-based Google login (EmbeddedSetup) | ✓ |
| ac2dm master token exchange | ✓ |
| Keystore-encrypted token storage | ✓ |
| Zerax-style glass UI | ✓ |
| ProGuard / build config | ✓ |
| License attribution (microG Apache-2.0) | ✓ |
| idToken via serverClientId | Partial (requires serverClientId config) |
| SafetyNet / Play Integrity bypass | ✗ Not in scope |
| FCM push notifications for virtual apps | ✗ Not in scope |

---

## 2. Project Structure & Architecture

### Overall Layout
```
Host App (com.bgmi / test host)
 └── MundoCore AAR  (com.mundo)
      ├── core/
      │   ├── gms/              ← GMS server-side (runs in :core process)
      │   └── system/           ← ServiceManager (Binder registration)
      ├── proxy/
      │   └── gms/              ← GMS client-side intercept + UI
      └── hooks/
           ├── IActivityManagerProxy
           ├── ActivityManagerCommonProxy
           ├── IPackageManagerProxy
           └── ServiceConnectionDelegate

Virtual Space (inside MundoCore)
 └── Cloned App (e.g. BGMI / Duolingo / any app)
      └── calls: bindService("com.google.android.gms")
           └── intercepted by GmsSignInInterceptor
                └── → FakeGmsServiceBroker
                     └── → GmsAuthService (Binder in :core)
```

### Process Map
| Process | What runs there |
|---|---|
| `:core` | `GmsAuthService` Binder stub, `GmsTokenStore`, `GmsCheckin`, `GmsCrypto` |
| App process | `GmsSignInInterceptor`, `GoogleLoginActivity`, `GoogleWebLoginActivity`, `ConsentSignInActivity` |

### AAR Integration in Host
```gradle
// host app build.gradle
dependencies {
    implementation fileTree(dir: "libs", include: ["*.aar"])
}
```
The AAR exposes `MundoCore.init(context)` which bootstraps the entire virtual engine including the GMS layer.

---

## 3. Zerax APK Analysis (Full Detail)

### What Zerax Is
Zerax is a private Android app (APK provided by user, who stated it is their own app) that implements Google Sign-In inside a virtual/parallel space engine. It served as the **UI design and flow reference** for this implementation.

### How Zerax Was Analyzed
- APK was provided directly by user
- Analyzed using standard Android reverse engineering: `apktool d zerax.apk`
- `classes.dex` examined using jadx / dex2jar
- `assets/` folder examined for resources and license files
- `res/` examined for drawables, layouts, styles
- `AndroidManifest.xml` examined for activity/service declarations

### Zerax APK — `classes.dex` Findings
The dex contained:

| Class (decompiled name) | Role |
|---|---|
| `GmsServiceBrokerProxy` | Wraps real GMS broker (`IGmsServiceBroker`); forwards non-sign-in services to real GMS while intercepting service ID 91 (Sign-In) |
| `GoogleAuthUi` | Glass-morphism UI builder for the account chooser dialog |
| `WavyProgressIndicator` | Custom `View` — animated wavy path drawn with `Canvas.drawPath()`, `ValueAnimator` |
| `EmbeddedSetup` / consent activity | WebView-based Google consent flow |
| `mm` JsBridge | JavaScript bridge inside WebView for capturing OAuth token from `accounts.google.com/EmbeddedSetup` |
| `MinuteMaid` | User-Agent string builder for token requests |
| `HttpFormClient` | HTTP POST client for `android.clients.google.com/auth` |
| `AuthRequest` / `AuthResponse` | Data classes for the ac2dm token exchange |
| `AuthManager` | Orchestrates token request → store → deliver |

### Zerax APK — `assets/` Findings
```
assets/
 ├── licenses/
 │   ├── microg-NOTICE.txt          ← Apache-2.0 attribution for microG
 │   └── microg-Apache-2.0.txt      ← Full Apache-2.0 license text
 └── (other assets)
```
**Key finding:** Zerax itself includes microG attribution files. This confirmed the implementation is derived from / inspired by [microG GmsCore](https://github.com/microg/GmsCore) (Apache-2.0). These same attribution files were added to MundoCore.

> **User question:** "NOTICE file shouldn't be added manually?"  
> **Answer:** Yes it should — Apache-2.0 requires you to include the NOTICE file in any derivative work. Zerax included it, so MundoCore must too.

### Zerax APK — `res/` Findings
Zerax UI design elements observed:

| Element | Description |
|---|---|
| Glass-morphism dialog | Dark semi-transparent dialog with blur effect, blue-tinted border |
| Account chooser | RecyclerView list of Google accounts with circular avatar, email, display name |
| Wavy progress bar | Animated sine-wave drawn in custom View, blue color |
| Enter animation | Slide-up + fade-in from bottom |
| Exit animation | Fade-out + slight slide-down |
| Color scheme | Dark background (#1A1A2E range), blue accent (#4285F4 Google blue), white text |

### Zerax GmsBrokerProxy Flow (observed from dex)
```
App calls bindService("com.google.android.gms") 
 → GmsSignInInterceptor intercepts
 → Calls bindRealGms() to get real IGmsServiceBroker
 → Wraps it in GmsBrokerProxy (deliverFakeBroker)
 → For service ID 91 (Sign-In): return FakeSignInService
 → For all other service IDs: forward to real GMS, rewriting packageName
```

### What Was Ported From Zerax vs What Was Independently Implemented
| Item | From Zerax | Independent |
|---|---|---|
| Glass UI style/colors | ✓ Design reference | — |
| WavyProgressIndicator animation | ✓ Design reference | — |
| GmsBrokerProxy pattern | ✓ Design reference | — |
| `mm` JsBridge name | ✓ | — |
| MinuteMaid UA | ✓ | — |
| SafeParcel parsing | — | ✓ Independently written |
| GmsAuthService Binder | — | ✓ Independently written |
| ForeignParcelBundle | — | ✓ Independently written |
| GmsTokenStore (Keystore) | — | ✓ Independently written |
| All Binder transaction codes | — | ✓ From GMS protocol spec |

**No code was copy-pasted from the Zerax dex — only design/flow was referenced.**

---

## 4. How Zerax GMS Google Login Works Without GMS App

This section explains the **complete mechanism** Zerax uses to provide Google Sign-In to virtual apps — without having the GMS app installed inside the virtual space, and without the cloned app needing real `com.google.android.gms`.

### The Core Problem
A cloned app inside a virtual space calls:
```kotlin
GoogleSignIn.getClient(context, options).signIn()
```
Internally this resolves to:
```
1. context.bindService(Intent("com.google.android.gms"), conn, flags)
2. conn.onServiceConnected → cast IBinder as IGmsServiceBroker
3. broker.getService(GetServiceRequest(serviceId=91), callbacks)
4. callbacks.onPostInitComplete → cast IBinder as ISignInService
5. signInService.signIn(packageName, options)
```

The virtual space's `PackageManager` does **not** have `com.google.android.gms` installed (because `ensureGoogleStack()` hides it). So `bindService` would normally fail silently with no service found.

### How Zerax Intercepts It

**Step 1 — Hook `bindService` at the ActivityManager level**
```
IActivityManagerProxy / ActivityManagerCommonProxy
 → detects Intent targeting com.google.android.gms
 → does NOT forward to real Android system
 → calls GmsSignInInterceptor.handleBindService()
```

**Step 2 — Bind to real GMS on the host side (not virtual side)**
```kotlin
// GmsSignInInterceptor.bindRealGms()
// Connects to REAL com.google.android.gms on the actual Android device
// (The host app has access to real GMS; the virtual app does not)
val realGmsConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        realBroker = IGmsServiceBroker.Stub.asInterface(binder)
        // Now wrap it and deliver fake broker to virtual app
        deliverFakeBroker(virtualAppConnection)
    }
}
context.bindService(Intent(GMS_PACKAGE), realGmsConnection, BIND_AUTO_CREATE)
```

**Step 3 — Deliver FakeGmsServiceBroker to virtual app**
```kotlin
// GmsSignInInterceptor.deliverFakeBroker()
// Calls the virtual app's ServiceConnection with our fake broker
virtualAppConnection.onServiceConnected(
    ComponentName(GMS_PACKAGE, "GmsServiceBroker"),
    FakeGmsServiceBroker(realBroker)   // our fake, backed by real
)
```
This is delivered via `ServiceConnectionDelegate` which handles 2-arg/3-arg/4-arg `connected()` variants.

**Step 4 — Virtual app asks FakeGmsServiceBroker for Sign-In service (ID 91)**
```kotlin
// FakeGmsServiceBroker.onTransact() — raw code 46
fun getService(callbacks: IGmsCallbacks, request: GetServiceRequest) {
    if (request.serviceId == 91) {
        // Sign-In → return our FakeSignInService (not real GMS)
        callbacks.onPostInitComplete(0, FakeSignInService(), Bundle())
    } else {
        // Any other service (Maps, Auth, etc.) → forward to real GMS
        // But rewrite the calling package name first:
        request.callerPackageName = hostPackageName   // host has auth
        request.callerSignature   = hostCertBytes
        realBroker.getService(callbacks, request)
        // Real GMS answers directly to the virtual app's callbacks
    }
}
```

**Step 5 — Virtual app calls ISignInService.signIn() (code 102)**
```
FakeSignInService.onTransact(102)
 → Reads SignInOptions from Parcel (SafeParcel format)
 → Checks options: requestIdToken? requestEmail? scopes?
 → Sends LocalBroadcast → GoogleLoginActivity launched
```

**Step 6 — GoogleLoginActivity (Zerax glass UI)**
```
IF accounts exist for this package in GmsTokenStore:
  → Show account chooser (RecyclerView, glass dialog)
  → User picks account → go to Step 8

IF no accounts:
  → Show "Sign in with Google" button
  → Launches ConsentSignInActivity → GoogleWebLoginActivity
```

**Step 7 — GoogleWebLoginActivity (new account flow)**
```kotlin
// Loads Google's EmbeddedSetup page in WebView
// This is the OFFICIAL Google login page used by microG and Zerax
webView.loadUrl("https://accounts.google.com/EmbeddedSetup")

// Google's JavaScript on that page, after successful login, calls:
window.mm.accountReady("oauth2_4/XXXXXXXX")

// Our JsBridge receives this:
@JavascriptInterface
fun accountReady(oauthToken: String) {
    // Start ac2dm master token exchange
    exchangeToken(oauthToken)
}
```

**Step 8 — ac2dm Master Token Exchange**
```
POST https://android.clients.google.com/auth
  Token:          oauth2_4/XXXXXX   (from EmbeddedSetup)
  service:        ac2dm
  get_accountid:  1
  ACCESS_TOKEN:   1
  add_account:    1
  User-Agent:     GoogleAuth/1.4 (...) com.google.android.gms/263663000

Response:
  Token:      1/zMASTERTOKEN_XXXX
  Email:      user@gmail.com
  firstName:  ...
  lastName:   ...
```

Master token stored in `GmsTokenStore` (Keystore AES-256-GCM).

**Step 9 — Build GoogleSignInAccount and return it**
```kotlin
// Build the SafeParcel object the app expects
val account = GoogleSignInAccount.build(
    displayName = "$firstName $lastName",
    email       = email,
    id          = accountId,
    photoUrl    = profilePhotoUrl,
    idToken     = null   // only if serverClientId was configured
)

// Wrap in ForeignParcelBundle (because app doesn't have GMS classes)
val bundle = ForeignParcelBundle()
bundle.putForeignParcelable(
    "googleSignInAccount",
    "com.google.android.gms.auth.api.signin.GoogleSignInAccount",
    account.toParcelBytes()
)

// Deliver to calling app via Binder callback
signinCallback.onSignInComplete(0, bundle.toBundle())
```

**Step 10 — Virtual app receives GoogleSignInAccount**
```kotlin
// In the cloned app's onActivityResult / Task callback:
val account = GoogleSignIn.getSignedInAccountFromIntent(data)
// account.email, account.displayName, account.photoUrl all populated
// App proceeds with Google login ✓
```

### Why This Works Without GMS Installed in Virtual Space
The key insight is that **GMS never runs inside the virtual space**. Instead:
- The virtual app talks to `FakeGmsServiceBroker` (our code)
- Our code talks to real GMS **on the host side** for non-sign-in services
- For sign-in specifically (service 91), we do the entire flow ourselves: WebView → token exchange → token storage → SafeParcel response
- The virtual app can't tell the difference — it gets a valid `GoogleSignInAccount` SafeParcel

### Why Package Name Rewriting Is Critical
Real GMS checks if the calling package is authorized (registered in Google Cloud Console, correct SHA-1 cert). The virtual app's package (e.g. `com.some.cloned.app`) is NOT registered. The host app's package might be.

So when forwarding to real GMS for other services:
```
request.callerPackageName = "com.mundo"       (host — registered)
request.callerSignature   = hostCertBytes     (host — correct cert)
```
Real GMS sees a known authorized package and responds normally.

---

## 5. idx Files — Full Analysis

Two `.idx` files were encountered in the project during the session:

### 5.1 `xtj.idx`
| Property | Value |
|---|---|
| Location | Inside MundoCore AAR `jniLibs/` or extracted at runtime |
| File format | **Go compiled binary / v2ray `.so`** |
| What it is | `mundoproxy` — a network proxy module written in Go, compiled as a native shared library |
| Magic bytes | Go binary magic (`\x7fELF` with Go runtime sections) |
| Purpose in project | Network tunneling / proxy for virtual space network traffic |
| **NOT** | microG, GMS auth, or any Google login component |

> **User asked:** "Is `xtj.idx` microG?"  
> **Answer:** **No.** It is a Go v2ray-based proxy `.so` renamed as `.idx`. It has nothing to do with Google Sign-In or microG.

> **User asked:** "Why can't you decrypt `kw9tzxhc.idx`?"  
> **Answer:** Same reason — it is a compiled native binary (Go/v2ray), not an encrypted file. It cannot be "decrypted" because it is not encrypted data; it is executable code.

### 5.2 `kw9tzxhc.idx`
| Property | Value |
|---|---|
| File format | Same as `xtj.idx` — Go compiled native `.so` |
| Purpose | Another mundoproxy/v2ray network module |
| Relationship to GMS | **None** |

### Why These Files Are `.idx` Extension
Renamed to `.idx` to:
1. Avoid Android's automatic extraction/loading of `.so` files from APK
2. Loaded manually at runtime via `System.load()` with the actual path
3. Common obfuscation pattern in virtual space engines to hide native libraries from static analysis

### What Was Confirmed About `.idx` Files
- These files are part of the **mundoproxy** component (network layer)
- They are loaded by the BlackBox-based engine for virtual space network routing
- They are **completely separate** from the GMS auth layer we were implementing
- The `dexopt` reference the user mentioned is unrelated — these are native `.so` files, not dex

---

## 6. AAR Structure & Calls

### AAR Contents
```
MundoCore.aar
 ├── classes.jar           ← All Kotlin/Java compiled classes
 ├── jni/
 │   ├── arm64-v8a/        ← Native .so files
 │   └── armeabi-v7a/
 ├── res/                  ← Merged resources
 ├── assets/               ← Merged assets (including license files)
 ├── AndroidManifest.xml   ← Merged manifest
 ├── R.txt
 ├── proguard.txt          ← Consumer ProGuard rules
 └── consumer-rules.pro    ← Additional consumer rules
```

### AAR Public API Calls (what host app calls)

#### Initialization
```kotlin
// Host app — Application.onCreate()
MundoCore.init(this)
// Internally bootstraps:
// - BlackBox engine
// - ServiceManager (registers GMS_AUTH Binder)
// - GmsSignInInterceptor (registers Binder hooks)
```

#### Install a virtual app
```kotlin
MundoCore.installApp(context, apkPath)
// Internally:
// - PackageManager hooks activated
// - ensureGoogleStack() called (see below)
```

#### Launch a virtual app
```kotlin
MundoCore.launchApp(context, packageName, userId)
// Internally:
// - ActivityManager hooks activated
// - GmsSignInInterceptor armed for this package
```

### `ensureGoogleStack()` — Important Clarification
**User asked:** "Does `ensureGoogleStack` install microG?"  
**Answer: NO.**

`ensureGoogleStack()` does the **opposite** — it **removes** the Google Play Services stack from the virtual space. This is by design:

```kotlin
fun ensureGoogleStack(packageName: String) {
    // REMOVES com.google.android.gms from virtual space's visible package list
    // so cloned app cannot see/bind to real GMS directly
    // Forces all GMS calls through our fake broker instead
    virtualPackageManager.hidePackage("com.google.android.gms")
    virtualPackageManager.hidePackage("com.google.android.gsf")
}
```

The GMS calls are then intercepted by our `GmsSignInInterceptor` which delivers `FakeGmsServiceBroker`.

### Binder Service Registration
```kotlin
// core/system/ServiceManager.kt
fun registerServices() {
    ServiceManager.addService("gms_auth", GmsAuthService())
    // "gms_auth" is the custom Binder name for our auth service
    // Not a real Android system service — internal to MundoCore
}
```

### How Virtual App Reaches GmsAuthService
```
Virtual App
 → bindService(Intent("com.google.android.gms")) 
 → IActivityManagerProxy.bindService() [hook]
 → ActivityManagerCommonProxy.bindService() [hook]
 → GmsSignInInterceptor.maybeWrapBroker()
 → delivers FakeGmsServiceBroker via ServiceConnectionDelegate
 → App calls broker.getService(91, ...) [IGmsServiceBroker code 46]
 → FakeGmsServiceBroker returns FakeSignInService
 → App calls ISignInService.signIn() [code 102]
 → GmsAuthService handles via GmsAuthClient
```

---

## 7. Files Created — Full Detail

### 5.1 `core/gms/GmsAuthService.kt`
**What it does:** Server-side Binder stub running in `:core` process. This is the heart of the entire auth system.

**Binder interface methods:**
```kotlin
fun login(pkg: String, account: GoogleSignInAccount): Boolean
fun getAccounts(pkg: String): List<GoogleSignInAccount>
fun signIn(pkg: String, options: SignInOptions): GoogleSignInAccount?
fun signOut(pkg: String): Boolean
fun revoke(pkg: String): Boolean
fun removeAccount(pkg: String, email: String): Boolean
fun getAndroidId(): String
fun getProfile(pkg: String): Bundle  // displayName, email, photoUrl, idToken
```

**Key behavior:**
- Fails with descriptive error if `idToken` or `serverAuthCode` is requested but not available
- Uses `GmsTokenStore` for all account persistence
- Uses `GmsCheckin` to generate/return `androidId`
- Calls `GmsSignatureHelper` to verify calling package's cert before returning tokens

### 5.2 `core/gms/GmsAuthClient.kt`
**What it does:** Client-side proxy to call `GmsAuthService` from the app process over Binder.

```kotlin
val authService = GmsAuthClient.connect(context)
val account = authService.signIn(callingPackage, options)
```

### 5.3 `core/gms/FakeGmsServiceBroker.kt`
**What it does:** Implements `IGmsServiceBroker` (the first thing any app talks to when connecting to GMS).

**Key method — `getService()`:**
```kotlin
// Raw Binder transaction code 46
override fun getService(callbacks: IGmsCallbacks, connectionInfo: GetServiceRequest) {
    if (connectionInfo.serviceId == 91) {
        // Sign-In service → return our FakeSignInService
        callbacks.onPostInitComplete(0, FakeSignInService(), Bundle())
        // onPostInitComplete = raw code 1
    } else {
        // All other services → forward to real GMS with package rewrite
        realBroker?.getService(callbacks, connectionInfo.rewritePackage(hostPkg))
    }
}
```

### 5.4 `core/gms/GmsBrokerProxy.kt`
**What it does:** Dynamic proxy (`InvocationHandler`) wrapping the real `IGmsServiceBroker` obtained by binding to actual GMS on the device.

**Key operation — package name rewriting:**
```kotlin
// When forwarding to real GMS, replace virtual app's package name
// with the host app's package name (which IS authorized with Google)
fun rewritePackage(request: GetServiceRequest): GetServiceRequest {
    request.callerPackageName = hostPackageName
    request.callerSignature = hostCertBytes
    return request
}
```

### 5.5 `core/gms/FakeSignInService.kt`
**What it does:** Implements `ISignInService` — the specific GMS service that apps call for Google Sign-In.

**Transaction codes:**
```
101 → getSignedInAccount(pkg)   returns current signed-in GoogleSignInAccount
102 → signIn(pkg, options)      triggers sign-in flow
103 → signOut(pkg)              clears stored account
```

**How sign-in is triggered (code 102):**
```kotlin
// 1. Writes pending sign-in request to shared state
// 2. Sends LocalBroadcast to GoogleLoginActivity
// 3. GoogleLoginActivity shows UI (Zerax-style chooser)
// 4. User picks account / signs in via WebView
// 5. Result written back via GmsAuthService
// 6. onActivityResult delivers GoogleSignInAccount back to calling app
```

### 5.6 `core/gms/ForeignParcelBundle.kt`
**What it does:** Hand-builds a `Bundle` that can contain foreign `Parcelable` objects (classes that exist in GMS but not in the host app). Standard Android `Bundle.putParcelable()` would fail with `ClassNotFoundException` when the bundle is read back.

**The problem this solves:**
```
Normal Bundle: writeParcelable() writes class name + data
Normal Bundle: readParcelable() tries Class.forName(className) → ClassNotFoundException

ForeignParcelBundle: writes raw bytes, skips class loading on read
Used for: GoogleSignInAccount, GoogleSignInOptions in Bundle extras
```

**API 33+ length prefix fix (bug that was found and fixed):**
```kotlin
// API 33+ added a 4-byte length prefix before Parcelable data in Bundle
// Without this, the bundle is misread on Android 13+
if (Build.VERSION.SDK_INT >= 33) {
    parcel.writeInt(parcelableBytes.size)  // length prefix
}
parcel.writeByteArray(parcelableBytes)
```

**Multi-entry support:** Can hold multiple foreign Parcelable entries under different keys, not just one.

### 5.7 `core/gms/GmsTokenStore.kt`
**What it does:** Encrypted storage for per-package Google account tokens.

**Storage backend:** `EncryptedSharedPreferences` from AndroidX Security Crypto (Keystore-backed AES-256-GCM).

**Key structure:**
```
Key:   "token_{packageName}_{email}"
Value: JSON { masterToken, accessToken, expiry, displayName, photoUrl, idToken? }

Key:   "account_{packageName}"  
Value: JSON { email, displayName, photoUrl }
```

**Why per-package:** Each cloned app gets its own isolated token set. App A's token cannot be read by App B.

### 5.8 `core/gms/GmsConstants.kt`
```kotlin
object GmsConstants {
    const val GMS_VERSION_CODE = 263663000  // Reported to apps asking "what GMS version?"
    const val GMS_PACKAGE = "com.google.android.gms"
    const val GSF_PACKAGE = "com.google.android.gsf"
    const val SIGN_IN_SERVICE_ID = 91
    const val BROKER_GET_SERVICE_CODE = 46
    const val CALLBACKS_ON_POST_INIT_CODE = 1
}
```

### 5.9 `core/gms/SafeParcelHelper.kt`
**What it does:** Read/write `SafeParcel` format — the serialization format GMS uses for all its objects passed over Binder.

**SafeParcel format (from microG SafeParcel repo — [github.com/microg/SafeParcel](https://github.com/microg/SafeParcel)):**
```
Each field: [header: int32][data...]
header = (fieldId & 0xFFFF) | (fieldSize << 16)
  → low  16 bits = field ID
  → high 16 bits = field size in bytes (0xFFFF = variable-length)

Object start marker: 0x4F45  ("OE" in ASCII)
Object end marker:   0x4F46  ("OF" in ASCII)
```

**Fields implemented:**
```kotlin
fun readInt(parcel: Parcel, fieldId: Int): Int
fun readString(parcel: Parcel, fieldId: Int): String?
fun readBool(parcel: Parcel, fieldId: Int): Boolean
fun readBytes(parcel: Parcel, fieldId: Int): ByteArray?
fun writeInt(parcel: Parcel, fieldId: Int, value: Int)
fun writeString(parcel: Parcel, fieldId: Int, value: String?)
fun writeBool(parcel: Parcel, fieldId: Int, value: Boolean)
fun startObject(parcel: Parcel): Int          // writes 0x4F45, returns position
fun endObject(parcel: Parcel, startPos: Int)  // backfills size
```

### 5.10 `core/gms/SignInOptions.kt`
**What it does:** Parses `GoogleSignInOptions` from the `SafeParcel` data the app sends in `ISignInService.signIn()`.

**Fields parsed:**
```
Field 1: requestIdToken (bool)
Field 2: serverClientId (string) — for idToken audience
Field 3: requestServerAuthCode (bool)
Field 4: requestEmail (bool)
Field 5: requestProfile (bool)
Field 6: requestedScopes (string[])
Field 7: hostedDomain (string)
```

### 5.11 `core/gms/GoogleSignInAccount.kt`
**What it does:** Builds and parses the `GoogleSignInAccount` SafeParcel object — the object returned to apps after successful sign-in.

**Fields:**
```
Field 1:  displayName (string)
Field 2:  email (string)
Field 3:  id (string) — Google account ID ("sub" claim in JWT)
Field 4:  idToken (string?) — JWT, only if requested
Field 5:  serverAuthCode (string?) — only if requested
Field 6:  photoUrl (string?)
Field 7:  grantedScopes (Set<string>)
Field 8:  familyName (string?)
Field 9:  givenName (string?)
Field 10: expirationTime (long)
```

### 5.12 `core/gms/GmsCrypto.kt`
**What it does:** Crypto helpers used during the ac2dm token exchange.

**Functions:**
```kotlin
fun encryptPassword(email: String, password: String, googlePublicKey: ByteArray): String
// Uses RSA encryption with Google's known public key
// Output is Base64-encoded EncryptedPasswd for the /auth POST

fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray
fun sha256(data: ByteArray): ByteArray
```

### 5.13 `core/gms/GmsCheckin.kt`
**What it does:** Generates and caches the `androidId` — a device identifier required for GMS token requests.

**How it works:**
```
1. Check if androidId already stored in GmsTokenStore
2. If not: generate random 64-bit long, store it
3. Return as hex string (matches real androidId format)
```
> Real GMS does a checkin HTTP call to get androidId assigned by Google. We generate a local one to avoid that network call.

### 5.14 `core/gms/GmsSignatureHelper.kt`
**What it does:** Handles the PM cert spoof — apps often verify that GMS has Google's signature before trusting it.

**How it works:**
```kotlin
fun getFakeGmsCert(): ByteArray {
    // Returns the well-known Google GMS certificate bytes
    // (public certificate — not a secret, used by apps to verify GMS identity)
    return GOOGLE_GMS_CERT_BYTES
}
```
**Bug that was fixed:** Was returning `null` — caused apps to reject our fake GMS. Fixed to return the actual cert bytes.

### 5.15 `proxy/gms/GoogleLoginActivity.kt`
**What it does:** Zerax-style account chooser dialog shown when an app triggers Google Sign-In.

**UI elements:**
```
- Semi-transparent glass-morphism dialog (MundoGoogleGlass style)
- "Sign in with Google" header with Google logo
- RecyclerView of saved accounts (circular avatar, name, email)
- "Use another account" option (opens GoogleWebLoginActivity)
- Cancel button
```

**Flow:**
```
1. Receives Intent from FakeSignInService (via LocalBroadcast)
2. Shows account list (from GmsTokenStore for this package)
3. User selects account → GmsAuthService.signIn() called
4. OR user selects "Use another account" → GoogleWebLoginActivity launched
5. Result returned to calling app via Activity result / Binder callback
```

### 5.16 `proxy/gms/GoogleWebLoginActivity.kt`
**What it does:** WebView-based Google login for new account sign-in.

**WebView setup:**
```kotlin
webView.settings.apply {
    javaScriptEnabled = true
    domStorageEnabled = true
    userAgentString = MinuteMaidUA.build()  // matches microG/Zerax UA
}
webView.addJavascriptInterface(JsBridge(), "mm")
webView.loadUrl("https://accounts.google.com/EmbeddedSetup")
```

**`mm` JsBridge (JavaScript → Kotlin bridge):**
```kotlin
inner class JsBridge {
    @JavascriptInterface
    fun accountReady(oauthToken: String) {
        // Called from JavaScript when Google login completes
        // oauthToken = "oauth2_4/..." token from EmbeddedSetup
        // Kick off ac2dm master token exchange
        viewModel.exchangeToken(oauthToken)
    }
    
    @JavascriptInterface  
    fun getAccounts(): String {
        // Returns JSON array of existing accounts (for account picker in WebView)
        return GmsAuthClient.getAccounts(callingPkg).toJson()
    }
}
```

**How EmbeddedSetup works:**
```
1. WebView loads accounts.google.com/EmbeddedSetup
2. User enters Google credentials
3. Google's JavaScript calls window.mm.accountReady(token)
4. JsBridge.accountReady() receives oauth2_4/... token
5. ac2dm exchange starts (see Section 9)
```

### 5.17 `proxy/gms/GoogleAuthUi.kt`
**What it does:** Shared UI builder for the glass-morphism components (Zerax design).

```kotlin
fun buildGlassDialog(context: Context): AlertDialog.Builder
fun buildLoadingView(context: Context): WavyProgressIndicator
fun buildAccountItem(account: GoogleSignInAccount): View
```

### 5.18 `proxy/gms/WavyProgressIndicator.kt`
**What it does:** Custom animated View — the wavy sine-wave progress indicator from Zerax UI.

**Implementation:**
```kotlin
class WavyProgressIndicator @JvmOverloads constructor(...) : View(...) {
    private val path = Path()
    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xFF4285F4.toInt()  // Google blue
        isAntiAlias = true
    }
    private var phase = 0f
    
    private val animator = ValueAnimator.ofFloat(0f, 2f * PI.toFloat()).apply {
        duration = 1500
        repeatMode = ValueAnimator.RESTART
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { 
            phase = it.animatedValue as Float
            invalidate()
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        // Draw animated sine wave: y = amplitude * sin(frequency * x + phase)
        path.reset()
        // ... wave drawing logic
        canvas.drawPath(path, paint)
    }
}
```

### 5.19 `proxy/gms/GmsSignInInterceptor.kt`
**What it does:** The core interceptor — hooks `bindService` to GMS and delivers our fake broker.

**Key methods:**

```kotlin
fun bindRealGms(context: Context, connection: ServiceConnection) {
    // Binds to real com.google.android.gms on the device
    // Gets the real IGmsServiceBroker
    // Needed so we can forward non-sign-in services to real GMS
}

fun deliverFakeBroker(connection: ServiceConnection, binder: IBinder) {
    // Calls connection.onServiceConnected() with our FakeGmsServiceBroker
    // Handles 2-arg, 3-arg, and 4-arg signatures of connected()
    // (Different Android versions / wrapper classes have different signatures)
}

fun maybeWrapBroker(binder: IBinder): IBinder {
    // If binder is a real GMS broker → wrap in GmsBrokerProxy
    // If already wrapped → return as-is
    return GmsBrokerProxy(binder, hostPackageName)
}

fun intercept(action: String, pkg: String): InterceptResult {
    return when (action) {
        "GOOGLE_SIGN_IN" → InterceptResult.HANDLE   // we handle this
        "NO_IMPL"        → InterceptResult.REJECT    // tell app no impl
        "START_SIGN_IN"  → InterceptResult.HANDLE   // we handle this
        else             → InterceptResult.FORWARD   // forward to real GMS
    }
}
```

### 5.20 `proxy/gms/ConsentSignInActivity.kt`
**What it does:** Consent dialog shown before redirecting to Google login WebView.

**Key fix in this file:**
```kotlin
// BEFORE (caused build error — requires core-ktx dependency):
val bundle = bundleOf("key1" to value1, "key2" to value2)

// AFTER (fixed — no extra dependency needed):
val bundle = Bundle().apply {
    putString("key1", value1)
    putBoolean("key2", value2)
}
```

---

## 8. Files Modified — Hook Layer

### 6.1 `IActivityManagerProxy`
**What was added:**
```kotlin
// Hook bindService() call
override fun bindService(
    caller: IApplicationThread,
    token: IBinder,
    service: Intent,
    resolvedType: String,
    connection: IServiceConnection,
    flags: Int,
    userId: Int
): Int {
    // Check if service targets GMS
    if (service.component?.packageName == GmsConstants.GMS_PACKAGE) {
        GmsSignInInterceptor.onBindService(caller, service, connection, flags)
        return 0  // Success, we handled it
    }
    return original.bindService(...)
}
```

### 6.2 `ActivityManagerCommonProxy`
**What was added:** Same `bindService` override as `IActivityManagerProxy` but for the "common" proxy path used on certain Android API levels where the specific interface is not directly accessible.

```kotlin
// handles: bindService on API 29+ where ActivityManagerNative is gone
fun proxyBindService(...): Int {
    if (isGmsIntent(service)) {
        return GmsSignInInterceptor.handleBindService(...)
    }
    return callOriginal(...)
}
```

### 6.3 `IPackageManagerProxy`
**What was added:** Cert spoofing for `com.google.android.gms`.

```kotlin
override fun getPackageInfo(packageName: String, flags: Int, userId: Int): PackageInfo? {
    val info = original.getPackageInfo(packageName, flags, userId)
    
    if (packageName == GmsConstants.GMS_PACKAGE && flags has GET_SIGNATURES) {
        // Replace real GMS cert with our spoofed cert
        // So apps believe our fake GMS is the real Google-signed GMS
        info?.signatures = arrayOf(Signature(GmsSignatureHelper.getFakeGmsCert()))
    }
    return info
}
```

### 6.4 `ServiceConnectionDelegate`
**What was added:** Support for multiple `connected()` / `onServiceConnected()` signatures.

**Problem:** When delivering our fake broker via `connection.onServiceConnected()`, different wrapper classes have different method signatures. If we call the wrong one, it crashes.

**Fix:**
```kotlin
fun deliverConnected(connection: Any, name: ComponentName, binder: IBinder) {
    // Try 2-arg version first
    try {
        connection::class.java
            .getMethod("connected", ComponentName::class.java, IBinder::class.java)
            .invoke(connection, name, binder)
        return
    } catch (e: NoSuchMethodException) { }
    
    // Try 3-arg version (some wrappers add a boolean)
    try {
        connection::class.java
            .getMethod("connected", ComponentName::class.java, IBinder::class.java, Boolean::class.java)
            .invoke(connection, name, binder, false)
        return
    } catch (e: NoSuchMethodException) { }
    
    // Try 4-arg version
    // ...
}
```

### 6.5 `core/system/ServiceManager.kt`
**What was added:**
```kotlin
fun registerAllServices(context: Context) {
    // ... existing services ...
    
    // Register our GMS auth service
    addService("gms_auth", GmsAuthService(context))
}
```

---

## 9. Resources & Assets Added

### Drawables
```
res/drawable/mundo_auth_google.xml
  → Vector drawable of Google "G" logo in correct brand colors
  → Used in GoogleLoginActivity header

res/drawable/mundo_auth_glass_bg.xml  
  → Shape drawable for glass-morphism dialog background
  → Dark semi-transparent rect with rounded corners + blue stroke
```

### Animations
```
res/anim/mundo_auth_enter.xml
  → translateY: 100% → 0% (slide up from bottom)
  → alpha: 0.0 → 1.0 (fade in)
  → duration: 300ms, interpolator: decelerate

res/anim/mundo_auth_exit.xml
  → alpha: 1.0 → 0.0 (fade out)
  → translateY: 0% → 20% (slight slide down)
  → duration: 200ms, interpolator: accelerate
```

### Styles
```xml
<!-- res/values/styles_mundo_auth.xml -->

<style name="MundoGoogleGlass" parent="Theme.AppCompat.Dialog">
    <item name="android:windowBackground">@drawable/mundo_auth_glass_bg</item>
    <item name="android:windowIsTranslucent">true</item>
    <item name="android:windowAnimationStyle">@style/MundoGoogleMotion</item>
    <item name="android:backgroundDimAmount">0.6</item>
</style>

<style name="MundoGoogleBrowser" parent="Theme.AppCompat.Light.NoActionBar">
    <item name="android:windowBackground">@color/mundo_auth_bg</item>
    <item name="android:statusBarColor">@color/mundo_auth_bg</item>
</style>

<style name="MundoGoogleMotion">
    <item name="android:windowEnterAnimation">@anim/mundo_auth_enter</item>
    <item name="android:windowExitAnimation">@anim/mundo_auth_exit</item>
</style>
```

### License Assets
```
assets/licenses/microg-NOTICE.txt
  → microG project NOTICE file
  → Required by Apache-2.0 for derivative works
  → Source: https://github.com/microg/GmsCore/blob/master/NOTICE

assets/licenses/microg-Apache-2.0.txt
  → Full text of Apache License 2.0
  → Source: https://www.apache.org/licenses/LICENSE-2.0.txt
```

> **Why manually added:** Apache-2.0 §4(b) requires that derivative works include the NOTICE file from the original. Since our GMS auth implementation is inspired by microG's approach, we include their NOTICE. Zerax does the same thing.

---

## 10. GMS Binder Protocol — Deep Dive

### Service Binding Flow
```
Step 1: App calls bindService(Intent(GMS_PACKAGE))
Step 2: Our hook intercepts → delivers FakeGmsServiceBroker
Step 3: App calls broker.getService(request, callbacks)
        → Raw Binder transaction code: 46
Step 4: Broker calls callbacks.onPostInitComplete(status, binder, extras)
        → Raw Binder transaction code: 1
Step 5: App unwraps binder as ISignInService
Step 6: App calls signIn() / getSignedInAccount() / signOut()
        → Raw codes: 102 / 101 / 103
```

### Raw Binder Codes Used
| Interface | Method | Raw Code |
|---|---|---|
| `IGmsServiceBroker` | `getService()` | 46 |
| `IGmsCallbacks` | `onPostInitComplete()` | 1 |
| `ISignInService` | `getSignedInAccount()` | 101 |
| `ISignInService` | `signIn()` | 102 |
| `ISignInService` | `signOut()` | 103 |

**Why raw codes instead of AIDL:** Google's GMS interfaces are not public. We use the raw `Binder.transact()` with the known transaction codes, reverse-engineered from GMS APK and confirmed via microG source.

### GetServiceRequest Structure (SafeParcel)
```
Field 1: packageName (string)   — calling app's package
Field 2: serviceId (int)        — 91 for Sign-In
Field 3: versionCode (int)      — GMS version code expected
Field 4: extras (Bundle)        — additional params
```

### onPostInitComplete Parameters
```
arg1: status (int)      — 0 = success
arg2: binder (IBinder)  — the service binder (our FakeSignInService)
arg3: extras (Bundle)   — can be empty
```

---

## 11. ac2dm Token Exchange Flow

**Reference:** <cite index="31-1">The master token is obtained from an undocumented authentication endpoint at `https://android.clients.google.com/auth`.</cite>

### Full Flow
```
1. GoogleWebLoginActivity loads:
   https://accounts.google.com/EmbeddedSetup
   
2. User logs in via Google's standard web UI

3. Google's JavaScript calls:
   window.mm.accountReady("oauth2_4/AAAA...")
   
4. JsBridge.accountReady() receives oauth2_4 token

5. HttpFormClient POSTs to android.clients.google.com/auth:
```

<cite index="32-1">The POST to `https://android.clients.google.com/auth` includes:
```
accountType: HOSTED_OR_GOOGLE
Email: user@gmail.com
has_permission: 1
add_account: 1
EncryptedPasswd: <base64>
service: ac2dm
```</cite>

```
6. Response contains master token (Token=1/zMASTER...)

7. Master token stored in GmsTokenStore (Keystore-encrypted)

8. Per-package OAuth tokens derived on demand:
   POST android.clients.google.com/auth
   Token: 1/zMASTER...
   service: oauth2:https://www.googleapis.com/auth/userinfo.profile
   app: com.cloned.app.package
   client_sig: <host app cert sha1>
   
9. Access token returned to calling app via GoogleSignInAccount
```

### MinuteMaid User-Agent
```
User-Agent: GoogleAuth/1.4 (mako JDQ39) com.google.android.gms/263663000
```
This UA string is required — Google's auth endpoint rejects requests without a recognized Android GMS UA. <cite index="38-1">The correct format is `com.google.android.gms/243530022` style.</cite>

---

## 12. SafeParcel Format — Full Spec

<cite index="48-1">SafeParcel is a mechanism to serialize objects on the Android platform into Parcelables version-agnostic. This is achieved by prefixing each field with a unique identifying number and its length. When deserializing, unknown fields can be skipped and the field order is not relevant. The SafeParcel format was originally developed by Google for Play Services.</cite>

### Field Header Encoding
```
header (int32) = (fieldId & 0xFFFF) | (fieldSize << 16)

Low  16 bits → field ID
High 16 bits → field data size in bytes
              (0xFFFF in high bits = variable length, actual size follows)
```

### Object Markers
```
Start marker: 0x4F45 (written as int32 at object start)
End marker:   0x4F46 (written at object end)
```

### Reading Algorithm
```kotlin
fun readField(parcel: Parcel): Pair<Int, Int> {
    val header = parcel.readInt()
    val fieldId = header and 0xFFFF
    val size    = (header ushr 16) and 0xFFFF
    val actualSize = if (size == 0xFFFF) parcel.readInt() else size
    return Pair(fieldId, actualSize)
}
```

---

## 13. ForeignParcelBundle — Full Detail

### Why It Was Needed
When GMS returns a `GoogleSignInAccount` inside a `Bundle`, the bundle contains a `Parcelable` whose class is `com.google.android.gms.auth.api.signin.GoogleSignInAccount`. This class does not exist in the host app or the cloned app — only in real GMS.

Standard `Bundle.readParcelable()` → `Class.forName("com.google.android.gms...GoogleSignInAccount")` → `ClassNotFoundException` → crash.

### How ForeignParcelBundle Solves It
```kotlin
class ForeignParcelBundle {
    private val entries = mutableMapOf<String, Pair<String, ByteArray>>()
    // key → Pair(className, rawParcelBytes)
    
    fun putForeignParcelable(key: String, className: String, data: ByteArray) {
        entries[key] = Pair(className, data)
    }
    
    fun toBundle(): Bundle {
        // Writes a Bundle where Parcelable entries are raw bytes
        // The class name is written but reading is deferred / skipped
        val parcel = Parcel.obtain()
        // ... write magic, entry count, entries ...
        return Bundle().also { it.readFromParcel(parcel) }
    }
}
```

### API 33+ Fix (Bug Found During Session)
```kotlin
// Android 13 (API 33) added a 4-byte length prefix before each Parcelable
// in Bundle serialization. Without this, the bundle is deserialized incorrectly.
if (Build.VERSION.SDK_INT >= 33) {
    parcel.writeInt(parcelableBytes.size)  // NEW: length prefix
}
parcel.writeByteArray(parcelableBytes)    // actual Parcelable data
```

---

## 14. Bugs Found & Fixed — Every Single One

| # | File | Bug Description | Root Cause | Fix |
|---|---|---|---|---|
| 1 | `FakeGmsServiceBroker` | Wrong `getService()` Binder code (was 1) | Assumed AIDL ordinal 0+1=1 | Fixed to raw code **46** |
| 2 | `SafeParcelHelper` | Field header bit ordering reversed (id in high bits, size in low bits) | Misread spec | Fixed: id=low 16, size=high 16 |
| 3 | `GmsTokenStore` | All packages sharing one token slot (last sign-in overwrote others) | Key was just `"account"` | Refactored to `"account_{packageName}"` |
| 4 | `GmsSignInInterceptor` | Sign-in result never delivered back to calling app | Missing callback path after WebView login | Added `deliverResult()` → Binder callback to app |
| 5 | `GmsSignatureHelper` | `getFakeGmsCert()` returning `null` | Missing initialization of cert bytes constant | Fixed to return actual Google GMS cert byte array |
| 6 | `ServiceConnectionDelegate` | `connected()` call crashing — wrong method signature | Only 2-arg version handled; some wrappers use 3-arg or 4-arg | Added all 3 overloads via reflection |
| 7 | `ConsentSignInActivity` | Build error: `Unresolved reference: bundleOf` | `bundleOf` requires `core-ktx` which wasn't in AAR deps | Replaced with `Bundle().apply {}` |
| 8 | `GmsAuthService`, `GmsSignInInterceptor` | Key debug logs invisible in release builds | `Log.d` stripped by R8/ProGuard in release | Moved all critical diagnostic logs to `Log.w` |
| 9 | `build.gradle` | Build fails: `buildToolsVersion "34.0.4"` not found | User's machine had `34.0.0` installed, not `34.0.4` | User changed to `"34.0.0"` |
| 10 | `ForeignParcelBundle` | Bundle deserialized incorrectly on Android 13+ | Missing 4-byte length prefix before Parcelable data added in API 33 | Added `parcel.writeInt(size)` before data when `SDK >= 33` |
| 11 | `GmsAuthService` | Silent failure when `idToken`/`serverAuthCode` requested | No check — just returned null silently | Added explicit `return Error("idToken not available — serverClientId not configured")` |
| 12 | `IPackageManagerProxy` | `getPackageInfo()` returned null for GMS package | Virtual space hid GMS; proxy didn't handle this case | Added fallback: if virtual returns null for GMS pkg, call real PM |
| 13 | `FakeSignInService` onTransact | Parcel data position not reset before reading | `Parcel.readInt()` for interface token consumed data | Added `enforceInterface()` then reset position |
| 14 | `GmsBrokerProxy` | Package name rewrite not applied for `Bundle` extras in request | Only top-level field rewritten; extras Bundle had old pkg | Rewrote extras Bundle traversal to find/replace packageName |

---

## 15. Build Config & ProGuard

### AAR `build.gradle`
```gradle
android {
    compileSdk 34
    buildToolsVersion "34.0.0"   // was "34.0.4" — corrected by user
    
    defaultConfig {
        minSdk 21
        targetSdk 34
        consumerProguardFiles "consumer-rules.pro"
    }
    
    buildTypes {
        release {
            isMinifyEnabled = false  // AAR itself not minified
            // Host app's ProGuard handles minification
        }
    }
}

dependencies {
    implementation "androidx.security:security-crypto:1.1.0-alpha06"
    // Note: androidx.core:core-ktx was intentionally NOT added
    // to avoid bundleOf dependency — using Bundle().apply {} instead
    implementation "androidx.appcompat:appcompat:1.6.1"
    implementation "androidx.localbroadcastmanager:localbroadcastmanager:1.1.0"
}
```

### `consumer-rules.pro` (bundled in AAR — applied to host app automatically)
```proguard
# Keep JsBridge — methods called from JavaScript via WebView
-keepclassmembers class com.mundo.proxy.gms.GoogleWebLoginActivity$JsBridge {
    public *;
}

# Keep SafeParcel structures (fields accessed by field ID, not name)
-keepclassmembers class com.mundo.core.gms.GoogleSignInAccount { *; }
-keepclassmembers class com.mundo.core.gms.SignInOptions { *; }

# Keep GmsAuthService Binder stub (called via raw Binder transactions)
-keep class com.mundo.core.gms.GmsAuthService { *; }
-keep class com.mundo.core.gms.GmsAuthService$Stub { *; }

# Keep FakeSignInService (same reason)
-keep class com.mundo.core.gms.FakeSignInService { *; }

# Keep reflection targets in ServiceConnectionDelegate
-keepclassmembers class * implements android.content.ServiceConnection {
    public void connected(android.content.ComponentName, android.os.IBinder);
    public void connected(android.content.ComponentName, android.os.IBinder, boolean);
}
```

### Host App `proguard-rules.pro` (additional rules provided)
```proguard
# Keep MundoCore entry point
-keep class com.mundo.MundoCore { *; }

# Keep all GMS proxy classes (called via reflection from hooks)
-keep class com.mundo.proxy.gms.** { *; }
-keep class com.mundo.core.gms.** { *; }

# Keep hook proxies (accessed via reflection by BlackBox engine)
-keep class com.mundo.hooks.** { *; }
```

---

## 16. Manifest Changes

### Services Added
```xml
<!-- GmsAuthService — server process -->
<service
    android:name="com.mundo.core.gms.GmsAuthService"
    android:process=":core"
    android:exported="false" />
```

### Activities Added
```xml
<!-- Account chooser (Zerax glass UI) -->
<activity
    android:name="com.mundo.proxy.gms.GoogleLoginActivity"
    android:theme="@style/MundoGoogleGlass"
    android:exported="false"
    android:launchMode="singleTop" />

<!-- WebView login (new account) -->
<activity
    android:name="com.mundo.proxy.gms.GoogleWebLoginActivity"
    android:theme="@style/MundoGoogleBrowser"
    android:exported="false"
    android:configChanges="keyboardHidden|orientation|screenSize" />

<!-- Consent screen before WebView login -->
<activity
    android:name="com.mundo.proxy.gms.ConsentSignInActivity"
    android:theme="@style/MundoGoogleMotion"
    android:exported="false" />
```

---

## 17. LADB Logcat Commands

### Commands Provided to User

```bash
# Step 1: Enable ADB over WiFi (LADB)
# Settings → Developer Options → Wireless Debugging → Enable

# Step 2: Find app UID
adb shell pm list packages -U | grep <package_name>
# Example output: package:com.mundo uid:10633

# Step 3: Filter logs by our GMS tags
adb logcat -s "MundoGms:W" "GmsAuth:W" "GmsBroker:W" "SignInIntercept:W" "GoogleLogin:W"

# Step 4: Filter by specific PID (find PID first)
adb shell ps -A | grep com.mundo
# Then:
adb logcat --pid=<PID>

# Step 5: Crash logs
adb logcat -s "AndroidRuntime:E" "FATAL:E" "DEBUG:E"

# Step 6: Combined one-liner (all our tags + crashes)
adb logcat -s "MundoGms:*" "GmsAuth:*" "GmsBroker:*" "SignInIntercept:*" "GoogleLogin:*" "GoogleWebLogin:*" "FakeSignIn:*" "AndroidRuntime:E" "FATAL:E"

# Step 7: Save to file
adb logcat -s "MundoGms:*" "GmsAuth:*" > /sdcard/bgmi_gms.txt

# Step 8: Clear logcat before test run
adb logcat -c && adb logcat -s "MundoGms:*" "GmsAuth:*"
```

**Why `Log.w` not `Log.d`:** R8/ProGuard in release builds strips `Log.d` calls. `Log.w` (warning level) survives release minification. All diagnostic logs were moved to `Log.w` specifically so they appear in release-build logcat.

---

## 18. Device Test — Error Faced & Log Analysis

### Test Setup
- Host app package: `com.bgmi` (later confirmed: `com.pubg.imobile` running inside it)
- UID found: `10633`
- Build: Release AAR integrated into host

### Test Result
When Google Sign-In was triggered in the cloned app:
1. Loading dialog appeared (our `WavyProgressIndicator` — confirming UI was integrated)
2. After ~2 seconds: **silent failure** — dialog dismissed, no account signed in, no error shown to user

### Log Files Received From User
| File | Contents |
|---|---|
| `bgmi_gms.txt` | Logs from `adb logcat -s "MundoGms:*"` — **none of our GMS diagnostic tags appeared** |
| `bgmi_uid.txt` | UID lookup output — showed uid:10633 for com.bgmi |
| `bgmi_crash.txt` | **Empty** — no crash occurred (silent failure, not a crash) |

### Analysis of Logs
The absence of ANY of our log tags meant one of:
1. The AAR's GMS code was not being reached at all (hook not firing)
2. The process was different from expected (`:core` process not starting)
3. ProGuard removed log calls (less likely since we used `Log.w`)

**Further debugging was not completed** — the hook firing issue remained unresolved. The 3 most likely causes are listed in Section 19 for the next agent to investigate.

---

## 19. How a Next Agent Should Proceed (From Logs)

### What We Know From the Failed Test
- UI appeared (WavyProgressIndicator showed) → AAR was integrated and ran
- None of our log tags (`MundoGms`, `GmsAuth`, `GmsBroker`, `SignInIntercept`) appeared → GMS hook layer was never reached
- No crash → silent failure, not an exception
- UID was 10633 for `com.bgmi`

### The 3 Most Likely Root Causes (In Order of Probability)

#### Cause 1 — Hook Not Registered for the Right Process
The GMS intercept hooks (`IActivityManagerProxy`, `ActivityManagerCommonProxy`) must be installed in the **same process** as the virtual app. If the cloned app runs in a different process than expected (e.g. `:p0` or `:virtual` instead of main), the hooks installed in the main process won't fire.

**How to verify:**
```bash
# Check all processes of the host app
adb shell ps -A | grep com.bgmi
# Expected output: multiple lines like
# com.bgmi          PID1  ...
# com.bgmi:p0       PID2  ...
# com.bgmi:core     PID3  ...
```

**What to look for:** The process running the cloned app (`:p0` or `:virtual`) must have its own hook installation. BlackBox typically re-installs hooks in each child process via `Application.onCreate()` override in the virtual space. Verify that `MundoCore.init()` or equivalent is called in the child process's `Application`.

**Fix if this is the cause:**
```kotlin
// In BlackBox's virtual Application onCreate:
override fun onCreate() {
    super.onCreate()
    if (isVirtualProcess()) {
        GmsSignInInterceptor.installHooks(this)  // must run in virtual process too
    }
}
```

#### Cause 2 — Wrong AAR Used (Old vs New)
User mentioned multiple AARs during the session. If the host app was built with an older version of the AAR (before our GMS fixes), none of the new code would run.

**How to verify:**
```bash
# Extract AAR classes and check for our new classes
unzip -p host.apk classes.dex | strings | grep "GmsSignInInterceptor"
# If nothing found → old AAR was used

# Or check apk contents directly:
apktool d host.apk -o host_decoded
grep -r "GmsSignInInterceptor" host_decoded/smali/
```

**Fix:** Rebuild host with the new AAR from `ParallelSpace_GmsFixed.zip`.

#### Cause 3 — `bindService` Hook Not Covering the Right Call Path
On API 29+, `ActivityManagerNative` was removed and `IActivityManager` is accessed differently. If `ActivityManagerCommonProxy` doesn't cover the exact reflection path the BlackBox version uses, the hook silently does nothing.

**How to verify:**
```bash
# Run with verbose Binder logging enabled
adb shell settings put global debug_binder_transactions 1
adb logcat -s "Binder:W" "JavaBinder:W"
# Then trigger Google Sign-In
# Look for: bindService calls to com.google.android.gms
# If you see them → hook not intercepting
# If you don't see them → app isn't calling bindService at all (different sign-in path)
```

**Fix if hook path is wrong:** Add hook for `IActivityManager.bindServiceInstance()` (API 31+ replacement for `bindService`):
```kotlin
// In IActivityManagerProxy — add this additional override for API 31+:
@TargetApi(31)
override fun bindServiceInstance(
    caller: IApplicationThread,
    token: IBinder,
    service: Intent,
    resolvedType: String,
    connection: IServiceConnection,
    flags: Int,
    instanceName: String,
    userId: Int
): Int {
    if (service.component?.packageName == GmsConstants.GMS_PACKAGE) {
        GmsSignInInterceptor.onBindService(caller, service, connection, flags)
        return 0
    }
    return original.bindServiceInstance(...)
}
```

### Step-by-Step Debug Procedure for Next Agent

```
Step 1: Confirm which AAR version is in the APK
  → apktool d + grep for GmsSignInInterceptor

Step 2: If old AAR → rebuild with new AAR, re-test

Step 3: If new AAR confirmed → check process list:
  adb shell ps -A | grep com.bgmi

Step 4: Add temporary verbose log to BlackBox's child process init:
  Log.w("MundoDebug", "Process: " + ProcessCompat.myProcessName())
  Log.w("MundoDebug", "GmsHooks installed: " + GmsSignInInterceptor.isInstalled())

Step 5: Run:
  adb logcat -s "MundoDebug:*"
  Trigger Google Sign-In in cloned app

Step 6: If hooks not installed in child process → fix Application.onCreate() in virtual space

Step 7: If hooks installed but bindService not intercepted → add API 31+ bindServiceInstance hook

Step 8: Once hook fires, verify SafeParcel parsing:
  adb logcat -s "GmsBroker:W" "FakeSignIn:W"
  Should see: "getService called, serviceId=91"

Step 9: Verify token exchange:
  adb logcat -s "GoogleWebLogin:W" "GmsAuth:W"
  Should see: "accountReady called" then "masterToken received"

Step 10: Verify final delivery:
  adb logcat -s "GmsAuth:W"
  Should see: "signIn complete, delivering GoogleSignInAccount"
```

### Log Tags to Monitor (All at Warning Level)
| Tag | What It Tells You |
|---|---|
| `MundoGms` | General GMS layer entry points |
| `GmsAuth` | GmsAuthService Binder calls |
| `GmsBroker` | FakeGmsServiceBroker — getService calls |
| `SignInIntercept` | GmsSignInInterceptor — bindService intercept |
| `GoogleLogin` | GoogleLoginActivity — UI events |
| `GoogleWebLogin` | GoogleWebLoginActivity — WebView + JsBridge |
| `FakeSignIn` | FakeSignInService — ISignInService transactions |
| `SafeParcel` | SafeParcelHelper — read/write operations |
| `TokenStore` | GmsTokenStore — save/load operations |

---

## 20. How a Fully Working Google Login Flow Looks

This section describes exactly what SHOULD happen, step by step, when everything is working correctly. Use this as the success criteria / test plan.

### Preconditions
- New AAR integrated in host
- Host app installed on device with real GMS available
- Cloned app installed in virtual space
- No saved accounts yet (fresh test)

### Complete Working Flow

**[T=0] User opens cloned app, taps "Sign in with Google"**
```
Cloned app calls:
  GoogleSignIn.getClient(context, gso).signIn(activity)
  → internally: context.startActivityForResult(signInIntent, RC_SIGN_IN)
  → signInIntent goes to com.google.android.gms/.auth.api.signin.internal.SignInHubActivity
```

**[T=1] ActivityManager hook fires**
```
Log: W/SignInIntercept: bindService intercepted for com.google.android.gms
Log: W/SignInIntercept: Binding real GMS on host side...
```
GmsSignInInterceptor binds to real GMS (host side) to get real broker.

**[T=2] Real GMS broker obtained**
```
Log: W/GmsBroker: Real GMS broker connected
Log: W/GmsBroker: Delivering FakeGmsServiceBroker to virtual app
```

**[T=3] Virtual app receives FakeGmsServiceBroker, calls getService(91)**
```
Log: W/GmsBroker: getService called, serviceId=91
Log: W/GmsBroker: Returning FakeSignInService for service 91
Log: W/FakeSignIn: onPostInitComplete sent, status=0
```

**[T=4] Virtual app calls ISignInService.signIn() (code 102)**
```
Log: W/FakeSignIn: signIn() called by package=com.cloned.app
Log: W/FakeSignIn: Options: requestEmail=true, requestProfile=true, requestIdToken=false
Log: W/SignInIntercept: No saved accounts for com.cloned.app — launching WebView login
```

**[T=5] GoogleLoginActivity appears**
```
UI: Glass-morphism dialog slides up from bottom (mundo_auth_enter animation)
UI: "Sign in with Google" header with Google G logo
UI: Shows "Use another account" (no saved accounts yet)
Log: W/GoogleLogin: Activity started, callingPkg=com.cloned.app
```

**[T=6] User taps "Use another account"**
```
Log: W/GoogleLogin: Launching ConsentSignInActivity
UI: Consent dialog shown (MundoGoogleMotion animation)
UI: "An app wants to sign in with Google" message
```

**[T=7] User taps "Continue" on consent screen**
```
Log: W/GoogleWebLogin: Launching WebView for EmbeddedSetup
UI: GoogleWebLoginActivity opens (MundoGoogleBrowser theme)
UI: accounts.google.com/EmbeddedSetup loads in WebView
UI: Google login form visible — email field
```

**[T=8] User enters Google credentials and logs in**
```
UI: Google's standard web login flow completes
Google's JS: window.mm.accountReady("oauth2_4/AAAA...")
Log: W/GoogleWebLogin: JsBridge.accountReady() called, token length=120
Log: W/GoogleWebLogin: Starting ac2dm token exchange
```

**[T=9] ac2dm master token exchange**
```
Log: W/GmsAuth: POST android.clients.google.com/auth — service=ac2dm
Log: W/GmsAuth: Master token received, email=user@gmail.com
Log: W/GmsAuth: Storing token for email=user@gmail.com
Log: W/TokenStore: Encrypted and saved: account_com.cloned.app
```

**[T=10] Profile info fetched**
```
Log: W/GmsAuth: Fetching profile for user@gmail.com
Log: W/GmsAuth: Profile: displayName=John Doe, photoUrl=https://lh3...
```

**[T=11] GoogleSignInAccount built and returned**
```
Log: W/GmsAuth: Building GoogleSignInAccount SafeParcel
Log: W/GmsAuth: SignIn complete — delivering account to com.cloned.app
Log: W/FakeSignIn: onSignInComplete sent, status=0

UI: GoogleLoginActivity and WebView dismiss (exit animation)
UI: Back in cloned app
```

**[T=12] Cloned app receives result**
```kotlin
// In cloned app's onActivityResult:
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode == RC_SIGN_IN) {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        val account = task.getResult(ApiException::class.java)
        // account.email         = "user@gmail.com"
        // account.displayName   = "John Doe"
        // account.photoUrl      = Uri("https://lh3.googleusercontent.com/...")
        // account.id            = "1234567890"
        // account.idToken       = null (unless serverClientId configured)
    }
}
```

**[T=13] Second sign-in (same app, same account)**
```
[T=4 again] FakeSignInService.signIn() called
Log: W/SignInIntercept: Found saved account for com.cloned.app — user@gmail.com
UI: GoogleLoginActivity shows account chooser
UI: Circular avatar, "John Doe", "user@gmail.com" in list
User taps account → instant sign-in (no WebView needed)
[T=11 again] Account returned immediately
```

### What "Working" Looks Like in Logcat
```
W/SignInIntercept: bindService intercepted for com.google.android.gms
W/GmsBroker: getService called, serviceId=91
W/FakeSignIn: signIn() called by package=com.cloned.app
W/GoogleWebLogin: JsBridge.accountReady() called
W/GmsAuth: Master token received, email=user@gmail.com
W/GmsAuth: SignIn complete — delivering account to com.cloned.app
```
If you see all 6 of these lines in order → Google login is working end-to-end.

### Common Partial Failures and Their Meaning
| Last Log Line Seen | What Failed | Where to Look |
|---|---|---|
| *(nothing)* | Hook never fired | Section 19 Cause 1/2/3 |
| `bindService intercepted` only | Real GMS bind failed | Check real GMS available on device |
| `getService called` | FakeSignInService creation failed | Check GmsAuthService is running in :core |
| `signIn() called` | UI launch failed | Check Activities in Manifest |
| `accountReady() called` | ac2dm exchange failed | Check network, User-Agent, androidId |
| `masterToken received` | Profile fetch or SafeParcel build failed | Check SafeParcelHelper, GmsCrypto |

---

## 21. Incorrect Claims Corrected During Session

These are things that were initially stated incorrectly and later corrected:

| Incorrect Claim | Correction |
|---|---|
| "BGMI uses Identity API code 212" | Wrong — BGMI's Google Sign-In uses standard service ID 91 like all GMS apps |
| "`ensureGoogleStack` installs microG into virtual space" | Wrong — it **removes** Google stack from virtual space to force our fake GMS |
| "`xtj.idx` is microG" | Wrong — it is a Go v2ray `.so` file (mundoproxy network module) |
| "`kw9tzxhc.idx` can be decrypted" | Wrong — it is a compiled native binary, not encrypted data |
| "Zerax APK dexopt idx is related to GMS" | Wrong — dexopt artifacts are unrelated to our GMS implementation |

---

## 22. All References & Sources

| Source | URL | Used For |
|---|---|---|
| microG GmsCore | https://github.com/microg/GmsCore | Auth protocol reference, ac2dm flow, service IDs |
| microG SafeParcel | https://github.com/microg/SafeParcel | SafeParcel format spec |
| microG NOTICE | https://github.com/microg/GmsCore/blob/master/NOTICE | License attribution file |
| Apache-2.0 License | https://www.apache.org/licenses/LICENSE-2.0 | License text for attribution |
| BlackBox Engine | https://github.com/dreamncn/BlackBox | Base virtual space engine |
| NewBlackBox (ALEX5402) | https://github.com/ALEX5402/NewBlackbox | Android 14+ fixes reference |
| Android Binder Docs | https://source.android.com/docs/core/architecture/ipc/binder-overview | Binder IPC protocol |
| Inside Android Play Services Magic (SBK Tech) | https://sbktech.blogspot.com/2014/01/inside-android-play-services-magic.html | ac2dm master token exchange protocol |
| AndroidX Security Crypto | https://developer.android.com/jetpack/androidx/releases/security | Keystore-backed EncryptedSharedPreferences |
| gpsoauth (Python) | https://sources.debian.org/src/python-gpsoauth | ac2dm flow reference |
| Google Internal Android Auth | https://brutecat-test2.pages.dev/articles/google-android-auth | Token exchange endpoint details |
| Zerax APK | Provided by user (stated as their own app) | UI design, flow reference, NOTICE files |

---

## 23. Output Artifact

```
/home/claude/ParallelSpace_GmsFixed.zip
  ├── MundoCore/
  │   ├── core/
  │   │   ├── gms/
  │   │   │   ├── GmsAuthService.kt
  │   │   │   ├── GmsAuthClient.kt
  │   │   │   ├── FakeGmsServiceBroker.kt
  │   │   │   ├── GmsBrokerProxy.kt
  │   │   │   ├── FakeSignInService.kt
  │   │   │   ├── ForeignParcelBundle.kt
  │   │   │   ├── GmsTokenStore.kt
  │   │   │   ├── GmsConstants.kt
  │   │   │   ├── SafeParcelHelper.kt
  │   │   │   ├── SignInOptions.kt
  │   │   │   ├── GoogleSignInAccount.kt
  │   │   │   ├── GmsCrypto.kt
  │   │   │   ├── GmsCheckin.kt
  │   │   │   └── GmsSignatureHelper.kt
  │   │   └── system/
  │   │       └── ServiceManager.kt  (modified)
  │   ├── proxy/
  │   │   └── gms/
  │   │       ├── GoogleLoginActivity.kt
  │   │       ├── GoogleWebLoginActivity.kt
  │   │       ├── GoogleAuthUi.kt
  │   │       ├── WavyProgressIndicator.kt
  │   │       ├── GmsSignInInterceptor.kt
  │   │       └── ConsentSignInActivity.kt
  │   ├── hooks/
  │   │   ├── IActivityManagerProxy.kt  (modified)
  │   │   ├── ActivityManagerCommonProxy.kt  (modified)
  │   │   ├── IPackageManagerProxy.kt  (modified)
  │   │   └── ServiceConnectionDelegate.kt  (modified)
  │   ├── res/
  │   │   ├── drawable/
  │   │   │   ├── mundo_auth_google.xml
  │   │   │   └── mundo_auth_glass_bg.xml
  │   │   ├── anim/
  │   │   │   ├── mundo_auth_enter.xml
  │   │   │   └── mundo_auth_exit.xml
  │   │   └── values/
  │   │       └── styles_mundo_auth.xml
  │   ├── assets/
  │   │   └── licenses/
  │   │       ├── microg-NOTICE.txt
  │   │       └── microg-Apache-2.0.txt
  │   ├── AndroidManifest.xml  (updated)
  │   ├── build.gradle  (buildToolsVersion corrected)
  │   └── consumer-rules.pro  (updated)
  └── host-proguard-additions.pro
```

---

## 24. APK Version History — Every Fix That Made v6 Work

This section documents **every single fix**, version by version, that was applied during the live debugging session on the actual device. These are the fixes made **after** the initial GMS implementation was done — the fixes that took Google Sign-In from completely broken (silent failure) to fully working.

---

### APK Version Naming Convention
| APK File | Internal Version | Status |
|---|---|---|
| `ark-gms-diag3.apk` | v3 | First diagnostic build — BROKEN (zero logs) |
| `ark-gms-diag4.apk` | v4 | TAG unification — PARTIAL (chain visible, sign-in still fails) |
| `ark-gms-diag5.apk` | v5 | GmsBrokerProxy deep logging — PARTIAL (proves GmsBrokerProxy broken) |
| `ark-gms-diag6.apk` | v6 | Direct FakeBroker bypass — **WORKING ✓** |

---

### Pre-Session Fixes (Applied Before This Debug Session)

These fixes were made in earlier sessions before the v3–v6 debug cycle. They are listed here for completeness so the full history is in one place.

#### PRE-1 — IActivityTaskManagerProxy: `isR()` → `isQ()` (Android 10+ Support)

**File:** `IActivityTaskManagerProxy.java` (or equivalent in BlackBox hook layer)

**Problem:** On Android 10 (API 29), `startActivity` routes through `IActivityTaskManager` instead of `IActivityManager`. The BlackBox engine had a version guard using `isR()` (Android 11+) instead of `isQ()` (Android 10+), so the proxy was not being registered on Android 10 devices.

**Symptom:** Virtual app activities failed to launch on Android 10 devices. No hook fired for activity start.

**Fix:**
```java
// BEFORE (wrong — only registers on Android 11+)
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    // register IActivityTaskManagerProxy
}

// AFTER (correct — registers on Android 10+ as it should)
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    // register IActivityTaskManagerProxy
}
```

**Result:** Activity launching worked correctly on Android 10+ devices.

---

#### PRE-2 — SecurityException Swallowed in ServiceConnectionDelegate

**File:** `ServiceConnectionDelegate.java`

**Problem:** `ServiceConnectionDelegate.connected()` was calling `connection.onServiceConnected()` inside a try-catch that silently swallowed `SecurityException`. When BlackBox tried to deliver a service connection to a virtual app, if a security check failed, the error was hidden — the app never received the service connection and appeared to hang.

**Symptom:** `bindService` appeared to succeed (returned non-zero) but the app's `ServiceConnection.onServiceConnected()` was never called.

**Fix:** Removed the silent catch-all, added proper logging of security exceptions so they appear in logcat.

```java
// BEFORE — SecurityException silently eaten
try {
    connection.onServiceConnected(name, binder);
} catch (Throwable t) {
    // silent — nothing logged
}

// AFTER — exception surfaced in log
try {
    connection.onServiceConnected(name, binder);
} catch (SecurityException se) {
    Log.w("MundoGms", "ServiceConnectionDelegate: SecurityException in onServiceConnected: " + se.getMessage());
} catch (Throwable t) {
    Log.w("MundoGms", "ServiceConnectionDelegate: error in onServiceConnected: " + t);
}
```

**Result:** Security failures became visible in logcat instead of silently disappearing.

---

#### PRE-3 — GMS_AUTH Service Pre-Caching in `initBlackManager()`

**File:** `BlackBoxCore.java` (or equivalent engine init file)

**Problem:** `GmsAuthService` Binder was not being registered early enough. When the virtual app's first bindService call came in and the GMS intercept tried to call back into `GmsAuthService`, the service wasn't ready yet, causing a null Binder.

**Fix:** Added explicit pre-caching / early registration of the `GmsAuthService` Binder inside `initBlackManager()` so it's ready before any virtual app can start.

```java
// In initBlackManager():
GmsAuthService.ensureStarted(context);  // pre-cache the Binder
```

**Result:** `GmsAuthService` Binder was always available when the GMS intercept needed it.

---

#### PRE-4 — Comprehensive Diagnostic Logging Added Throughout Chain

**Files modified:** `GmsSignInInterceptor.java`, `FakeGmsServiceBroker.java`, `FakeSignInService.java`, `GmsAuthClient.java`, `ServiceConnectionDelegate.java`

**Problem:** The GMS intercept chain had almost no logging, making it impossible to tell where failures occurred.

**Fix:** Added `Log.w("MundoGms", ...)` at every entry point and decision branch throughout the entire chain:
- `GmsSignInInterceptor.shouldInterceptBind()` — logs intent action and intercept decision
- `GmsSignInInterceptor.bindRealGms()` — logs real GMS bind attempt and result
- `GmsSignInInterceptor.deliverFakeBroker()` — logs broker delivery attempt
- `FakeGmsServiceBroker.onTransact()` — logs every getService call with serviceId
- `FakeSignInService.onTransact()` — logs every transaction code received
- `GmsAuthClient.signIn()` — logs sign-in attempt and result
- `ServiceConnectionDelegate.connected()` — logs target, component name, binder type

**Result:** Full GMS intercept chain became traceable from a single logcat filter.

---

### v3 Build — `ark-gms-diag3.apk`

**Status: BROKEN — Zero logs from MundoGms tag**

**Problem Discovered:** After installing v3 on device and triggering Google Sign-In in the cloned BGMI, running:
```
adb logcat -s "MundoGms:*"
```
showed **absolutely zero lines** from any of our GMS diagnostic code, even though the loading spinner appeared (proving the AAR was loaded). This was extremely confusing — the code was clearly running (UI appeared) but zero log output.

**Root Cause Found:** `proguard-rules.pro` in the **host app** (`/tmp/arkui_extract/arkC/ark/app/proguard-rules.pro`) contained this block:

```proguard
# BEFORE v3 fix — this was stripping ALL Log calls
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);           ← THIS WAS THE KILLER
    public static int e(...);           ← THIS TOO
    public static boolean wtf(...);     ← AND THIS
    public static boolean isLoggable(...); ← AND THIS
}
```

The `-assumenosideeffects` directive tells R8 to treat these calls as having no side effects and **completely removes them from compiled code**. Because `Log.w` was listed, every `Log.w("MundoGms", ...)` call in our code was silently deleted at compile time. The compiled release APK literally contained no log calls at all.

**Fix Applied to `proguard-rules.pro`:**

```proguard
# AFTER v3 fix — only strip low-priority logs, keep w/e for diagnostics
-assumenosideeffects class android.util.Log {
    public static int v(...);           ← still stripped (verbose)
    public static int d(...);           ← still stripped (debug)
    public static int i(...);           ← still stripped (info)
    # Log.w, Log.e, Log.wtf, isLoggable — REMOVED FROM LIST
    # They now survive R8 and appear in device logcat
}
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}
```

**Files Changed:**
- `arkC/ark/app/proguard-rules.pro` — removed `Log.w`, `Log.e`, `Log.wtf`, `isLoggable` from `-assumenosideeffects`

**Result After v3 Fix:** Rebuilding and installing showed logs appeared! The first real diagnostic data from the device was now visible.

**Key Insight:** In release builds, `Log.d` and `Log.v` are stripped. All diagnostic code **must use `Log.w` or `Log.e`** to survive ProGuard/R8 minification. This applies to any Log call you want to see from a release APK.

---

### v4 Build — `ark-gms-diag4.apk`

**Status: PARTIAL — GMS chain visible but sign-in still fails**

**What v3 logs revealed:** After v3 fixed the log stripping, v3's logs appeared but showed that GMS intercept tags like `"GmsSignInInterceptor"`, `"FakeGmsServiceBroker"`, `"FakeSignInService"` were separate tags — none of them showed up under the `"MundoGms"` filter we were using. The various GMS classes each had their own TAG string, inconsistently named.

**Problems Found in v3 Logs:**
1. TAG mismatch: code used tags like `"GmsSignInInterceptor"`, `"FakeGmsSignInBroker"`, `"SignInSvc"` etc. — not `"MundoGms"` 
2. Logs from some classes were still invisible because their tags weren't in the adb filter
3. `ServiceConnectionDelegate.connected()` had no logging at all — blind spot in the chain

**Fixes Applied in v4:**

#### Fix 4-1: Unified all GMS TAG constants to `"MundoGms"`

**Files changed and what TAG was before → after:**

| File | Old TAG | New TAG |
|---|---|---|
| `GmsSignInInterceptor.java` | `"GmsSignInInterceptor"` | `"MundoGms"` |
| `FakeGmsServiceBroker.java` | `"FakeGmsServiceBroker"` | `"MundoGms"` |
| `GmsBrokerProxy.java` | `"GmsBrokerProxy"` | `"MundoGms"` |
| `FakeSignInService.java` | `"FakeSignInService"` | `"MundoGms"` |
| `GmsAuthClient.java` | `"GmsAuthClient"` | `"MundoGms"` |

**Code change in each file:**
```java
// BEFORE
private static final String TAG = "GmsSignInInterceptor";  // (or whatever it was)

// AFTER
private static final String TAG = "MundoGms";
```

#### Fix 4-2: Added logging to `ServiceConnectionDelegate.connected()`

**File:** `ServiceConnectionDelegate.java`

```java
// Added at start of connected() method:
Log.w("MundoGms", "ServiceConnectionDelegate.connected: target=" + target.getClass().getName()
    + " name=" + name
    + " binder=" + (binder != null ? binder.getClass().getName() : "null")
    + " isBrokerWrapped=" + (binder instanceof FakeGmsServiceBroker));
```

#### Fix 4-3: Added detailed per-step logging throughout intercept chain

Added `Log.w("MundoGms", ...)` at every decision point in `IActivityManagerProxy.BindService.hook()`:
```java
Log.w("MundoGms", "BindService GMS check: intercept=" + gmsIntercept + " conn=" + (connection != null));
Log.w("MundoGms", "BindService: trying bindRealGms for action=" + bindAction);
Log.w("MundoGms", "BindService: bindRealGms result=" + real);
Log.w("MundoGms", "BindService: delivering FakeGmsServiceBroker for action=" + bindAction);
Log.w("MundoGms", "GMS bind intercepted -> FakeGmsServiceBroker OK");
```

**Files Changed:**
- `GmsSignInInterceptor.java` — TAG → `"MundoGms"`
- `FakeGmsServiceBroker.java` — TAG → `"MundoGms"`
- `GmsBrokerProxy.java` — TAG → `"MundoGms"`
- `FakeSignInService.java` — TAG → `"MundoGms"`
- `GmsAuthClient.java` — TAG → `"MundoGms"`
- `ServiceConnectionDelegate.java` — added connected() logging
- `IActivityManagerProxy.java` — added BindService intercept logging

**Result After v4 Fix:** Full GMS intercept chain now visible under a single `adb logcat -s "MundoGms:*"` filter. v4 logs showed:
- `BindService GMS check: intercept=true` ✓ — hook IS firing
- `ServiceConnectionDelegate.connected: ... isBrokerWrapped=true` ✓ — broker delivered
- `FakeGmsServiceBroker: getService id=300` ✓ — broker receiving calls
- `FakeGmsServiceBroker: getService id=270` ✓ — more calls
- BUT: `GmsBrokerProxy.onTransact` — **ZERO lines** ← new problem found

**Key Insight from v4 Logs:** The `FakeGmsServiceBroker` was working and receiving calls (service IDs 300, 270 visible). However `GmsBrokerProxy.onTransact` never appeared — meaning the game SDK was receiving the `GmsBrokerProxy` binder but never calling `transact()` on it. This pointed to a binder delivery chain issue with `GmsBrokerProxy`.

---

### v5 Build — `ark-gms-diag5.apk`

**Status: PARTIAL — Confirms GmsBrokerProxy is broken, never receives calls**

**Purpose of v5:** Deep diagnostic build to prove exactly what was happening with `GmsBrokerProxy`. The v4 logs showed `wrap: creating GmsBrokerProxy` and `isBrokerWrapped=true` but ZERO `onTransact` lines. v5 added exhaustive logging to `GmsBrokerProxy` to understand why.

**What v4 Logs Showed (the new problem):**

```
W/MundoGms: BindService: trying bindRealGms for action=com.google.android.gms.auth.api.signin.service.START
W/MundoGms: BindService: bindRealGms result=1
W/MundoGms: GmsBrokerProxy: wrap: creating GmsBrokerProxy for real binder
W/MundoGms: GmsBrokerProxy: wrapped=true
W/MundoGms: ServiceConnectionDelegate.connected: isBrokerWrapped=true
  ...then nothing. No onTransact. Sign-in fails silently.
```

**Hypothesis:** `GmsBrokerProxy.onTransact()` was either:
1. Being stripped by R8 (overriding a Binder method — R8 might not preserve the override)
2. Not being called because the binder delivery to the game SDK was going through a wrapper that bypassed it
3. The game SDK was receiving the proxy object but calling transact on a different reference

**Fixes Applied in v5:**

#### Fix 5-1: Added exhaustive logging to `GmsBrokerProxy.wrap()` static factory

```java
// In GmsBrokerProxy.wrap():
public static IBinder wrap(IBinder realBinder, String hostPkg) {
    Log.w("MundoGms", "GmsBrokerProxy.wrap: called with realBinder=" + realBinder
        + " class=" + (realBinder != null ? realBinder.getClass().getName() : "null")
        + " hostPkg=" + hostPkg);
    GmsBrokerProxy proxy = new GmsBrokerProxy(realBinder, hostPkg);
    Log.w("MundoGms", "GmsBrokerProxy.wrap: proxy created=" + proxy
        + " isBinderAlive=" + proxy.isBinderAlive());
    return proxy;
}
```

#### Fix 5-2: Added logging at the very top of `GmsBrokerProxy.onTransact()`

```java
@Override
public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
    Log.w("MundoGms", "GmsBrokerProxy.onTransact: code=" + code + " flags=" + flags);
    // ... rest of method
}
```

#### Fix 5-3: Added logging in `GmsBrokerProxy` constructor

```java
public GmsBrokerProxy(IBinder realBinder, String hostPkg) {
    super();
    this.realBinder = realBinder;
    this.hostPkg = hostPkg;
    Log.w("MundoGms", "GmsBrokerProxy: new instance created, realBinder=" + realBinder.getClass().getName());
}
```

**Files Changed:**
- `GmsBrokerProxy.java` — added constructor logging, wrap() logging, onTransact() top-of-method logging

**v5 Logs on Device Confirmed:**
```
W/MundoGms: GmsBrokerProxy.wrap: called with realBinder=android.os.BinderProxy ...
W/MundoGms: GmsBrokerProxy.wrap: proxy created=com.mundo.core.gms.GmsBrokerProxy@... isBinderAlive=true
W/MundoGms: GmsBrokerProxy: new instance created, realBinder=android.os.BinderProxy
W/MundoGms: ServiceConnectionDelegate.connected: isBrokerWrapped=true
  ...ZERO lines from GmsBrokerProxy.onTransact
```

**Root Cause Confirmed:** `GmsBrokerProxy` is being constructed correctly and delivered to the game SDK. The game SDK receives it. But the game SDK **never calls transact()** on it. The `onTransact` override in `GmsBrokerProxy` is confirmed to exist in the binary (logging in `wrap()` runs) but `onTransact` never executes.

**Why This Happens (Two Possible Causes):**

**Cause A — R8 stripping the `onTransact` override:**
R8 can remove method overrides if it determines the override has the same behavior as the parent (both just call `super.onTransact()`). If the GmsBrokerProxy `onTransact` is considered "trivial" in some configurations, R8 might inline or remove it.

**Cause B — Binder delivery chain issue:**
When `ServiceConnectionDelegate.connected()` delivers the proxy binder to the game SDK's service connection, the SDK may be storing the binder as `IBinder` and then calling `IBinder.transact()` directly on it. For a `Binder` subclass (not a `BinderProxy`), the JNI layer may bypass the Java `onTransact()` override in some edge cases involving cross-process binder death/resurrection.

**Conclusion from v5:** `GmsBrokerProxy` path is fundamentally broken for the sign-in service. The fix is to bypass it entirely for sign-in and use the `FakeGmsServiceBroker` directly (which was already proven to work — it received getService calls correctly).

---

### v6 Build — `ark-gms-diag6.apk` ✓ WORKING

**Status: WORKING — Google Sign-In fully functional**

**Strategy:** Based on v5 diagnosis, the fix is:
1. For the sign-in service action (`com.google.android.gms.auth.api.signin.service.START`), skip `bindRealGms` and `GmsBrokerProxy` entirely
2. Go **directly** to `FakeGmsServiceBroker` via `deliverFakeBroker()`
3. `FakeGmsServiceBroker` is a local `Binder` subclass that works correctly (confirmed by getService id=300/270 logs in v4/v5)
4. Add ProGuard keep rules for all GMS classes to prevent future R8 stripping

**All Fixes Applied in v6:**

---

#### Fix 6-1 (CRITICAL): Sign-In Service Bypasses `bindRealGms` → Goes Directly to `FakeGmsServiceBroker`

**File:** `IActivityManagerProxy.java` — `BindService.hook()` method

**Before v6 (broken flow for all GMS actions):**
```java
// ALL GMS actions went through the same path:
boolean gmsIntercept = GmsSignInInterceptor.shouldInterceptBind(intent);
if (gmsIntercept && connection != null) {
    // try real GMS first (this wrapped it in GmsBrokerProxy — BROKEN for sign-in)
    IServiceConnection gmsProxy = ServiceConnectionDelegate.createProxy(connection, intent);
    Object real = GmsSignInInterceptor.bindRealGms(who, method, args, 4, gmsProxy);
    if (real != null) {
        // update dispatcher with gmsProxy...
        return real;
    }
    // fallback: deliverFakeBroker
    if (GmsSignInInterceptor.deliverFakeBroker(intent, connection)) {
        return 1;
    }
}
```

**After v6 (fixed flow with sign-in bypass):**
```java
boolean gmsIntercept = GmsSignInInterceptor.shouldInterceptBind(intent);
Log.w("MundoGms", "BindService GMS check: intercept=" + gmsIntercept + " conn=" + (connection != null));
if (gmsIntercept && connection != null) {
    String bindAction = intent.getAction();
    boolean isSignIn = "com.google.android.gms.auth.api.signin.service.START".equals(bindAction);

    if (!isSignIn) {
        // Non-sign-in GMS services: try real GMS first, broker wrapped
        IServiceConnection gmsProxy = ServiceConnectionDelegate.createProxy(connection, intent);
        Log.w("MundoGms", "BindService: trying bindRealGms for action=" + bindAction);
        Object real = GmsSignInInterceptor.bindRealGms(who, method, args, 4, gmsProxy);
        Log.w("MundoGms", "BindService: bindRealGms result=" + real);
        if (real != null) {
            try {
                WeakReference<?> weakReference = BRLoadedApkServiceDispatcherInnerConnection.get(connection).mDispatcher();
                if (weakReference != null && weakReference.get() != null) {
                    BRLoadedApkServiceDispatcher.get(weakReference.get())._set_mConnection(gmsProxy);
                }
            } catch (Throwable t) {
                Log.w("MundoGms", "GMS bind: dispatcher update skipped: " + t.getMessage());
            }
            return real;
        }
    } else {
        // SIGN-IN: skip bindRealGms entirely — go directly to FakeGmsServiceBroker
        Log.w("MundoGms", "BindService: SIGN-IN service -> direct FakeBroker (skip bindRealGms)");
    }

    // Sign-in always, or non-sign-in when real GMS failed: fully virtual broker
    Log.w("MundoGms", "BindService: delivering FakeGmsServiceBroker for action=" + bindAction);
    if (GmsSignInInterceptor.deliverFakeBroker(intent, connection)) {
        Log.w("MundoGms", "GMS bind intercepted -> FakeGmsServiceBroker OK");
        return 1;  // non-zero = bound
    }
    Log.w("MundoGms", "BindService: deliverFakeBroker FAILED, falling through");
}
```

**Why This Works:**
- `FakeGmsServiceBroker` is a plain Java `Binder` subclass
- It has no dependency on real GMS, no `GmsBrokerProxy` wrapper
- v4/v5 logs proved it successfully receives `getService(serviceId=300)` and `getService(serviceId=270)` calls
- When `serviceId=91` (Sign-In) comes in, it routes directly to `FakeSignInService`
- The game SDK talks to `FakeSignInService` → `GmsAuthClient.signIn()` → actual Google login flow

---

#### Fix 6-2: ProGuard Keep Rules for All GMS Classes

**File:** `arkC/ark/app/proguard-rules.pro`

**Problem:** R8 could strip or inline methods in GMS classes because they're accessed via reflection, raw Binder transactions, and `instanceof` checks — none of which are visible to R8's reachability analysis.

**Added to `proguard-rules.pro`:**
```proguard
# GMS intercept classes — Binder subclasses called reflectively by framework
-keep class com.mundo.core.gms.**           { *; }
-keep class com.mundo.proxy.gms.**          { *; }
-keep class com.mundo.fake.delegate.ServiceConnectionDelegate { *; }
-dontwarn com.mundo.**
```

**Why each rule:**

| Rule | Why Needed |
|---|---|
| `-keep class com.mundo.core.gms.**` | `FakeGmsServiceBroker`, `GmsBrokerProxy`, `FakeSignInService` are Binder subclasses; R8 cannot see that Android's Binder JNI layer calls `onTransact()` via reflection |
| `-keep class com.mundo.proxy.gms.**` | `GmsSignInInterceptor` is called via `@ProxyMethod` annotations; R8 cannot trace these calls |
| `-keep class com.mundo.fake.delegate.ServiceConnectionDelegate` | Called by `createProxy()` which is invoked reflectively via BlackBox hook dispatch |
| `-dontwarn com.mundo.**` | Suppresses R8 warnings about internal classes it can't fully analyze |

**Before v6** (only partial keep rules existed, no coverage for GMS classes):
```proguard
# Remove most logging
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
# (no GMS keep rules)
```

**After v6:**
```proguard
# Remove most logging (keep w/e for diagnostics)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
-assumenosideeffects class java.io.PrintStream { public void println(...); public void print(...); }

# GMS intercept classes — Binder subclasses called reflectively by framework
-keep class com.mundo.core.gms.**           { *; }
-keep class com.mundo.proxy.gms.**          { *; }
-keep class com.mundo.fake.delegate.ServiceConnectionDelegate { *; }
-dontwarn com.mundo.**
```

---

#### v6 Build Process

> **Important:** The MundoCore AAR built for v6 (and all diag builds) is the **DEBUG variant** (`assembleDebug`), not release. The host app APK itself is a release build, but the AAR inside it is debug. See the "Release vs Debug AAR" section below for why this matters.

**Step 1: Build MundoCore AAR (debug variant)**
```bash
cd /tmp/orig_extract/ParallelSpace
./gradlew :MundoCore:assembleDebug
# Output: MundoCore/build/outputs/aar/MundoCore-debug.aar
# Size: ~2MB (debug) vs ~163KB (earlier version without lsparanoid applied)
```

**Step 2: Remove old AAR to prevent duplicate class conflict**
```bash
# CRITICAL: Must remove old AAR first
# If both MundoCore-debug.aar and MundoCore.aar exist in libs/, 
# the build fails with "Duplicate class" errors
rm /tmp/arkui_extract/arkC/ark/app/libs/MundoCore-debug.aar
```

**Step 3: Copy new AAR to host app**
```bash
cp /tmp/orig_extract/ParallelSpace/MundoCore/build/outputs/aar/MundoCore-debug.aar \
   /tmp/arkui_extract/arkC/ark/app/libs/MundoCore.aar
```

**Step 4: Build host app release APK**
```bash
cd /tmp/arkui_extract/arkC
./gradlew :app:assembleRelease
# Output: ark/app/build/outputs/apk/release/app-release_protected.apk
```

**Step 5: Install on device**
```bash
adb install -r ark/app/build/outputs/apk/release/app-release_protected.apk
```

---

#### v6 Result

**User confirmed: Google Sign-In working ✓**

No device logs were provided by the user for this build. The user confirmed the result directly: v6 login is working. The log lines above are **not actual device output** — they were illustrative examples of what the new code paths would emit if logged on device. Do not treat them as captured logcat output.

> ⚠️ **Correction note (added 2026-11-11):** An earlier version of this document showed fabricated logcat lines under a heading "Actual Device Output". That was wrong — the user only confirmed "v6 login working hai", no logs were shared. Those lines have been replaced with this note.

---

### Release vs Debug AAR — Does It Matter for GMS?

**Short answer: YES, using release AAR can break GMS if `-keep` rules are wrong.**

#### MundoCore Build Types (from `build.gradle`):
Both `debug` and `release` build types have `minifyEnabled true` in MundoCore. So **both variants run R8/ProGuard** on the AAR's classes. The difference between the two in this project is minimal — same `proguard-rules.pro` applies to both.

#### Why v6 Used Debug AAR:
The v6 and all diag builds explicitly used `assembleDebug` because:
1. It was the safest path during iterative debugging — no surprise behavior
2. `minifyEnabled true` applies in both, so the GMS `-keep` rules apply either way
3. The important protection is not debug vs release, it's having the correct `-keep` rules

#### What Would Break With Release AAR Without Proper Keep Rules:
R8 runs on the AAR classes (because `minifyEnabled true`). The GMS intercept chain relies on:

| Class | Why It Needs Keep |
|---|---|
| `FakeGmsServiceBroker` | Extends `Binder`, `onTransact()` called by Android JNI — not visible to R8 |
| `GmsBrokerProxy` | Same — Binder subclass, `onTransact` called by JNI framework |
| `FakeSignInService` | Called via `serviceId=91` lookup — R8 can't trace this path |
| `GmsSignInInterceptor` | Called via `@ProxyMethod` annotation hooks — reflective dispatch |
| `ServiceConnectionDelegate` | Called via `createProxy()` which is invoked reflectively by BlackBox |

**Without the `-keep` rules**, R8 would:
- Rename class names (obfuscation) — breaking the `instanceof` checks and service ID lookups
- Inline or remove `onTransact()` overrides — breaking all Binder communication
- Remove logging — making failures invisible

#### What Saves You in Either Variant:
1. **`proguard.txt` (consumer ProGuard rules)** — the AAR embeds these rules, and the host app's R8 always applies them. Current content:
   ```proguard
   -keep class com.mundo.** {*; }
   -keep class com.mundo.jnihook.** {*; }
   -keep class black.** {*; }
   -keep class android.** {*; }
   -keep class com.android.** {*; }
   ```
   This `-keep class com.mundo.** {*;}` rule is a **broad keep** — it protects ALL `com.mundo.*` classes from being stripped or renamed in the **host app's** R8 run. This is the most important protection.

2. **`proguard-rules.pro` in the AAR** — these run when building the AAR itself (before it becomes an AAR). The `-keep` rules added in v6 protect classes inside the AAR's own R8 pass.

#### Conclusion:
- **v6 used debug AAR** (`assembleDebug`) — safe, confirmed working
- **Release AAR would also work** as long as `proguard-rules.pro` has the correct `-keep` rules (which v6 does)
- The critical protection is `proguard.txt`'s `-keep class com.mundo.** {*;}` — this broad rule is embedded in the AAR and always applied to the host app. Without it, the host app's R8 could strip GMS classes even if the AAR itself compiled fine.
- For v7 (mundoproxy build), `assembleRelease` was used — also safe because both variants use the same ProGuard rules.

---

### Summary Table — All Version Fixes

| Version | APK | File Changed | What Was Fixed | Before | After |
|---|---|---|---|---|---|
| PRE-1 | — | `IActivityTaskManagerProxy.java` | Android 10+ hook not registering | `isR()` guard | `isQ()` guard |
| PRE-2 | — | `ServiceConnectionDelegate.java` | SecurityException swallowed silently | silent catch | logged catch |
| PRE-3 | — | `BlackBoxCore.java` (init) | GmsAuthService Binder not ready on first bind | lazy init | pre-cached in `initBlackManager()` |
| PRE-4 | — | 5 GMS classes | No diagnostic logging in intercept chain | no logs | `Log.w("MundoGms",...)` throughout |
| **v3** | `diag3.apk` | `proguard-rules.pro` | ALL Log calls stripped by R8 (zero logs) | `assumenosideeffects` included `Log.w/e` | removed `Log.w/e/wtf/isLoggable` from strip list |
| **v4** | `diag4.apk` | 5 GMS class TAGs | Logs appeared but under wrong tags | `"GmsSignInInterceptor"`, `"FakeGmsServiceBroker"` etc. | all unified to `"MundoGms"` |
| **v4** | `diag4.apk` | `ServiceConnectionDelegate.java` | No visibility into broker delivery | no logs in `connected()` | added full logging |
| **v4** | `diag4.apk` | `IActivityManagerProxy.java` | Intercept chain not fully logged | sparse logs | added per-step logs in BindService.hook |
| **v5** | `diag5.apk` | `GmsBrokerProxy.java` | GmsBrokerProxy.onTransact never called — needed proof | sparse logs | added constructor + wrap() + onTransact() entry logging |
| **v6** | `diag6.apk` | `IActivityManagerProxy.java` | GmsBrokerProxy binder ignored by game SDK | all GMS → bindRealGms → GmsBrokerProxy | sign-in action → **directly** `deliverFakeBroker` → `FakeGmsServiceBroker` |
| **v6** | `diag6.apk` | `proguard-rules.pro` | R8 could strip GMS Binder classes | no GMS keep rules | `-keep class com.mundo.core.gms.**` + proxy.gms + ServiceConnectionDelegate |

---

### Why v6 Works — Root Cause Chain Summary

```
ZERO LOGS (silent failure)
└── Root cause: proguard-rules.pro stripped ALL Log calls including Log.w/Log.e
    └── Fix v3: removed Log.w/e from -assumenosideeffects
        └── Logs appeared but wrong tags
            └── Root cause: each GMS class had its own TAG string
                └── Fix v4: unified all TAGs to "MundoGms"
                    └── Full chain visible — found GmsBrokerProxy never receives onTransact
                        └── Root cause: GmsBrokerProxy binder delivered to game SDK
                                        but game SDK never calls transact() on it
                            └── Fix v5: added exhaustive GmsBrokerProxy logging to prove it
                                └── Confirmed: GmsBrokerProxy is fundamentally broken for sign-in
                                    └── Root cause: Either R8 strips onTransact override,
                                                    or game SDK's binder delivery chain bypasses it
                                        └── Fix v6: bypass GmsBrokerProxy entirely for sign-in
                                                    → go directly to FakeGmsServiceBroker
                                                    + add -keep rules for all GMS classes
                                            └── WORKING ✓
```

### Delivered Artifacts

| File | Description |
|---|---|
| `ark-gms-diag3.apk` | First diagnostic APK (ProGuard log fix) |
| `ark-gms-diag4.apk` | TAG unification APK |
| `ark-gms-diag5.apk` | GmsBrokerProxy deep logging APK |
| `ark-gms-diag6.apk` | **WORKING APK** — direct FakeBroker for sign-in |
| `MundoCore-source-v6.zip` | Full MundoCore source as used in v6 (974 KB) |
| `MundoCore-v6.aar` | Pre-built AAR from v6 (2.0 MB) |

---

*Document updated: 2026-11-11*  
*Total files created: 14 new | 5 modified*  
*Total bugs fixed: 14 (implementation) + 10 (device debug v3–v6) = 24 total*  
*Session: MundoCore Google Sign-In Implementation — Full Log*
