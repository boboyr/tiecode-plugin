package com.dsh.nomount;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Constructor;

import dalvik.system.DexClassLoader;

/**
 * 未安装 APK 的加载器：一份 dex ClassLoader + 一份独立 Resources，
 * 之后由 {@link HostInstrumentation} 把界面代理出来。
 */
public final class ApkRuntime {

    public static final String TAG = "DSHNoMount";
    /** 代理 Activity 在宿主清单里已声明，见结绳 AndroidManifest。 */
    public static final String PROXY_ACTIVITY = "com.rapid.program.impl.android.dynamic.DynamicActivity";
    public static final String EXTRA_TARGET = "dsh.nomount.target";
    public static final String EXTRA_SOURCE = "dsh.nomount.source";

    public static volatile DexClassLoader classLoader;
    public static volatile Resources resources;
    public static volatile AssetManager assets;
    public static volatile String packageName;
    public static volatile String hostPackageName;
    public static volatile String sourcePath;
    public static volatile ActivityInfo[] activities;
    public static volatile Application application;
    public static volatile Instrumentation baseInstrumentation;
    /** 目标 APK 的应用级主题（Activity 没写 theme 时用它兜底）。 */
    public static volatile int applicationTheme;
    /** 已加载产物副本的 mtime+size，用来判断“产物变了要整份重载”。 */
    private static volatile long loadedSourceStamp = -1L;
    /** 最近一次创建的未安装 Activity，重建前先把它 finish 掉。 */
    private static volatile Activity currentActivity;

    private ApkRuntime() {
    }

    public static synchronized void load(Context host, String apkPath) throws Exception {
        File apk = new File(apkPath);
        if (!apk.isFile()) {
            throw new IllegalStateException("APK 不存在: " + apkPath);
        }
        long stamp = apk.lastModified() * 1000L + apk.length();
        if (apkPath.equals(sourcePath) && classLoader != null && stamp == loadedSourceStamp) {
            // 产物没变，直接复用已加载的环境
            return;
        }
        hostPackageName = host.getPackageName();

        // 关键：**不要直接加载构建产物**。DexClassLoader 会 mmap 打开它，
        // 之后结绳的打包/签名任务再去写同一个文件就会失败（你遇到的“改代码再编译报错”）。
        // 因此先拷一份私有副本，每次都加载副本。
        File copyDir = new File(host.getDir("dsh-nomount-apk", Context.MODE_PRIVATE), "apk");
        if (!copyDir.isDirectory() && !copyDir.mkdirs()) {
            throw new IllegalStateException("无法创建 APK 副本目录: " + copyDir);
        }
        File copy = new File(copyDir, "target-" + stamp + ".apk");
        if (!copy.isFile() || copy.length() != apk.length()) {
            copyFile(apk, copy);
            Log.i(TAG, "已拷贝产物副本: " + copy + " (" + copy.length() + " 字节)");
        }
        final String loadPath = copy.getAbsolutePath();

        PackageManager pm = host.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(loadPath, PackageManager.GET_ACTIVITIES);
        if (info == null || info.applicationInfo == null) {
            throw new IllegalStateException("无法解析 APK 清单: " + apkPath);
        }
        ApplicationInfo appInfo = info.applicationInfo;
        // 这两行是 PackageManager 拿未安装 APK 图标/资源时的惯例修正
        appInfo.sourceDir = loadPath;
        appInfo.publicSourceDir = loadPath;
        appInfo.sourceDir = loadPath;

        // 每次产物变化都换新的 odex 目录，避免命中上一版 dex 缓存
        File optDir = new File(host.getDir("dsh-nomount-opt", Context.MODE_PRIVATE), "dex-" + stamp);
        if (!optDir.isDirectory() && !optDir.mkdirs()) {
            throw new IllegalStateException("无法创建 odex 目录: " + optDir);
        }

        // 重建前先把上一版跑着的未安装窗口收掉，否则画面还是旧的
        finishCurrentActivity();

        // 带 native 库的工程（Spine/GDX 等）要能 System.loadLibrary，必须把 APK 里的 .so 解出来
        // 并挂到 ClassLoader 的 native 搜索路径上，否则报 "Couldn't load shared library 'gdx'"。
        String libDir = extractNativeLibs(host, loadPath, info.packageName, stamp);
        if (libDir != null) {
            appInfo.nativeLibraryDir = libDir;
        }

        ApkClassLoader loader = new ApkClassLoader(loadPath, optDir.getAbsolutePath(), libDir,
                ApkRuntime.class.getClassLoader());
        AssetManager am = newAssetManager();
        Reflect.call(AssetManager.class, am, "addAssetPath", loadPath);
        Resources hostRes = host.getResources();
        Resources res = new Resources(am, hostRes.getDisplayMetrics(), hostRes.getConfiguration());

        packageName = info.packageName;
        activities = info.activities;
        applicationTheme = appInfo.theme;
        classLoader = loader;
        assets = am;
        resources = res;
        sourcePath = apkPath;
        loadedSourceStamp = stamp;
        application = null;

        Log.i(TAG, "已加载未安装 APK: " + packageName + " (" + apkPath + ")"
                + " activities=" + (activities == null ? 0 : activities.length)
                + " appTheme=0x" + Integer.toHexString(applicationTheme));

        tryCreateApplication(host, appInfo, loader, res);
    }

