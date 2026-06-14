package com.github.tvbox.osc.base;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.VersionedPackage;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;

import androidx.multidex.MultiDexApplication;
import androidx.work.Configuration;

import com.github.catvod.crawler.JsLoader;
import com.github.catvod.Init;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.EpgUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.orhanobut.hawk.Hawk;
import com.p2p.P2PClass;
import com.whl.quickjs.android.QuickJSLoader;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.hilt.work.HiltWorkerFactory;
import dagger.hilt.android.HiltAndroidApp;
import dagger.hilt.EntryPoint;
import dagger.hilt.InstallIn;
import dagger.hilt.android.EntryPointAccessors;
import dagger.hilt.components.SingletonComponent;

@HiltAndroidApp
public class App extends MultiDexApplication implements Configuration.Provider {
    public static final String HOST_PACKAGE_NAME = "com.github.tvbox.osc";
    private static final String HOST_APPLICATION_CLASS_NAME = "com.github.tvbox.osc.base.App";
    private static final String CHROME_PACKAGE_NAME = "com.android.chrome";
    private static final String SYSTEM_SETTINGS_PACKAGE_NAME = "com.android.settings";
    private static final String YOUTUBE_FOR_TV_PACKAGE_NAME = "com.google.android.youtube.tv";

    private static App instance = new App();
    private static volatile Context appContext;
    private static volatile Context bridgeContext;
    private static volatile ApplicationInfo bridgedApplicationInfo;
    private static volatile PackageManager bridgedPackageManager;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile boolean attached = false;
    private static volatile boolean hostRuntimeBootstrapped = false;
    private static volatile boolean webViewPackageSpoofLogged = false;
    private static volatile P2PClass p;
    private volatile Configuration workManagerConfiguration;
    public static String burl;
    private static String dashData;
    private volatile VodInfo vodInfo;

    public App() {
        instance = this;
    }

    public static synchronized void init(Context context) {
        if (context != null) {
            Context resolvedApplication = context.getApplicationContext();
            App resolvedApp = null;
            Context initContext = null;
            if (context instanceof App) {
                resolvedApp = (App) context;
            } else if (resolvedApplication instanceof App) {
                resolvedApp = (App) resolvedApplication;
            }
            if (resolvedApp != null) {
                instance = resolvedApp;
                appContext = resolvedApp;
                bridgeContext = resolvedApp.peekBridgeContext();
                initContext = bridgeContext != null ? bridgeContext : resolvedApp;
                attached = true;
            } else {
                Context runtimeContext = resolvedApplication != null ? resolvedApplication : context;
                bridgeContext = runtimeContext;
                initContext = runtimeContext;
                if (instance != null && instance.getClass() != App.class) {
                    appContext = instance;
                    attached = true;
                } else {
                    appContext = runtimeContext;
                    if (!attached && instance != null) {
                        instance.attach(runtimeContext);
                        attached = true;
                    }
                }
            }
            resetBridgeCaches();
            Init.set(initContext != null ? initContext : getInstance());
            bootstrapHostRuntime();
        }
    }

    public static App getInstance() {
        return instance;
    }

    @Override
    public Configuration getWorkManagerConfiguration() {
        Configuration cached = workManagerConfiguration;
        if (cached != null) {
            return cached;
        }
        HiltWorkerFactory workerFactory =
                EntryPointAccessors.fromApplication(this, WorkManagerEntryPoint.class).workerFactory();
        Configuration created = new Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .build();
        workManagerConfiguration = created;
        return created;
    }

    private static void resetBridgeCaches() {
        bridgedApplicationInfo = null;
        bridgedPackageManager = null;
    }

