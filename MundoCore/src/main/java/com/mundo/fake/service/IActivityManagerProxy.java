package com.mundo.fake.service;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Application;
import android.app.IServiceConnection;
import android.app.Notification;
import android.content.ComponentName;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

import black.android.app.BRActivityManagerNative;
import black.android.app.BRActivityManagerOreo;
import black.android.app.BRLoadedApkReceiverDispatcher;
import black.android.app.BRLoadedApkReceiverDispatcherInnerReceiver;
import black.android.app.BRLoadedApkServiceDispatcher;
import black.android.app.BRLoadedApkServiceDispatcherInnerConnection;
import black.android.content.BRContentProviderNative;
import black.android.content.pm.BRUserInfo;
import black.android.util.BRSingleton;
import com.mundo.MundoCore;
import com.mundo.app.BActivityThread;
import com.mundo.core.env.AppSystemEnv;
import com.mundo.core.system.DaemonService;
import com.mundo.core.system.user.BUserHandle;
import com.mundo.entity.AppConfig;
import com.mundo.entity.am.RunningAppProcessInfo;
import com.mundo.entity.am.RunningServiceInfo;
import com.mundo.fake.delegate.ContentProviderDelegate;
import com.mundo.fake.delegate.InnerReceiverDelegate;
import com.mundo.fake.delegate.ServiceConnectionDelegate;
import com.mundo.fake.frameworks.BActivityManager;
import com.mundo.fake.frameworks.BPackageManager;
import com.mundo.fake.hook.ClassInvocationStub;
import com.mundo.fake.hook.MethodHook;
import com.mundo.fake.hook.ProxyMethod;
import com.mundo.fake.hook.ScanClass;
import com.mundo.fake.service.base.PkgMethodProxy;
import com.mundo.fake.service.context.providers.ContentProviderStub;
import com.mundo.fake.service.context.providers.SystemProviderStub;
import com.mundo.proxy.ProxyManifest;
import com.mundo.proxy.gms.GmsSignInInterceptor;
import com.mundo.proxy.record.ProxyBroadcastRecord;
import com.mundo.proxy.record.ProxyPendingRecord;
import com.mundo.utils.ArrayUtils;
import com.mundo.utils.ComponentUtils;
import com.mundo.utils.FileUtils;
import com.mundo.utils.MethodParameterUtils;
import com.mundo.utils.Reflector;
import com.mundo.utils.Slog;
import com.mundo.utils.compat.ActivityManagerCompat;
import com.mundo.utils.compat.BuildCompat;
import com.mundo.utils.compat.ParceledListSliceCompat;
import com.mundo.utils.compat.TaskDescriptionCompat;