    /**
     * 把目标 APK 里**当前设备 ABI** 的 .so 解到插件私有目录，返回该目录当作 ClassLoader 的
     * native 搜索路径（没有 native 库时返回 null）。
     */
    private static String extractNativeLibs(Context host, String apkPath, String pkg, long stamp) {
        try {
            java.util.Map<String, java.util.List<java.util.zip.ZipEntry>> byAbi = new java.util.HashMap<>();
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apkPath)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    java.util.zip.ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.startsWith("lib/") || !name.endsWith(".so")) {
                        continue;
                    }
                    String[] parts = name.split("/");
                    if (parts.length != 3) {
                        continue;
                    }
                    java.util.List<java.util.zip.ZipEntry> list = byAbi.get(parts[1]);
                    if (list == null) {
                        list = new java.util.ArrayList<>();
                        byAbi.put(parts[1], list);
                    }
                    list.add(entry);
                }

                String abi = null;
                for (String supported : android.os.Build.SUPPORTED_ABIS) {
                    if (byAbi.containsKey(supported)) {
                        abi = supported;
                        break;
                    }
                }
                if (abi == null) {
                    return null;
                }
                File dir = new File(host.getDir("dsh-nomount-libs", Context.MODE_PRIVATE), pkg + "/" + stamp + "/" + abi);
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    return null;
                }
                int count = 0;
                for (java.util.zip.ZipEntry entry : byAbi.get(abi)) {
                    String fileName = entry.getName().substring(("lib/" + abi + "/").length());
                    File out = new File(dir, fileName);
                    if (out.isFile() && out.length() == entry.getSize() && entry.getSize() > 0) {
                        count++;
                        continue;
                    }
                    try (java.io.InputStream in = zip.getInputStream(entry);
                         java.io.FileOutputStream os = new java.io.FileOutputStream(out)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            os.write(buf, 0, n);
                        }
                    }
                    out.setReadable(true, false);
                    out.setExecutable(true, false);
                    count++;
                }
                Log.i(TAG, "已解出 " + count + " 个 native 库(" + abi + ") 到 " + dir);
                return dir.getAbsolutePath();
            }
        } catch (Throwable t) {
            Log.w(TAG, "解出 native 库失败", t);
            return null;
        }
    }

    private static void tryCreateApplication(Context host, ApplicationInfo appInfo,
                                             ClassLoader loader, Resources res) {
        Instrumentation base = baseInstrumentation;
        if (base == null) {
            return;
        }
        String name = appInfo.className != null ? appInfo.className : "android.app.Application";
        try {
            TargetContext appContext = new TargetContext(host, res, loader, packageName);
            Application app = base.newApplication(loader, name, appContext);
            base.callApplicationOnCreate(app);
            application = app;
            Log.i(TAG, "已创建目标 Application: " + name);
        } catch (Throwable t) {
            Log.w(TAG, "目标 Application 初始化失败（忽略，继续跑界面）", t);
        }
    }

    private static AssetManager newAssetManager() throws Exception {
        Constructor<AssetManager> c;
        try {
            c = AssetManager.class.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            c = AssetManager.class.getDeclaredConstructor(boolean.class);
            c.setAccessible(true);
            return c.newInstance(false);
        }
        c.setAccessible(true);
        return c.newInstance();
    }

    public static ActivityInfo findActivity(String name) {
        if (activities == null || name == null) {
            return null;
        }
        for (ActivityInfo ai : activities) {
            if (name.equals(ai.name)) {
                return ai;
            }
        }
        return null;
    }

    /** 没给启动 Activity 名时的兜底：先按清单里的 MAIN/LAUNCHER 找，再退化成第一个。 */
    public static String guessActivity() {
        if (sourcePath != null) {
            String launcher = ManifestParser.findLauncher(new File(sourcePath), packageName);
            if (launcher != null) {
                return launcher;
            }
        }
        if (activities == null || activities.length == 0) {
            return null;
        }
        if (activities.length > 1) {
            Log.w(TAG, "APK 有 " + activities.length + " 个 Activity，未指定启动窗口，暂用 "
                    + activities[0].name);
        }
        return activities[0].name;
    }

    /** 把清单里给的名字对齐到 PackageManager 报出来的真实类名。 */
    public static String resolveActivityName(String name) {
        if (name == null || activities == null) {
            return name;
        }
        for (ActivityInfo ai : activities) {
            if (ai.name != null && ai.name.equals(name)) {
                return ai.name;
            }
        }
        for (ActivityInfo ai : activities) {
            if (ai.name != null && (ai.name.endsWith("." + name) || name.endsWith("." + ai.name))) {
                Log.i(TAG, "启动窗口名对齐: " + name + " -> " + ai.name);
                return ai.name;
            }
        }
        return name;
    }

    /** 加载并显示目标 APK 的窗口。 */
    public static void launch(Context host, String apkPath, String activityName) throws Exception {
        load(host, apkPath);
        String target = activityName != null && !activityName.isEmpty()
                ? activityName : guessActivity();
        if (target == null) {
            throw new IllegalStateException("APK 里找不到任何 Activity");
        }
        target = resolveActivityName(target);
        if (findActivity(target) == null) {
            Log.w(TAG, "清单里没有这个 Activity，仍按名字尝试: " + target);
        }
        Intent intent = new Intent();
        intent.setClassName(hostPackageName, PROXY_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(EXTRA_TARGET, target);
        intent.putExtra(EXTRA_SOURCE, apkPath);
        Log.i(TAG, "启动代理窗口 " + PROXY_ACTIVITY + " -> " + target);
        host.startActivity(intent);
    }

    /** Activity 创建前把资源/Context/主题/Application 换成目标 APK 的。 */
    static void injectActivity(Activity activity, Context host) {
        Resources res = resources;
        ClassLoader loader = classLoader;
        if (res == null || loader == null) {
            return;
        }
        try {
            TargetContext base = new TargetContext(host, res, loader, packageName);
            currentActivity = activity;
            Reflect.setField(activity, "mResources", res);
            Reflect.setField(activity, "mBase", base);
            Reflect.setField(activity, "mTheme", null);
            ActivityInfo info = findActivity(activity.getClass().getName());
            if (info != null) {
                Reflect.setField(activity, "mActivityInfo", info);
            }
            int theme = info != null ? info.getThemeResource() : 0;
            int source = theme != 0 ? theme : applicationTheme;
            // 有些 APK 清单里只写 @android:style/...，但 Activity 是 AppCompat 的，
            // 用系统主题会让 AppCompat/Material 直接抛异常，这里换成它自己随包带的 AppCompat 主题。
            if ((source == 0 || (source >>> 24) == 0x01) && isAppCompatActivity(activity.getClass())) {
                int fallback = res.getIdentifier("Theme.AppCompat.Light.NoActionBar", "style", packageName);
                if (fallback == 0) {
                    fallback = res.getIdentifier("Theme.AppCompat.Light", "style", packageName);
                }
                if (fallback == 0) {
                    fallback = res.getIdentifier("Theme.AppCompat", "style", packageName);
                }
                if (fallback != 0) {
                    Log.i(TAG, "AppCompat 界面遇到系统主题，改用目标包自带主题 0x"
                            + Integer.toHexString(fallback));
                    source = fallback;
                }
            }
            Log.i(TAG, "主题: activity=0x" + Integer.toHexString(theme)
                    + " app=0x" + Integer.toHexString(applicationTheme)
                    + " 采用=0x" + Integer.toHexString(source));
            if (source != 0) {
                String name;
                try {
                    name = res.getResourceName(source);
                } catch (Throwable t) {
                    name = "解析不到(" + t.getClass().getSimpleName() + ")";
                }
                Log.i(TAG, "主题资源名: " + name);
                try {
                    activity.setTheme(source);
                } catch (Throwable t) {
                    // 主题失败也不要中断后面的注入，否则 Application/窗口样式都换不上，界面更糟
                    Log.e(TAG, "应用目标主题失败(0x" + Integer.toHexString(source) + ")", t);
                }
            }
            Log.i(TAG, "注入后 resources=" + (activity.getResources() == res ? "目标" : "宿主")
                    + " theme=" + activity.getTheme()
                    + " application=" + (activity.getApplication() == application ? "目标" : "宿主"));
            if (application != null) {
                Reflect.setField(activity, "mApplication", application);
            }
            resetWindowStyle(activity);
        } catch (Throwable t) {
            Log.e(TAG, "注入目标环境失败", t);
        }
    }

    /** 目标 Activity 是不是 AppCompat/Fragment 系（它们对主题有硬要求）。 */
    private static boolean isAppCompatActivity(Class<?> clazz) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            String name = c.getName();
            if ("androidx.appcompat.app.AppCompatActivity".equals(name)
                    || "androidx.fragment.app.FragmentActivity".equals(name)
                    || "androidx.activity.ComponentActivity".equals(name)
                    || "androidx.core.app.ComponentActivity".equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * PhoneWindow 在 attach 时（我们还没换主题）就缓存了窗口样式，
     * 这里清掉让它按目标主题重新读一遍：否则窗口背景/标题栏还是宿主的，看起来全黑。
     */
    private static void resetWindowStyle(Activity activity) {
        try {
            Object window = activity.getWindow();
            if (window == null) {
                return;
            }
            boolean a = Reflect.setField(window, "mWindowStyle", null);
            boolean b = Reflect.setField(window, "mBackgroundDrawable", null);
            boolean c = Reflect.setField(window, "mDecor", null);
            Log.i(TAG, "窗口样式重置: style=" + a + " bg=" + b + " decor=" + c);
        } catch (Throwable t) {
            Log.w(TAG, "窗口样式重置失败", t);
        }
    }

    private static void copyFile(File from, File to) throws Exception {
        try (java.io.InputStream in = new java.io.FileInputStream(from);
             java.io.FileOutputStream out = new java.io.FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.getFD().sync();
        }
    }

    /** 重建前把上一版跑着的未安装窗口关掉，避免出现“两个窗口/画面不更新”。 */
    static void finishCurrentActivity() {
        final Activity activity = currentActivity;
        currentActivity = null;
        if (activity == null || activity.isFinishing()) {
            return;
        }
        try {
            activity.finish();
            Log.i(TAG, "已关闭上一版未安装窗口: " + activity.getClass().getName());
        } catch (Throwable t) {
            Log.w(TAG, "关闭旧窗口失败", t);
        }
    }
}
