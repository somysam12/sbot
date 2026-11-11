# Session 2 — Changes Log & AAR Integration Guide

---

## 1. Is Session Me Kya Hua (Summary)

Is session me **v6 MundoCore source se release AAR** banaya gaya (v7 mundoproxy changes hataake) aur usse ark app ke release APK me daalke test ke liye build kiya.

**Deliverables:**
- `MundoCore-release.aar` — 1.8MB, v6 clean release build
- `ark-v6-release-aar.apk` — 7.6MB, ark app release APK using that AAR

---

## 2. AAR Side Changes — `MundoCore/proguard-rules.pro`

### Kya Add Kiya (Missing tha)

v6 MundoCore source me ye block **bilkul nahi tha** — isi wajah se Google Sign-In release build me break ho sakta tha:

```proguard
# --- GMS intercept classes — explicit keep for release build ---
# Binder subclasses: Android JNI calls onTransact() via native — R8 can't see this reference
-keep class com.mundo.core.gms.**           { *; }
-keep class com.mundo.proxy.gms.**          { *; }
-keep class com.mundo.fake.delegate.ServiceConnectionDelegate { *; }
-dontwarn com.mundo.**
```

**Kyun zaroori hai:**
- `com.mundo.core.gms.**` = `GmsAuthService`, `GmsSignInInterceptor`, `FakeGmsServiceBroker`, `FakeSignInService` — ye sab Binder subclasses hain
- Android framework inhe JNI se call karta hai `onTransact()` via. R8 ko ye reference dikh nahi sakta (reflection/JNI boundary)
- Agar `-keep` nahi diya to R8 release build me in classes ko **silently delete** kar deta hai
- Result: Google Sign-In runtime pe crash ya completely fail ho jata

### Jo Pehle Se Tha (Change Nahi Hua)

```proguard
-dontshrink
-dontoptimize
-repackageclasses 'top.bienvenido.date_24323'
-allowaccessmodification

# WebView JS bridge
-keepclassmembers class com.mundo.proxy.gms.GoogleWebLoginActivity$JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.mundo.proxy.gms.GoogleWebLoginActivity$JsBridge
```

---

## 3. App Side — `ark/app/proguard-rules.pro` Changes

Ye changes **pichle session me already ho chuke the** (v6 build ke time). Is session me inhe just verify kiya gaya aur same raha.

### GMS Keeps (v6 me add hue the):

```proguard
# GMS intercept classes — Binder subclasses called reflectively by framework
-keep class com.mundo.core.gms.**           { *; }
-keep class com.mundo.proxy.gms.**          { *; }
-keep class com.mundo.fake.delegate.ServiceConnectionDelegate { *; }
-dontwarn com.mundo.**
```

### Log Stripping Fix (v3 me add hua tha — critical context):

**Problem:** `ark-gms-diag3.apk` install karne ke baad device pe **bilkul koi log nahi aayi** — ek bhi line nahi `adb logcat -s "MundoGms:*"` se. UI dikh rahi thi, spinner aa raha tha, matlab AAR load ho rahi thi — fir bhi zero logs.

**Root Cause:** Host app ke `proguard-rules.pro` me ye tha:

```proguard
# BEFORE v3 fix — ye sab Log calls strip ho rahe the
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);               ← YAHI PROBLEM THI
    public static int e(...);               ← YE BHI
    public static boolean wtf(...);         ← YE BHI
    public static boolean isLoggable(...);  ← YE BHI
}
```

`-assumenosideeffects` = R8 ko batata hai "ye methods ke koi side effects nahi hain" → R8 **call ko compiled code se completely hata deta hai**. `Log.w` listed hone ki wajah se hamare saare `Log.w("MundoGms", ...)` calls binary me hi nahi the. Release APK me literally ek bhi log call nahi tha.

**Fix (v3 me kiya):** `Log.w`, `Log.e`, `Log.wtf`, `isLoggable` ko list se hataaya:

