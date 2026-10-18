package com.dsh.nomount;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.rapid.api.Platform;
import com.rapid.framework.program.android.DvmPluginDescriptor;
import com.rapid.framework.program.android.app.AndroidPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * 免安装运行器插件入口。
 *
 * <p>结绳 IDE（app1）就是宿主：插件激活时把宿主的 Instrumentation 换成
 * {@link HostInstrumentation}，之后任何未安装 APK 的界面都可以通过宿主里已声明的
 * 代理 Activity 显示出来，整个过程不经过 PackageManager 安装。
 *
 * <p>当前 v0.1 用文件触发（便于验证真实链路）：
 * {@code /sdcard/Tiecode/免安装运行.txt}，内容一行 {@code /path/to/app.apk} 或
 * {@code /path/to/app.apk#com.pkg.MainActivity}，{@code #} 开头为注释。
 */
public class NoMountPlugin extends AndroidPlugin {

    public static final String TAG = "DSHNoMount";
    public static final String CONFIG_FILE = "/sdcard/Tiecode/免安装运行.txt";
    /** 等宿主界面稳下来再拉起目标窗口。 */
    private static final long START_DELAY_MS = 6000L;

    private static Context hostContext;
    private static boolean hookInstalled;
    private static Thread watcher;

    @Override
    public void onInit(DvmPluginDescriptor descriptor) {
        try {
            super.onInit(descriptor);
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "onInit 完成");
    }

    @Override
    public void activate() {
        try {
            super.activate();
        } catch (Throwable ignored) {
        }
        Context ctx = null;
        try {
            ctx = Platform.getApplicationContext();
        } catch (Throwable t) {
            Log.e(TAG, "拿不到宿主 Context", t);
        }
        hostContext = ctx;
        Log.i(TAG, "activate 宿主=" + (ctx == null ? "null" : ctx.getPackageName()));
        if (ctx == null) {
            return;
        }
        installHook(ctx);
        startConfigWatcher(ctx);
        HotLoader.start(ctx);
    }

    /** 热加载 payload 时的入口：只装 hook，监听线程由引导版负责。 */
    public static void bootstrap(Context ctx) {
        hostContext = ctx;
        installHook(ctx);
        Log.i(TAG, "payload 已接管 (bootstrap)");
    }

    /** 把宿主的 Instrumentation 换成我们的代理实现（幂等）。 */
    public static synchronized boolean installHook(Context ctx) {
        if (hookInstalled) {
            return true;
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = Reflect.getStatic(activityThread, "currentActivityThread");
            if (thread == null) {
                Log.e(TAG, "currentActivityThread() 返回 null，挂载失败");
                return false;
            }
            Object current = Reflect.field(thread, "mInstrumentation");
            if (current instanceof HostInstrumentation) {
                ApkRuntime.baseInstrumentation = ((HostInstrumentation) current).getBase();
                hookInstalled = true;
                return true;
            }
            if (!(current instanceof Instrumentation)) {
                Log.e(TAG, "宿主 Instrumentation 类型异常: " + current);
                return false;
            }
            ApkRuntime.baseInstrumentation = (Instrumentation) current;
            HostInstrumentation hook = new HostInstrumentation((Instrumentation) current, ctx);
            if (!Reflect.setField(thread, "mInstrumentation", hook)) {
                Log.e(TAG, "写 mInstrumentation 失败（hidden API 受限？）");
                return false;
            }
            hookInstalled = true;
            Log.i(TAG, "Instrumentation 已挂载: " + current + " -> " + hook);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "挂载 Instrumentation 异常", t);
            return false;
        }
    }

    /**
     * v0.1 触发方式：盯着配置文件，内容一变就拉起里面写的 APK。
     * 这样在 IDE 已经跑起来之后，改配置即可反复触发，不必重启宿主。
     */
    private void startConfigWatcher(Context ctx) {
        if (watcher != null) {
            return;
        }
        watcher = new Thread(() -> {
            // 启动时先「记住」当前内容但不触发运行：
            // 否则每次打开结绳都会自动跳去跑上一次调试用的 APK（很打扰）。
            long lastStamp = Long.MIN_VALUE;
            String lastLine = null;
            try {
                File current = new File(CONFIG_FILE);
                if (current.isFile()) {
                    lastStamp = current.lastModified() * 1000L + current.length();
                    lastLine = readConfigLine();
                }
            } catch (Throwable ignored) {
            }
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    File file = new File(CONFIG_FILE);
                    long stamp = file.isFile() ? (file.lastModified() * 1000L + file.length()) : -1L;
                    if (stamp != lastStamp) {
                        lastStamp = stamp;
                        String line = readConfigLine();
                        if (line != null && !line.equals(lastLine)) {
                            lastLine = line;
                            runLine(ctx, line, 3000L);
                        }
                    }
                    Thread.sleep(1500L);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    Log.e(TAG, "配置监听异常", t);
                }
            }
        }, "dsh-nomount-watcher");
        watcher.setDaemon(true);
        watcher.start();
        Log.i(TAG, "配置监听已启动: " + CONFIG_FILE);
    }

    private static void runLine(Context ctx, String line, long delayMs) {
        String path = line;
        String activity = null;
        int hash = line.indexOf('#');
        if (hash > 0) {
            path = line.substring(0, hash).trim();
            activity = line.substring(hash + 1).trim();
        }
        final String apk = path;
        final String target = activity == null || activity.isEmpty() ? null : activity;
        Log.i(TAG, "计划运行: " + apk + " 启动窗口=" + target);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (HotLoader.dispatchRun(apk, target)) {
                return;
            }
            try {
                ApkRuntime.launch(hostContext != null ? hostContext : ctx, apk, target);
                Log.i(TAG, "已请求显示目标窗口");
            } catch (Throwable t) {
                Log.e(TAG, "免安装运行失败", t);
            }
        }, delayMs);
    }

    private static String readConfigLine() {
        File file = new File(CONFIG_FILE);
        if (!file.isFile()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                return line;
            }
        } catch (Throwable t) {
            Log.e(TAG, "读配置失败", t);
        }
        return null;
    }

    /** 运行一个未安装 APK：装好 hook 后把加载/启动放到主线程（构建回调在后台线程）。 */
    public static void runApk(String apkPath, String activityName) {
        final Context ctx = hostContext != null ? hostContext : Platform.getApplicationContext();
        if (ctx == null) {
            Log.e(TAG, "宿主 Context 不可用");
            return;
        }
        installHook(ctx);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ApkRuntime.launch(ctx, apkPath, activityName);
                Log.i(TAG, "已请求显示目标窗口（免安装）");
            } catch (Throwable t) {
                Log.e(TAG, "runApk 失败", t);
            }
        });
    }
}
