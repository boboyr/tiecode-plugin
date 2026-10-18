package com.dsh.nomount;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;

/**
 * 开发用热加载：监听 sdcard 上的 payload dex，一变就用**子优先**加载器重新装一遍 hook。
 *
 * <p>这样改插件逻辑不用重启结绳——把新 dex 推到 /sdcard/Tiecode/nomount-payload.dex 即可。
 * 生产构建里 payload 不存在时，这段逻辑完全不参与。
 */
public final class HotLoader {

    public static final String TAG = "DSHNoMount";
    public static final String PAYLOAD_PATH = "/sdcard/Tiecode/nomount-payload.dex";
    /** payload 里的入口类，必须提供 static void bootstrap(Context)。 */
    private static final String ENTRY = "com.dsh.nomount.NoMountPlugin";

    private static volatile Class<?> payloadClass;
    private static Thread thread;

    private HotLoader() {
    }

    public static synchronized void start(Context ctx) {
        if (thread != null) {
            return;
        }
        thread = new Thread(() -> {
            long lastStamp = Long.MIN_VALUE;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    File file = new File(PAYLOAD_PATH);
                    long stamp = file.isFile() ? (file.lastModified() * 1000L + file.length()) : -1L;
                    if (stamp != lastStamp) {
                        lastStamp = stamp;
                        if (file.isFile()) {
                            reload(ctx);
                        }
                    }
                    Thread.sleep(1500L);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    Log.e(TAG, "热加载异常", t);
                }
            }
        }, "dsh-nomount-hotloader");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "热加载监听已启动: " + PAYLOAD_PATH);
    }

    private static void reload(Context ctx) throws Exception {
        File optDir = new File(ctx.getDir("dsh-nomount-opt", Context.MODE_PRIVATE), "payload");
        if (!optDir.isDirectory() && !optDir.mkdirs()) {
            throw new IllegalStateException("无法创建 payload odex 目录: " + optDir);
        }
        ApkClassLoader loader = new ApkClassLoader(PAYLOAD_PATH, optDir.getAbsolutePath(), null,
                HotLoader.class.getClassLoader());
        Class<?> entry = loader.loadClass(ENTRY);
        Method bootstrap = entry.getMethod("bootstrap", Context.class);
        bootstrap.invoke(null, ctx);
        payloadClass = entry;
        Log.i(TAG, "热加载完成，已切到新代码: " + entry.getClassLoader());
    }

    /** 把一次运行请求交给 payload 版实现；没有 payload 时返回 false。 */
    static boolean dispatchRun(String apk, String activity) {
        Class<?> entry = payloadClass;
        if (entry == null) {
            return false;
        }
        try {
            entry.getMethod("runApk", String.class, String.class).invoke(null, apk, activity);
            Log.i(TAG, "运行请求已交给热加载代码");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "热加载代码执行失败，回退内置实现", t);
            return false;
        }
    }
}