    public static synchronized void bootstrapHostRuntime() {
        if (hostRuntimeBootstrapped || appContext == null) {
            return;
        }
        hostRuntimeBootstrapped = true;
        try {
            Hawk.init(getInstance()).build();
            Hawk.put(HawkConfig.DEBUG_OPEN, false);
            if (!Hawk.contains(HawkConfig.PLAY_TYPE)) {
                Hawk.put(HawkConfig.PLAY_TYPE, 1);
            }
        } catch (Throwable error) {
            LOG.e("Hawk bootstrap failed: " + error);
        }
        try {
            OkGoHelper.init();
        } catch (Throwable error) {
            LOG.e("OkGo bootstrap failed: " + error);
        }
        try {
            EpgUtil.init();
        } catch (Throwable error) {
            LOG.e("EPG bootstrap failed: " + error);
        }
        try {
            ControlManager.init(getInstance());
        } catch (Throwable error) {
            LOG.e("ControlManager bootstrap failed: " + error);
        }
        try {
            AppDataManager.init();
        } catch (Throwable error) {
            LOG.e("AppDataManager bootstrap failed: " + error);
        }
        try {
            PlayerHelper.init();
        } catch (Throwable error) {
            LOG.e("PlayerHelper bootstrap failed: " + error);
        }
        try {
            QuickJSLoader.init();
        } catch (Throwable error) {
            LOG.e("QuickJS bootstrap failed: " + error);
        }
        try {
            FileUtils.cleanPlayerCache();
        } catch (Throwable error) {
            LOG.e("FileUtils bootstrap failed: " + error);
        }
    }

    public static void post(Runnable runnable) {
        if (runnable != null) {
            MAIN_HANDLER.post(runnable);
        }
    }

    public static void post(Runnable runnable, long delayMillis) {
        if (runnable == null) {
            return;
        }
        MAIN_HANDLER.removeCallbacks(runnable);
        if (delayMillis >= 0) {
            MAIN_HANDLER.postDelayed(runnable, delayMillis);
        }
    }

    private Context requireContext() {
        Context context = peekBridgeContext();
        if (context == null) {
            throw new IllegalStateException("TVBox App bridge is not initialized");
        }
        return context;
    }

    private boolean shouldBridgeHostIdentity() {
        return false;
    }

    private boolean hasRealHostIdentity() {
        return HOST_PACKAGE_NAME.equals(super.getPackageName());
    }

    private Context peekBridgeContext() {
        Context context = bridgeContext;
        if (context != null && context != this) {
            return context;
        }
        Context application = appContext;
        if (application != null && application != this) {
            return application;
        }
        Context base = getBaseContext();
        if (base != null && base != this) {
            return base;
        }
        return null;
    }

    @Override
    protected void attachBaseContext(Context base) {
        if (base != null) {
            instance = this;
            appContext = this;
            bridgeContext = base;
            resetBridgeCaches();
            attached = true;
            Init.set(base);
        }
        super.attachBaseContext(base);
    }

    private void attach(Context context) {
        super.attachBaseContext(context);
    }

    @Override
    public Context getApplicationContext() {
        return appContext != null ? appContext : this;
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        if (!shouldBridgeHostIdentity() || hasRealHostIdentity()) {
            return super.getApplicationInfo();
        }
        ApplicationInfo cached = bridgedApplicationInfo;
        if (cached != null) {
            return cached;
        }
        ApplicationInfo realInfo = requireContext().getApplicationInfo();
        ApplicationInfo bridgedInfo = new ApplicationInfo(realInfo);
        bridgedInfo.packageName = HOST_PACKAGE_NAME;
        bridgedInfo.processName = buildHostProcessName(realInfo.processName);
        bridgedInfo.className = HOST_APPLICATION_CLASS_NAME;
        bridgedApplicationInfo = bridgedInfo;
        return bridgedInfo;
    }

    @Override
    public AssetManager getAssets() {
        return requireContext().getAssets();
    }

    @Override
    public File getCacheDir() {
        return requireContext().getCacheDir();
    }

    @Override
    public File getCodeCacheDir() {
        return requireContext().getCodeCacheDir();
    }

    @Override
    public File getDir(String name, int mode) {
        return requireContext().getDir(name, mode);
    }

    @Override
    public File getDatabasePath(String name) {
        return requireContext().getDatabasePath(name);
    }

    @Override
    public File getFilesDir() {
        return requireContext().getFilesDir();
    }

    @Override
    public File getExternalCacheDir() {
        return requireContext().getExternalCacheDir();
    }

    @Override
    public ClassLoader getClassLoader() {
        if (!shouldBridgeHostIdentity() || hasRealHostIdentity()) {
            return super.getClassLoader();
        }
        return requireContext().getClassLoader();
    }

    @Override
    public PackageManager getPackageManager() {
        if (!shouldBridgeHostIdentity() || hasRealHostIdentity()) {
            return super.getPackageManager();
        }
        PackageManager cached = bridgedPackageManager;
        if (cached != null) {
            return cached;
        }
        PackageManager bridged = new BridgedPackageManager(this, requireContext());
        bridgedPackageManager = bridged;
        return bridged;
    }