import static android.content.Context.RECEIVER_EXPORTED;
import static android.content.Context.RECEIVER_NOT_EXPORTED;
import static android.content.pm.PackageManager.GET_META_DATA;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import org.lsposed.lsparanoid.Obfuscate;
/**
 * Created by @jagdish_vip on 3/30/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
@Obfuscate
@ScanClass(ActivityManagerCommonProxy.class)
public class IActivityManagerProxy extends ClassInvocationStub {
    public static final String TAG = "ActivityManagerStub";

    /** Host app registers this to wrap the Settings IContentProvider at acquisition time. */
    public interface SettingsProviderWrapper {
        Object wrap(Object originalProvider);
    }

    /**
     * Set BEFORE MundoCore.doAttachBaseContext().
     * ProGuard renames this field on release builds — host app must search by TYPE, not name.
     */
    public static volatile SettingsProviderWrapper sSettingsProviderWrapper = null;

    @Override
    protected Object getWho() {
        Object iActivityManager = null;
        if (BuildCompat.isOreo()) {
            iActivityManager = BRActivityManagerOreo.get().IActivityManagerSingleton();
        } else if (BuildCompat.isL()) {
            iActivityManager = BRActivityManagerNative.get().gDefault();
        }
        return BRSingleton.get(iActivityManager).get();
    }

    @Override
    protected void inject(Object base, Object proxy) {
        Object iActivityManager = null;
        if (BuildCompat.isOreo()) {
            iActivityManager = BRActivityManagerOreo.get().IActivityManagerSingleton();
        } else if (BuildCompat.isL()) {
            iActivityManager = BRActivityManagerNative.get().gDefault();
        }
        BRSingleton.get(iActivityManager)._set_mInstance(proxy);
    }

    @Override
    public boolean isBadEnv() {
        return getProxyInvocation() != getWho();
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new PkgMethodProxy("getAppStartMode"));
        addMethodHook(new PkgMethodProxy("setAppLockedVerifying"));
        addMethodHook(new PkgMethodProxy("reportJunkFromApp"));
    }

    @ProxyMethod("getContentProvider")
    public static class GetContentProvider extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Exception {
            int authIndex = getAuthIndex();
            Object auth = args[authIndex];
            Object content = null;

            if (auth instanceof String) {
                if (ProxyManifest.isProxy((String) auth)) {
                    return method.invoke(who, args);
                }

                if (BuildCompat.isQ()) {
                    args[1] = MundoCore.getHostPkg();
                }

                if (auth.equals("settings") || auth.equals("media") || auth.equals("telephony")) {
                    content = method.invoke(who, args);
                    ContentProviderDelegate.update(content, (String) auth);
                    if ("settings".equals(auth)) {
                        applySettingsWrapper(content);
                    }
                    return content;
                } else {
                    Log.d(TAG, "hook getContentProvider: " + auth);

                    ProviderInfo providerInfo = MundoCore.getBPackageManager().resolveContentProvider((String) auth, FileUtils.FileMode.MODE_IWUSR, BActivityThread.getUserId());
                    if (providerInfo == null) {
                        return null;
                    }

                    IBinder providerBinder = null;
                    if (BActivityThread.getAppPid() != -1) {
                        AppConfig appConfig = MundoCore.getBActivityManager().initProcess(providerInfo.packageName, providerInfo.processName, BActivityThread.getUserId());
                        if (appConfig.bpid != BActivityThread.getAppPid()) {
                            providerBinder = MundoCore.getBActivityManager().acquireContentProviderClient(providerInfo);
                        }
                        args[authIndex] = ProxyManifest.getProxyAuthorities(appConfig.bpid);
                        args[getUserIndex()] = MundoCore.getHostUserId();
                    }
                    if (providerBinder == null)
                        return null;

                    content = method.invoke(who, args);
                    Reflector.with(content).field("info").set(providerInfo);
                    Reflector.with(content).field("provider").set(new ContentProviderStub().wrapper(BRContentProviderNative.get().asInterface(providerBinder), BActivityThread.getAppPackageName()));
                }

                return content;
            }
            return method.invoke(who, args);
        }

        private int getAuthIndex() {
            // 10.0
            if (BuildCompat.isQ()) {
                return 2;
            } else {
                return 1;
            }
        }

        private int getUserIndex() {
            return getAuthIndex() + 1;
        }
    }
    
    @ProxyMethod("startService")
    public static class StartService extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[1];
            String resolvedType = (String) args[2];
            ResolveInfo resolveInfo = MundoCore.getBPackageManager().resolveService(intent, 0, resolvedType, BActivityThread.getUserId());
            if (resolveInfo == null) {
                return method.invoke(who, args);
            }

            int requireForegroundIndex = getRequireForeground();
            boolean requireForeground = false;
            if (requireForegroundIndex != -1) {
                requireForeground = (boolean) args[requireForegroundIndex];
            }
            return MundoCore.getBActivityManager().startService(intent, resolvedType, requireForeground, BActivityThread.getUserId());
        }

        public int getRequireForeground() {
            if (BuildCompat.isOreo()) {
                return 3;
            }
            return -1;
        }
    }

    @ProxyMethod("stopService")
    public static class StopService extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[1];
            String resolvedType = (String) args[2];
            return MundoCore.getBActivityManager().stopService(intent, resolvedType, BActivityThread.getUserId());
        }
    }

    @ProxyMethod("stopServiceToken")
    public static class StopServiceToken extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            IBinder token = (IBinder) args[1];
            MundoCore.getBActivityManager().stopServiceToken(componentName, token, BActivityThread.getUserId());
            return true;
        }
    }
    
    // Fixed: added null/exception guards around reflective dispatcher update (crashes on A15/16
    // when BlackReflection field names change). Core bind logic unchanged.
    @ProxyMethod("bindService")
    public static class BindService extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[2];
            String resolvedType = (String) args[3];
            IServiceConnection connection = (IServiceConnection) args[4];

            // ---- GMS Sign-In Service Intercept ----
            if (GmsSignInInterceptor.shouldInterceptBind(intent) && connection != null) {
                try {
                    android.content.Context ctx = BActivityThread.getApplication();
                    IBinder fakeBroker = GmsSignInInterceptor.getFakeBroker(ctx);
                    // Deliver the fake broker via IServiceConnection.onServiceConnected()
                    android.content.ComponentName gmsComp = new android.content.ComponentName(
                            GmsSignInInterceptor.GMS_PKG,
                            GmsSignInInterceptor.GMS_PKG + ".chimera.GmsApiService");
                    connection.connected(gmsComp, fakeBroker);
                    Log.d("MundoCore", "GMS bind intercepted, returning FakeGmsServiceBroker");
                    return 1; // non-zero = success
                } catch (Throwable t) {
                    Log.w("MundoCore", "GMS bind intercept failed: " + t.getMessage());
                    // Fall through to normal handling
                }
            }
            // ---- End GMS Sign-In Service Intercept ----

            int userId = intent.getIntExtra("_G_|_UserId", -1);
            userId = userId == -1 ? BActivityThread.getUserId() : userId;
            ResolveInfo resolveInfo = MundoCore.getBPackageManager().resolveService(intent, 0, resolvedType, userId);
            if (resolveInfo != null || AppSystemEnv.isOpenPackage(intent.getComponent())) {
                Intent bindService = MundoCore.getBActivityManager().bindService(intent,connection == null ? null : connection.asBinder(),resolvedType,userId);
                if (connection != null) {
                    if (intent.getComponent() == null && resolveInfo != null) {
                        intent.setComponent(new ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name));
                    }
                    IServiceConnection proxy = ServiceConnectionDelegate.createProxy(connection, intent);
                    args[4] = proxy;

                    // Guard: field access via BlackReflection can throw on A15/16 if internal
                    // field names changed. Fall back gracefully — service bind still proceeds.
                    try {
                        WeakReference<?> weakReference = BRLoadedApkServiceDispatcherInnerConnection.get(connection).mDispatcher();
                        if (weakReference != null && weakReference.get() != null) {
                            BRLoadedApkServiceDispatcher.get(weakReference.get())._set_mConnection(proxy);
                        }
                    } catch (Throwable t) {
                        Log.w("MundoCore", "bindService: dispatcher update skipped: " + t.getMessage());
                    }
                }
                if (bindService != null) {
                    args[2] = bindService;
                    return method.invoke(who, args);
                }
            }
            return 0;
        }

        @Override
        protected boolean isEnable() {
            return MundoCore.get().isBlackProcess() || MundoCore.get().isServerProcess();
        }
    }

    //android 13.0变更
    @ProxyMethod("bindServiceInstance")
    public static class BindServiceInstance extends BindIsolatedService {

    }
    

    // 10.0
    @ProxyMethod("bindIsolatedService")
    public static class BindIsolatedService extends BindService {
        @Override
        protected Object beforeHook(Object who, Method method, Object[] args) throws Throwable {
            // instanceName
            args[6] = null;
            return super.beforeHook(who, method, args);
        }
    }

    @ProxyMethod("unbindService")
    public static class UnbindService extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IServiceConnection iServiceConnection = (IServiceConnection) args[0];
            if (iServiceConnection == null) {
                return method.invoke(who, args);
            }
            MundoCore.getBActivityManager().unbindService(iServiceConnection.asBinder(), BActivityThread.getUserId());
            ServiceConnectionDelegate delegate = ServiceConnectionDelegate.getDelegate(iServiceConnection.asBinder());
            if (delegate != null) {
                args[0] = delegate;
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getRunningAppProcesses")
    public static class GetRunningAppProcesses extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            RunningAppProcessInfo runningAppProcesses = BActivityManager.get().getRunningAppProcesses(BActivityThread.getAppPackageName(), BActivityThread.getUserId());
            if (runningAppProcesses == null) {
                return new ArrayList<>();
            }
            return runningAppProcesses.mAppProcessInfoList;
        }
    }

    @ProxyMethod("getServices")
    public static class GetServices extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            RunningServiceInfo runningServices = BActivityManager.get().getRunningServices(BActivityThread.getAppPackageName(), BActivityThread.getUserId());
            if (runningServices == null) {
                return new ArrayList<>();
            }
            return runningServices.mRunningServiceInfoList;
        }
    }

    @ProxyMethod("getIntentSender")
    public static class GetIntentSender extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int type = (int) args[0];
            Intent[] intents = (Intent[]) args[getIntentsIndex(args)];
            MethodParameterUtils.replaceFirstAppPkg(args);

            for (int i = 0; i < intents.length; i++) {
                Intent intent = intents[i];
                switch (type) {
                    case ActivityManagerCompat.INTENT_SENDER_ACTIVITY:
                        Intent shadow = new Intent();
                        shadow.setComponent(new ComponentName(MundoCore.getHostPkg(), ProxyManifest.getProxyPendingActivity(BActivityThread.getAppPid())));
                        ProxyPendingRecord.saveStub(shadow, intent, BActivityThread.getUserId());
                        intents[i] = shadow;
                        break;
                }
            }

            // Android 12 (API 31) compat: PendingIntent requires FLAG_IMMUTABLE or FLAG_MUTABLE
            if (BuildCompat.isS()) {
                int flagsIndex = getIntentsIndex(args) + 2;
                if (flagsIndex < args.length && args[flagsIndex] instanceof Integer) {
                    int flags = (int) args[flagsIndex];
                    if ((flags & (0x4000000 | 0x2000000)) == 0) {
                        flags |= 0x4000000; // FLAG_IMMUTABLE
                        args[flagsIndex] = flags;
                    }
                }
            }

            IInterface invoke = (IInterface) method.invoke(who, args);
            if (invoke != null) {
                String[] packagesForUid = BPackageManager.get().getPackagesForUid(BActivityThread.getCallingBUid());
                if (packagesForUid.length < 1) {
                    packagesForUid = new String[]{MundoCore.getHostPkg()};
                }
                MundoCore.getBActivityManager().getIntentSender(invoke.asBinder(), packagesForUid[0], BActivityThread.getCallingBUid());
            }
            return invoke;
        }

        private int getIntentsIndex(Object[] args) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Intent[]) {
                    return i;
                }
            }
            if (BuildCompat.isR()) {
                return 6;
            } else {
                return 5;
            }
        }
    }

    @ProxyMethod("getPackageForIntentSender")
    public static class getPackageForIntentSender extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IInterface invoke = (IInterface) args[0];
            return MundoCore.getBActivityManager().getPackageForIntentSender(invoke.asBinder());
        }
    }

    @ProxyMethod("getUidForIntentSender")
    public static class getUidForIntentSender extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IInterface invoke = (IInterface) args[0];
            return MundoCore.getBActivityManager().getUidForIntentSender(invoke.asBinder());
        }
    }

    @ProxyMethod("getIntentSenderWithSourceToken")
    public static class GetIntentSenderWithSourceToken extends GetIntentSender {
    }

    @ProxyMethod("getIntentSenderWithFeature")
    public static class GetIntentSenderWithFeature extends GetIntentSender {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("broadcastIntent")
    public static class BroadcastIntent extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int intentIndex = getIntentIndex(args);
            Intent intent = (Intent) args[intentIndex];
            String resolvedType = (String) args[intentIndex + 1];

            Intent proxyIntent = MundoCore.getBActivityManager().sendBroadcast(intent, resolvedType, BActivityThread.getUserId());
            if (proxyIntent != null) {
                proxyIntent.setExtrasClassLoader(BActivityThread.getApplication().getClassLoader());

                ProxyBroadcastRecord.saveStub(proxyIntent, intent, BActivityThread.getUserId());
                args[intentIndex] = proxyIntent;
            }
            // ignore permission
            for (int i = 0; i < args.length; i++) {
                Object o = args[i];
                if (o instanceof String[]) {
                    args[i] = null;
                }
            }
            return method.invoke(who, args);
        }

        int getIntentIndex(Object[] args) {
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg instanceof Intent) {
                    return i;
                }
            }
            return 1;
        }
    }

    @ProxyMethod("unregisterReceiver")
    public static class unregisterReceiver extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("finishReceiver")
    public static class finishReceiver extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("publishService")
    public static class PublishService extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("peekService")
    public static class PeekService extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            Intent intent = (Intent) args[0];
            String resolvedType = (String) args[1];
            return MundoCore.getBActivityManager().peekService(intent, resolvedType, BActivityThread.getUserId());
        }
    }

    // todo
    @ProxyMethod("sendIntentSender")
    public static class SendIntentSender extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    // android 10
    @ProxyMethod("registerReceiverWithFeature")
    public static class RegisterReceiverWithFeature extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            // featureId String is at index 2; IIntentReceiver is at index 3 (API 29-30) or 4 (API 31+)
            int receiverIndex = -1;
            for (int i = 3; i < args.length; i++) {
                if (args[i] instanceof IIntentReceiver) {
                    receiverIndex = i;
                    break;
                }
            }
            if (receiverIndex != -1) {
                IIntentReceiver intentReceiver = (IIntentReceiver) args[receiverIndex];
                IIntentReceiver proxy = InnerReceiverDelegate.createProxy(intentReceiver);
                WeakReference<?> weakReference = BRLoadedApkReceiverDispatcherInnerReceiver.get(intentReceiver).mDispatcher();
                if (weakReference != null) {
                    BRLoadedApkReceiverDispatcher.get(weakReference.get())._set_mIIntentReceiver(proxy);
                }
                args[receiverIndex] = proxy;
                // permission String is 2 positions after IIntentReceiver (after IntentFilter)
                int permissionIndex = receiverIndex + 2;
                if (permissionIndex < args.length && args[permissionIndex] instanceof String) {
                    args[permissionIndex] = null;
                }
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("registerReceiver")
    public static class RegisterReceiver extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            int receiverIndex = 2;
            if (args[receiverIndex] != null) {
                IIntentReceiver intentReceiver = (IIntentReceiver) args[receiverIndex];
                IIntentReceiver proxy = InnerReceiverDelegate.createProxy(intentReceiver);

                WeakReference<?> weakReference = BRLoadedApkReceiverDispatcherInnerReceiver.get(intentReceiver).mDispatcher();
                if (weakReference != null) {
                    BRLoadedApkReceiverDispatcher.get(weakReference.get())._set_mIIntentReceiver(proxy);
                }

                args[receiverIndex] = proxy;
            }
            int permissionIndex = 4;
            // ignore permission
            if (args[permissionIndex] != null) {
                args[permissionIndex] = null;
            }
            return method.invoke(who, args);
        }
    }

    //这里需要修复
    @ProxyMethod("grantUriPermission")
    public static class GrantUriPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastUid(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setServiceForeground")
    public static class setServiceForeground extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    @ProxyMethod("getHistoricalProcessExitReasons")
    public static class getHistoricalProcessExitReasons extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return ParceledListSliceCompat.create(new ArrayList<>());
        }
    }

    @ProxyMethod("getCurrentUser")
    public static class getCurrentUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BRUserInfo.get()._new(BActivityThread.getUserId(), "EliteCore", BRUserInfo.get().FLAG_PRIMARY());
        }
    }

    @ProxyMethod("checkPermission")
    public static class checkPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastUid(args);
            String permission = (String) args[0];
            if (permission.equals(Manifest.permission.ACCOUNT_MANAGER) || permission.equals(Manifest.permission.SEND_SMS)) {
                return PackageManager.PERMISSION_GRANTED;
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("checkUriPermission")
    public static class checkUriPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return PackageManager.PERMISSION_GRANTED;
        }
    }

    // for < Android 10
    @ProxyMethod("setTaskDescription")
    public static class SetTaskDescription extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ActivityManager.TaskDescription td = (ActivityManager.TaskDescription) args[1];
            args[1] = TaskDescriptionCompat.fix(td);
            return method.invoke(who, args);
        }
    }
    
    @ProxyMethod("setRequestedOrientation")
    public static class setRequestedOrientation extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(who, args);
            } catch (Throwable e) {
                e.printStackTrace();
            }
            return 0;
        }
    }

    @ProxyMethod("registerUidObserver")
    public static class registerUidObserver extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    @ProxyMethod("unregisterUidObserver")
    public static class unregisterUidObserver extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    @ProxyMethod("updateConfiguration")
    public static class updateConfiguration extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    /**
     * Wraps the Settings IContentProvider in the host-registered SettingsProviderWrapper.
     * No Proxy.isProxyClass() guard — ContentProviderDelegate.update() already wraps in
     * SystemProviderStub (a Proxy); a guard would prevent our wrap from installing.
     */
    static void applySettingsWrapper(Object contentHolder) {
        SettingsProviderWrapper wrapper = sSettingsProviderWrapper;
        android.util.Log.d(TAG, "applySettingsWrapper called: wrapper=" + (wrapper == null ? "NULL" : "SET")
                + " holder=" + (contentHolder == null ? "NULL" : contentHolder.getClass().getSimpleName()));
        if (wrapper == null || contentHolder == null) return;
        try {
            Object prov = Reflector.with(contentHolder).field("provider").get();
            android.util.Log.d(TAG, "applySettingsWrapper: prov=" + (prov == null ? "NULL" : prov.getClass().getName()));
            if (prov != null) {
                Object wrapped = wrapper.wrap(prov);
                android.util.Log.d(TAG, "applySettingsWrapper: wrapped=" + (wrapped == null ? "NULL" : wrapped.getClass().getName()));
                if (wrapped != null) {
                    Reflector.with(contentHolder).field("provider").set(wrapped);
                    android.util.Log.d(TAG, "applySettingsWrapper: provider field SET OK");
                }
            }
        } catch (Throwable e) {
            android.util.Log.e(TAG, "applySettingsWrapper FAILED: " + e);
        }
    }

}