```proguard
# AFTER v3 fix — sirf low-priority logs strip karo
-assumenosideeffects class android.util.Log {
    public static int v(...);    ← strip (verbose)
    public static int d(...);    ← strip (debug)
    public static int i(...);    ← strip (info)
    # Log.w, Log.e, Log.wtf, isLoggable — HATAYE GAYE
    # Ab ye device logcat me dikh te hain
}
```

**Result:** V3 ke baad rebuild aur install karne par logs aane lage. Pehli baar real diagnostic data device se mili.

**Rule:** Release APK me diagnostic logs ke liye hamesha `Log.w` ya `Log.e` use karo — `Log.d`/`Log.v`/`Log.i` R8 se strip ho jaate hain. Aur kabhi `Log.w`/`Log.e` ko `-assumenosideeffects` me mat daalo.

---

## 4. Kisi Bhi Dusre App Source Me Ye AAR Daalke Google Login Kaise Kaam Karaayein

**Scenario:** Naya host app source hai, usme `MundoCore.aar` daalke Google Sign-In kaam karaana hai.

### Step 1 — AAR copy karo

```
app/libs/MundoCore.aar    ← release AAR yahan daalein
```

### Step 2 — `app/build.gradle` me dependency add karo

```groovy
android {
    // ...
}

dependencies {
    implementation fileTree(dir: 'libs', include: ['*.aar', '*.jar'])
    // ya explicitly:
    implementation files('libs/MundoCore.aar')
}
```

### Step 3 — `app/proguard-rules.pro` me ye rules daalein (REQUIRED)

#### 3a. MundoCore repackaged classes keep karo

AAR build me `-repackageclasses 'top.bienvenido.date_24323'` hai — saari internal classes is package me move ho jaati hain. Inhe keep karna zaroori hai:

```proguard
-keep class top.bienvenido.**               { *; }
-dontwarn top.bienvenido.**
```

#### 3b. GMS intercept classes keep karo (Google Sign-In ke liye CRITICAL)

```proguard
-keep class com.mundo.core.gms.**           { *; }
-keep class com.mundo.proxy.gms.**          { *; }
-keep class com.mundo.fake.delegate.ServiceConnectionDelegate { *; }
-dontwarn com.mundo.**
```

**Ye kyun chahiye:** Ye classes R8 ko visible nahi hoti (JNI + reflection boundary). Bina in rules ke R8 inhe delete kar deta hai → Google Sign-In silently fail.

#### 3c. BlackBox external interface keep karo

```proguard
-keep class net_62v.**                      { *; }
-dontwarn net_62v.**
-keep class * implements net_62v.external.IMundoProcessCallback { *; }
-keepclassmembers class * implements net_62v.external.IMundoProcessCallback { *; }
```

#### 3d. Log stripping — CRITICAL MISTAKE SE BACHEIN

```proguard
# SAFE pattern — ye karo:
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# GALAT pattern — ye mat karo (logs completely gayab ho jaayenge):
# -assumenosideeffects class android.util.Log {
#     public static int w(...);               ← MAT DAALO
#     public static int e(...);               ← MAT DAALO
#     public static boolean wtf(...);         ← MAT DAALO
#     public static boolean isLoggable(...);  ← MAT DAALO
# }
```

#### 3e. Native methods protect karo

```proguard
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
```

#### 3f. LSParanoid (agar app me lsparanoid plugin hai)

```proguard
-keep @io.michaelrocks.paranoid.Obfuscate class * { *; }
-keep class io.michaelrocks.paranoid.**            { *; }
```

### Step 4 — MundoCore initialize karo

```java
// Application.onCreate() ya Activity.onCreate() me:
MundoCore.get().doAttachBaseContext(this, new ClientConfiguration());
// ya:
MundoCore.get().doCreate();
```

### Step 5 — AndroidManifest.xml me ye entries chahiye