    @Override
    public Context createPackageContext(String packageName, int flags) throws PackageManager.NameNotFoundException {
        if (!shouldBridgeHostIdentity()) {
            return super.createPackageContext(packageName, flags);
        }
        if (HOST_PACKAGE_NAME.equals(packageName)) {
            return this;
        }
        return requireContext().createPackageContext(packageName, flags);
    }

    @Override
    public String getPackageCodePath() {
        return requireContext().getPackageCodePath();
    }

    @Override
    public Resources getResources() {
        return requireContext().getResources();
    }

    @Override
    public String getPackageResourcePath() {
        return requireContext().getPackageResourcePath();
    }

    @Override
    public String getPackageName() {
        try {
            if (isChromiumPackageNameRequest()) {
                String packageName = spoofedWebViewPackageName();
                LOG.i("MihonNetwork", "WebView package spoof: requestedBy=Chromium, packageName=" + packageName);
                return packageName;
            }
        } catch (Exception ignored) {
        }
        return super.getPackageName();
    }

    private boolean isChromiumPackageNameRequest() {
        for (StackTraceElement trace : Thread.currentThread().getStackTrace()) {
            String className = trace.getClassName().toLowerCase();
            String methodName = trace.getMethodName().toLowerCase();
            boolean chromiumClass = className.equals("org.chromium.base.buildinfo")
                    || className.equals("org.chromium.base.apkinfo");
            boolean packageMethod = methodName.equals("getall")
                    || methodName.equals("getpackagename")
                    || methodName.equals("<init>");
            if (chromiumClass && packageMethod) {
                if (!webViewPackageSpoofLogged) {
                    webViewPackageSpoofLogged = true;
                    LOG.i(
                            "MihonNetwork",
                            "WebView package spoof trigger: class=" + trace.getClassName() + ", method=" + trace.getMethodName()
                    );
                }
                return true;
            }
        }
        return false;
    }

    private String spoofedWebViewPackageName() {
        PackageManager packageManager = getPackageManager();
        String packageName = firstInstalledPackageName(
                packageManager,
                CHROME_PACKAGE_NAME,
                SYSTEM_SETTINGS_PACKAGE_NAME,
                YOUTUBE_FOR_TV_PACKAGE_NAME
        );
        if (packageName != null) {
            return packageName;
        }
        List<PackageInfo> packages = packageManager.getInstalledPackages(0);
        return packages.isEmpty() ? super.getPackageName() : packages.get(0).packageName;
    }

    private static String firstInstalledPackageName(PackageManager packageManager, String... packageNames) {
        for (String packageName : packageNames) {
            try {
                return packageManager.getPackageInfo(packageName, 0).packageName;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }

    public static P2PClass getp2p() {
        try {
            if (p == null) {
                p = new P2PClass(FileUtils.getExternalCachePath());
            }
            return p;
        } catch (Exception e) {
            LOG.e(e.toString());
            return null;
        }
    }

    public Activity getCurrentActivity() {
        return AppManager.getInstance().currentActivity();
    }

    public void setDashData(String data) {
        dashData = data;
    }

    public String getDashData() {
        return dashData;
    }

    public void setVodInfo(VodInfo vodInfo) {
        this.vodInfo = vodInfo;
    }

    public VodInfo getVodInfo() {
        return vodInfo;
    }

    private String buildHostProcessName(String realProcessName) {
        if (realProcessName == null || realProcessName.isEmpty()) {
            return HOST_PACKAGE_NAME;
        }
        int suffixIndex = realProcessName.indexOf(':');
        if (suffixIndex >= 0) {
            return HOST_PACKAGE_NAME + realProcessName.substring(suffixIndex);
        }
        return HOST_PACKAGE_NAME;
    }

    private boolean isHostPackageName(String packageName) {
        return HOST_PACKAGE_NAME.equals(packageName);
    }

    private String getRealPackageName() {
        return requireContext().getPackageName();
    }

    private String mapToRealPackageName(String packageName) {
        if (isHostPackageName(packageName)) {
            return getRealPackageName();
        }
        return packageName;
    }

    private String bridgeReportedPackageName(String packageName) {
        if (getRealPackageName().equals(packageName)) {
            return HOST_PACKAGE_NAME;
        }
        return packageName;
    }

    private ApplicationInfo bridgeApplicationInfoIfNeeded(ApplicationInfo info) {
        if (info == null || !getRealPackageName().equals(info.packageName)) {
            return info;
        }
        ApplicationInfo bridgedInfo = new ApplicationInfo(info);
        bridgedInfo.packageName = HOST_PACKAGE_NAME;
        bridgedInfo.processName = buildHostProcessName(info.processName);
        bridgedInfo.className = HOST_APPLICATION_CLASS_NAME;
        return bridgedInfo;
    }

    private PackageInfo bridgePackageInfoIfNeeded(PackageInfo info) {
        if (info == null || !getRealPackageName().equals(info.packageName)) {
            return info;
        }
        PackageInfo bridgedInfo = shallowCopyPackageInfo(info);
        bridgedInfo.packageName = HOST_PACKAGE_NAME;
        bridgedInfo.applicationInfo = bridgeApplicationInfoIfNeeded(info.applicationInfo);
        return bridgedInfo;
    }

    private PackageInfo shallowCopyPackageInfo(PackageInfo source) {
        PackageInfo copy = new PackageInfo();
        Field[] fields = PackageInfo.class.getFields();
        for (Field field : fields) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                continue;
            }
            try {
                field.set(copy, field.get(source));
            } catch (IllegalAccessException ignored) {
            }
        }
        return copy;
    }

