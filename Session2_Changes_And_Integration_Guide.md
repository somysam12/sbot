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

*Is session ki deliverable: `ark-v6-release-aar.apk` (7.6MB) — test ke liye Google Sign-In check karo.*