```xml
<!-- Google login WebView activity -->
<activity
    android:name="com.mundo.proxy.gms.GoogleWebLoginActivity"
    android:theme="@style/MundoGoogleGlass"
    android:launchMode="singleTop" />

<!-- Account chooser -->
<activity
    android:name="com.mundo.proxy.gms.GoogleLoginActivity"
    android:theme="@style/MundoGoogleGlass"
    android:launchMode="singleTop" />

<!-- Consent screen -->
<activity
    android:name="com.mundo.proxy.gms.ConsentSignInActivity"
    android:theme="@style/MundoGoogleGlass" />
```

---

## 5. Minimum Required Proguard Rules — Quick Reference

Kisi bhi host app ke liye sirf zaroori rules (copy-paste ready):

```proguard
# ── MundoCore AAR — Required ProGuard Rules ──────────────────────────────────

# 1. Repackaged internal classes
-keep class top.bienvenido.**               { *; }
-dontwarn top.bienvenido.**

# 2. GMS / Google Sign-In classes (Binder subclasses — R8 can't see JNI refs)
-keep class com.mundo.core.gms.**           { *; }
-keep class com.mundo.proxy.gms.**          { *; }
-keep class com.mundo.fake.delegate.ServiceConnectionDelegate { *; }
-dontwarn com.mundo.**

# 3. BlackBox virtual space interface
-keep class net_62v.**                      { *; }
-dontwarn net_62v.**
-keep class * implements net_62v.external.IMundoProcessCallback { *; }
-keepclassmembers class * implements net_62v.external.IMundoProcessCallback { *; }

# 4. Native methods
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# 5. IMPORTANT: Log stripping — only strip low-priority, never w/e
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
```

---

## 6. Debug vs Release AAR — Difference

| Property | Debug AAR | Release AAR |
|---|---|---|
| Size | ~2.0MB | ~1.8MB |
| `minifyEnabled` | true (R8 runs) | true (R8 runs) |
| lsparanoid string obfuscation | Applied | Applied |
| ProGuard rules | Same rules | Same rules |
| `-dontshrink` / `-dontoptimize` | Yes (in both) | Yes (in both) |
| Use case | Development / logcat debug | Production / test |

**Note:** Dono variants me `minifyEnabled true` hai — isliye dono me ProGuard rules critical hain. Debug variant ke baad bhi GMS issues aa sakte hain agar rules galat hoon. Release variant ka size thoda kam hota hai sirf lsparanoid optimization se.

---

## 7. Build Commands

```bash
# MundoCore release AAR build
cd /path/to/MundoCore
./gradlew :MundoCore:assembleRelease
# Output: MundoCore/build/outputs/aar/MundoCore-release.aar

# Host app release APK build (after AAR copy in libs/)
cd /path/to/ark
./gradlew :app:assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
# (FrozenFire DEX encryption aur signing ho to: app-release_protected.apk)
```

---

## 8. Device Test Result — v6 Release AAR ✓ WORKING

**APK:** `ark-v6-release-aar.apk` (7.6MB)
**Device:** 143373155G110552
**Status:** ✅ **Google Sign-In WORKING**

---

### Log Analysis

#### Startup — Main Process (PID 5712, `com.bgmi`)

```
doAttachBaseContext process=com.bgmi type=Main isBlack=false isServer=false
HookManager: registered 0 injectors       ← main process — no hooks needed, correct
MundoCore.doCreate completed OK
```

Main process starts clean, 0 injectors registered (normal — hooks only run in cloned app process).

---

#### BlackBox Client Process (PID 8032, `com.bgmi:p0`) — Cloned BGMI

```
doAttachBaseContext process=com.bgmi:p0 type=BAppClient isBlack=true isServer=false
HookManager: registered 50 injectors, calling injectAll   ← 50 GMS/system hooks installed ✓
MundoCore.doCreate completed OK
```

50 injectors registered — full hook set for intercepting GMS calls inside cloned BGMI.

---

#### GMS Service Bind Activity