    @EntryPoint
    @InstallIn(SingletonComponent.class)
    interface WorkManagerEntryPoint {
        HiltWorkerFactory workerFactory();
    }

    private static final class BridgedPackageManager extends PackageManager {
        private final App owner;
        private final Context realContext;
        private final PackageManager base;

        private BridgedPackageManager(App owner, Context realContext) {
            this.owner = owner;
            this.realContext = realContext;
            this.base = realContext.getPackageManager();
        }

        private String mapPackageName(String packageName) {
            return owner.mapToRealPackageName(packageName);
        }

        private String bridgePackageName(String packageName) {
            return owner.bridgeReportedPackageName(packageName);
        }

        private ComponentName mapComponentName(ComponentName componentName) {
            if (componentName == null) {
                return null;
            }
            String packageName = componentName.getPackageName();
            if (!owner.isHostPackageName(packageName)) {
                return componentName;
            }
            return new ComponentName(owner.getRealPackageName(), componentName.getClassName());
        }

        private ApplicationInfo bridgeApplicationInfo(ApplicationInfo info) {
            return owner.bridgeApplicationInfoIfNeeded(info);
        }

        private PackageInfo bridgePackageInfo(PackageInfo info) {
            return owner.bridgePackageInfoIfNeeded(info);
        }

        private ApplicationInfo mapApplicationInfo(ApplicationInfo info) {
            if (info == null) {
                return null;
            }
            if (owner.isHostPackageName(info.packageName)) {
                return bridgeApplicationInfo(realContext.getApplicationInfo());
            }
            return info;
        }

        private List<ApplicationInfo> bridgeInstalledApplications(List<ApplicationInfo> infos) {
            List<ApplicationInfo> bridged = new ArrayList<>(infos.size());
            for (ApplicationInfo info : infos) {
                bridged.add(bridgeApplicationInfo(info));
            }
            return bridged;
        }

        private List<PackageInfo> bridgeInstalledPackages(List<PackageInfo> infos) {
            List<PackageInfo> bridged = new ArrayList<>(infos.size());
            for (PackageInfo info : infos) {
                bridged.add(bridgePackageInfo(info));
            }
            return bridged;
        }

        private String[] bridgePackagesForUid(String[] packages) {
            if (packages == null || packages.length == 0) {
                return packages;
            }
            String[] bridged = Arrays.copyOf(packages, packages.length);
            for (int index = 0; index < bridged.length; index++) {
                bridged[index] = bridgePackageName(bridged[index]);
            }
            return bridged;
        }

        @Override
        public void addPackageToPreferred(String packageName) {
            base.addPackageToPreferred(mapPackageName(packageName));
        }

        @Override
        public boolean addPermission(PermissionInfo info) {
            return base.addPermission(info);
        }

        @Override
        public boolean addPermissionAsync(PermissionInfo info) {
            return base.addPermissionAsync(info);
        }

        @Override
        public void addPreferredActivity(IntentFilter filter, int match, ComponentName[] set, ComponentName activity) {
            ComponentName[] mappedSet = set;
            if (set != null && set.length > 0) {
                mappedSet = Arrays.copyOf(set, set.length);
                for (int index = 0; index < mappedSet.length; index++) {
                    mappedSet[index] = mapComponentName(mappedSet[index]);
                }
            }
            base.addPreferredActivity(filter, match, mappedSet, mapComponentName(activity));
        }

        @Override
        public boolean canRequestPackageInstalls() {
            return base.canRequestPackageInstalls();
        }

        @Override
        public String[] canonicalToCurrentPackageNames(String[] names) {
            return base.canonicalToCurrentPackageNames(names);
        }

        @Override
        public int checkPermission(String permName, String packageName) {
            return base.checkPermission(permName, mapPackageName(packageName));
        }

        @Override
        public int checkSignatures(int uid1, int uid2) {
            return base.checkSignatures(uid1, uid2);
        }

        @Override
        public int checkSignatures(String pkg1, String pkg2) {
            return base.checkSignatures(mapPackageName(pkg1), mapPackageName(pkg2));
        }

        @Override
        public void clearInstantAppCookie() {
            base.clearInstantAppCookie();
        }

        @Override
        public void clearPackagePreferredActivities(String packageName) {
            base.clearPackagePreferredActivities(mapPackageName(packageName));
        }

        @Override
        public String[] currentToCanonicalPackageNames(String[] names) {
            return base.currentToCanonicalPackageNames(names);
        }

        @Override
        public void extendVerificationTimeout(int id, int verificationCodeAtTimeout, long millisecondsToDelay) {
            base.extendVerificationTimeout(id, verificationCodeAtTimeout, millisecondsToDelay);
        }

        @Override
        public Drawable getActivityBanner(ComponentName activityName) throws NameNotFoundException {
            return base.getActivityBanner(mapComponentName(activityName));
        }

        @Override
        public Drawable getActivityBanner(Intent intent) throws NameNotFoundException {
            return base.getActivityBanner(intent);
        }

        @Override
        public Drawable getActivityIcon(ComponentName activityName) throws NameNotFoundException {
            return base.getActivityIcon(mapComponentName(activityName));
        }

        @Override
        public Drawable getActivityIcon(Intent intent) throws NameNotFoundException {
            return base.getActivityIcon(intent);
        }

        @Override
        public ActivityInfo getActivityInfo(ComponentName component, int flags) throws NameNotFoundException {
            return base.getActivityInfo(mapComponentName(component), flags);
        }

        @Override
        public ActivityInfo getActivityInfo(ComponentName component, PackageManager.ComponentInfoFlags flags) throws NameNotFoundException {
            return base.getActivityInfo(mapComponentName(component), flags);
        }

        @Override
        public Drawable getActivityLogo(ComponentName activityName) throws NameNotFoundException {
            return base.getActivityLogo(mapComponentName(activityName));
        }

        @Override
        public Drawable getActivityLogo(Intent intent) throws NameNotFoundException {
            return base.getActivityLogo(intent);
        }

        @Override
        public List<PermissionGroupInfo> getAllPermissionGroups(int flags) {
            return base.getAllPermissionGroups(flags);
        }

        @Override
        public Drawable getApplicationBanner(ApplicationInfo info) {
            return base.getApplicationBanner(mapApplicationInfo(info));
        }

        @Override
        public Drawable getApplicationBanner(String packageName) throws NameNotFoundException {
            return base.getApplicationBanner(mapPackageName(packageName));
        }

        @Override
        public int getApplicationEnabledSetting(String packageName) {
            return base.getApplicationEnabledSetting(mapPackageName(packageName));
        }

        @Override
        public Drawable getApplicationIcon(ApplicationInfo info) {
            return base.getApplicationIcon(mapApplicationInfo(info));
        }

        @Override
        public Drawable getApplicationIcon(String packageName) throws NameNotFoundException {
            return base.getApplicationIcon(mapPackageName(packageName));
        }

        @Override
        public ApplicationInfo getApplicationInfo(String packageName, int flags) throws NameNotFoundException {
            return bridgeApplicationInfo(base.getApplicationInfo(mapPackageName(packageName), flags));
        }

        @Override
        public ApplicationInfo getApplicationInfo(String packageName, PackageManager.ApplicationInfoFlags flags) throws NameNotFoundException {
            return bridgeApplicationInfo(base.getApplicationInfo(mapPackageName(packageName), flags));
        }