| Action | Intercepted? | Result | Notes |
|---|---|---|---|
| `c2dm.intent.REGISTER` | NO (`intercept=false`) | Direct pass | FCM/C2DM — not a broker service, correct |
| `gms.measurement.START` | YES | `bindRealGms result=1` → GmsBrokerProxy wrapped | Analytics — real GMS via broker |
| `gms.ads.identifier.service.START` | YES | `wrap: skip (descriptor mismatch)` → `wrapped=false` | AdvertisingId uses plain AIDL, not broker pattern — skip is correct |
| `gms.auth.api.signin.service.START` | YES | **Direct FakeBroker (skip bindRealGms)** | Sign-In — handled by FakeGmsServiceBroker ✓ |
| `safebrowsing.SafeBrowsingService.START` | YES | `bindRealGms result=null` → FakeGmsServiceBroker fallback | Real GMS doesn't have this service, fallback correct |
| `common.telemetry.service.START` | YES | `bindRealGms result=null` → FakeGmsServiceBroker | Telemetry fallback, `getService id=270` handled ✓ |

---

#### Google Sign-In Flow (22:24:58) — KEY LINES

```
BindService.hook: action=com.google.android.gms.auth.api.signin.service.START
shouldInterceptBind=true
BindService: SIGN-IN service -> direct FakeBroker (skip bindRealGms)        ← step 1 ✓
BindService: delivering FakeGmsServiceBroker for action=...signin.service.START
GMS bind intercepted -> FakeGmsServiceBroker OK                              ← step 2 ✓

getService id=91 pkg=com.pubg.imobile                                        ← step 3 ✓

com.pubg.imobile: silentSignIn -> status 4                                   ← step 4 ✓
```

`status 4` = `SIGN_IN_REQUIRED` — user has no saved account, needs UI. This is correct expected behavior (not an error).

```
StartActivity.hook: action=com.google.android.gms.auth.GOOGLE_SIGN_IN
  pkg=com.pubg.imobile comp=SignInHubActivity → shouldInterceptSignInUi=false   ← game's own hub, skip
  
StartActivity.hook: action=com.google.android.gms.auth.GOOGLE_SIGN_IN
  pkg=com.google.android.gms comp=null → shouldInterceptSignInUi=true           ← INTERCEPT ✓

sign-in UI for com.pubg.imobile -> GoogleLoginActivity
  SignInOptions{
    scopes=[openid, profile],
    account=null,
    idToken=true,
    serverAuthCode=true,
    serverClientId=646362697320-ng2m2uj8a53arsiqgk7btc3bq9ia3rep.apps.googleusercontent.com
  }                                                                              ← step 5 ✓✓✓
```

**GoogleLoginActivity launched** with correct OAuth scopes and serverClientId. Full sign-in chain working end-to-end.

---

#### Second Process (PID 10124, `com.bgmi:p1`) — WebView Process

Starts at 22:31:11 (when Google login WebView opens). Clean:
- Chromium WebView sandboxed processes bind correctly
- `safetynet`, `measurement`, `usagereporting` → real GMS via GmsBrokerProxy
- `safebrowsing`, `telemetry` → FakeGmsServiceBroker fallback (real GMS doesn't expose these)
- `getService id=354` and `getService id=270` handled ✓

No errors, no crashes in entire log.

---

### Summary — Why It Works

| Component | Status | Reason |
|---|---|---|
| `FakeGmsServiceBroker` | ✓ Alive | `-keep class com.mundo.core.gms.**` protects it from R8 |
| `GmsSignInInterceptor` | ✓ Alive | Same keep rule |
| `ServiceConnectionDelegate` | ✓ Alive | Explicit keep rule in both MundoCore + host app proguard |
| `GmsBrokerProxy` | ✓ Working | Direct FakeBroker path for sign-in (v6 fix) |
| `GoogleLoginActivity` | ✓ Launched | `-keep class com.mundo.proxy.gms.**` protects it |
| Logs visible | ✓ | `Log.w`/`Log.e` NOT in `-assumenosideeffects` (v3 fix) |

**Conclusion:** v6 release AAR ProGuard rules are correct. Release build behaves identically to debug build for Google Sign-In. The `com.mundo.core.gms.**` keep rule that was missing in the original v6 source was the critical fix.