        @Override
        public CharSequence getApplicationLabel(ApplicationInfo info) {
            return base.getApplicationLabel(mapApplicationInfo(info));
        }

        @Override
        public Drawable getApplicationLogo(ApplicationInfo info) {
            return base.getApplicationLogo(mapApplicationInfo(info));
        }

        @Override
        public Drawable getApplicationLogo(String packageName) throws NameNotFoundException {
            return base.getApplicationLogo(mapPackageName(packageName));
        }

        @Override
        public ChangedPackages getChangedPackages(int sequenceNumber) {
            return base.getChangedPackages(sequenceNumber);
        }

        @Override
        public int getComponentEnabledSetting(ComponentName componentName) {
            return base.getComponentEnabledSetting(mapComponentName(componentName));
        }

        @Override
        public Drawable getDefaultActivityIcon() {
            return base.getDefaultActivityIcon();
        }

        @Override
        public Drawable getDrawable(String packageName, int resid, ApplicationInfo info) {
            return base.getDrawable(mapPackageName(packageName), resid, mapApplicationInfo(info));
        }

        @Override
        public List<ApplicationInfo> getInstalledApplications(int flags) {
            return bridgeInstalledApplications(base.getInstalledApplications(flags));
        }

        @Override
        public List<ApplicationInfo> getInstalledApplications(PackageManager.ApplicationInfoFlags flags) {
            return bridgeInstalledApplications(base.getInstalledApplications(flags));
        }

        @Override
        public List<PackageInfo> getInstalledPackages(int flags) {
            return bridgeInstalledPackages(base.getInstalledPackages(flags));
        }

        @Override
        public List<PackageInfo> getInstalledPackages(PackageManager.PackageInfoFlags flags) {
            return bridgeInstalledPackages(base.getInstalledPackages(flags));
        }

        @Override
        public String getInstallerPackageName(String packageName) {
            return base.getInstallerPackageName(mapPackageName(packageName));
        }

        @Override
        public byte[] getInstantAppCookie() {
            return base.getInstantAppCookie();
        }

        @Override
        public int getInstantAppCookieMaxBytes() {
            return base.getInstantAppCookieMaxBytes();
        }

        @Override
        public InstrumentationInfo getInstrumentationInfo(ComponentName className, int flags) throws NameNotFoundException {
            return base.getInstrumentationInfo(mapComponentName(className), flags);
        }

        @Override
        public Intent getLaunchIntentForPackage(String packageName) {
            return base.getLaunchIntentForPackage(mapPackageName(packageName));
        }

        @Override
        public Intent getLeanbackLaunchIntentForPackage(String packageName) {
            return base.getLeanbackLaunchIntentForPackage(mapPackageName(packageName));
        }

        @Override
        public String getNameForUid(int uid) {
            return bridgePackageName(base.getNameForUid(uid));
        }

        @Override
        public int[] getPackageGids(String packageName) throws NameNotFoundException {
            return base.getPackageGids(mapPackageName(packageName));
        }

        @Override
        public int[] getPackageGids(String packageName, int flags) throws NameNotFoundException {
            return base.getPackageGids(mapPackageName(packageName), flags);
        }

        @Override
        public PackageInfo getPackageInfo(VersionedPackage versionedPackage, int flags) throws NameNotFoundException {
            VersionedPackage mapped = versionedPackage;
            if (versionedPackage != null && owner.isHostPackageName(versionedPackage.getPackageName())) {
                mapped = new VersionedPackage(owner.getRealPackageName(), versionedPackage.getLongVersionCode());
            }
            return bridgePackageInfo(base.getPackageInfo(mapped, flags));
        }

        @Override
        public PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException {
            return bridgePackageInfo(base.getPackageInfo(mapPackageName(packageName), flags));
        }

        @Override
        public PackageInfo getPackageInfo(VersionedPackage versionedPackage, PackageManager.PackageInfoFlags flags) throws NameNotFoundException {
            VersionedPackage mapped = versionedPackage;
            if (versionedPackage != null && owner.isHostPackageName(versionedPackage.getPackageName())) {
                mapped = new VersionedPackage(owner.getRealPackageName(), versionedPackage.getLongVersionCode());
            }
            return bridgePackageInfo(base.getPackageInfo(mapped, flags));
        }

        @Override
        public PackageInfo getPackageInfo(String packageName, PackageManager.PackageInfoFlags flags) throws NameNotFoundException {
            return bridgePackageInfo(base.getPackageInfo(mapPackageName(packageName), flags));
        }

        @Override
        public PackageInstaller getPackageInstaller() {
            return base.getPackageInstaller();
        }

        @Override
        public int getPackageUid(String packageName, int flags) throws NameNotFoundException {
            return base.getPackageUid(mapPackageName(packageName), flags);
        }

        @Override
        public String[] getPackagesForUid(int uid) {
            return bridgePackagesForUid(base.getPackagesForUid(uid));
        }

        @Override
        public List<PackageInfo> getPackagesHoldingPermissions(String[] permissions, int flags) {
            return bridgeInstalledPackages(base.getPackagesHoldingPermissions(permissions, flags));
        }

        @Override
        public List<PackageInfo> getPackagesHoldingPermissions(String[] permissions, PackageManager.PackageInfoFlags flags) {
            return bridgeInstalledPackages(base.getPackagesHoldingPermissions(permissions, flags));
        }

        @Override
        public PermissionGroupInfo getPermissionGroupInfo(String name, int flags) throws NameNotFoundException {
            return base.getPermissionGroupInfo(name, flags);
        }

        @Override
        public PermissionInfo getPermissionInfo(String name, int flags) throws NameNotFoundException {
            return base.getPermissionInfo(name, flags);
        }

        @Override
        public int getPreferredActivities(List<IntentFilter> outFilters, List<ComponentName> outActivities, String packageName) {
            return base.getPreferredActivities(outFilters, outActivities, mapPackageName(packageName));
        }

        @Override
        public List<PackageInfo> getPreferredPackages(int flags) {
            return bridgeInstalledPackages(base.getPreferredPackages(flags));
        }

        @Override
        public ProviderInfo getProviderInfo(ComponentName component, int flags) throws NameNotFoundException {
            return base.getProviderInfo(mapComponentName(component), flags);
        }

        @Override
        public ProviderInfo getProviderInfo(ComponentName component, PackageManager.ComponentInfoFlags flags) throws NameNotFoundException {
            return base.getProviderInfo(mapComponentName(component), flags);
        }

        @Override
        public ActivityInfo getReceiverInfo(ComponentName component, int flags) throws NameNotFoundException {
            return base.getReceiverInfo(mapComponentName(component), flags);
        }

        @Override
        public ActivityInfo getReceiverInfo(ComponentName component, PackageManager.ComponentInfoFlags flags) throws NameNotFoundException {
            return base.getReceiverInfo(mapComponentName(component), flags);
        }

        @Override
        public Resources getResourcesForActivity(ComponentName activityName) throws NameNotFoundException {
            return base.getResourcesForActivity(mapComponentName(activityName));
        }

        @Override
        public Resources getResourcesForApplication(ApplicationInfo app) throws NameNotFoundException {
            return base.getResourcesForApplication(mapApplicationInfo(app));
        }

        @Override
        public Resources getResourcesForApplication(String packageName) throws NameNotFoundException {
            return base.getResourcesForApplication(mapPackageName(packageName));
        }

        @Override
        public ServiceInfo getServiceInfo(ComponentName component, int flags) throws NameNotFoundException {
            return base.getServiceInfo(mapComponentName(component), flags);
        }

        @Override
        public ServiceInfo getServiceInfo(ComponentName component, PackageManager.ComponentInfoFlags flags) throws NameNotFoundException {
            return base.getServiceInfo(mapComponentName(component), flags);
        }

        @Override
        public List<SharedLibraryInfo> getSharedLibraries(int flags) {
            return base.getSharedLibraries(flags);
        }

        @Override
        public FeatureInfo[] getSystemAvailableFeatures() {
            return base.getSystemAvailableFeatures();
        }

        @Override
        public String[] getSystemSharedLibraryNames() {
            return base.getSystemSharedLibraryNames();
        }

        @Override
        public CharSequence getText(String packageName, int resid, ApplicationInfo info) {
            return base.getText(mapPackageName(packageName), resid, mapApplicationInfo(info));
        }

        @Override
        public Drawable getUserBadgedDrawableForDensity(Drawable drawable, UserHandle user, Rect badgeLocation, int badgeDensity) {
            return base.getUserBadgedDrawableForDensity(drawable, user, badgeLocation, badgeDensity);
        }

        @Override
        public Drawable getUserBadgedIcon(Drawable icon, UserHandle user) {
            return base.getUserBadgedIcon(icon, user);
        }

        @Override
        public CharSequence getUserBadgedLabel(CharSequence label, UserHandle user) {
            return base.getUserBadgedLabel(label, user);
        }

        @Override
        public XmlResourceParser getXml(String packageName, int resid, ApplicationInfo info) {
            return base.getXml(mapPackageName(packageName), resid, mapApplicationInfo(info));
        }

        @Override
        public boolean hasSystemFeature(String name) {
            return base.hasSystemFeature(name);
        }

        @Override
        public boolean hasSystemFeature(String name, int version) {
            return base.hasSystemFeature(name, version);
        }

        @Override
        public boolean isInstantApp() {
            return base.isInstantApp();
        }

        @Override
        public boolean isInstantApp(String packageName) {
            return base.isInstantApp(mapPackageName(packageName));
        }

        @Override
        public boolean isPermissionRevokedByPolicy(String permName, String packageName) {
            return base.isPermissionRevokedByPolicy(permName, mapPackageName(packageName));
        }

        @Override
        public boolean isSafeMode() {
            return base.isSafeMode();
        }

        @Override
        public List<ResolveInfo> queryBroadcastReceivers(Intent intent, int flags) {
            return base.queryBroadcastReceivers(intent, flags);
        }

        @Override
        public List<ProviderInfo> queryContentProviders(String processName, int uid, int flags) {
            return base.queryContentProviders(processName, uid, flags);
        }

        @Override
        public List<InstrumentationInfo> queryInstrumentation(String targetPackage, int flags) {
            return base.queryInstrumentation(mapPackageName(targetPackage), flags);
        }

        @Override
        public List<ResolveInfo> queryIntentActivities(Intent intent, int flags) {
            return base.queryIntentActivities(intent, flags);
        }

        @Override
        public List<ResolveInfo> queryIntentActivityOptions(ComponentName caller, Intent[] specifics, Intent intent, int flags) {
            return base.queryIntentActivityOptions(mapComponentName(caller), specifics, intent, flags);
        }

        @Override
        public List<ResolveInfo> queryIntentContentProviders(Intent intent, int flags) {
            return base.queryIntentContentProviders(intent, flags);
        }

        @Override
        public List<ResolveInfo> queryIntentServices(Intent intent, int flags) {
            return base.queryIntentServices(intent, flags);
        }

        @Override
        public List<PermissionInfo> queryPermissionsByGroup(String group, int flags) throws NameNotFoundException {
            return base.queryPermissionsByGroup(group, flags);
        }

        @Override
        public void removePackageFromPreferred(String packageName) {
            base.removePackageFromPreferred(mapPackageName(packageName));
        }

        @Override
        public void removePermission(String name) {
            base.removePermission(name);
        }

        @Override
        public ResolveInfo resolveActivity(Intent intent, int flags) {
            return base.resolveActivity(intent, flags);
        }

        @Override
        public ProviderInfo resolveContentProvider(String name, int flags) {
            return base.resolveContentProvider(name, flags);
        }

        @Override
        public ResolveInfo resolveService(Intent intent, int flags) {
            return base.resolveService(intent, flags);
        }

        @Override
        public void setApplicationCategoryHint(String packageName, int categoryHint) {
            base.setApplicationCategoryHint(mapPackageName(packageName), categoryHint);
        }

        @Override
        public void setApplicationEnabledSetting(String packageName, int newState, int flags) {
            base.setApplicationEnabledSetting(mapPackageName(packageName), newState, flags);
        }

        @Override
        public void setComponentEnabledSetting(ComponentName componentName, int newState, int flags) {
            base.setComponentEnabledSetting(mapComponentName(componentName), newState, flags);
        }

        @Override
        public void setInstallerPackageName(String targetPackage, String installerPackageName) {
            base.setInstallerPackageName(mapPackageName(targetPackage), mapPackageName(installerPackageName));
        }

        @Override
        public void updateInstantAppCookie(byte[] cookie) {
            base.updateInstantAppCookie(cookie);
        }

        @Override
        public void verifyPendingInstall(int id, int verificationCode) {
            base.verifyPendingInstall(id, verificationCode);
        }
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        try {
            JsLoader.destroy();
        } catch (Throwable ignored) {
        }
    }
}
