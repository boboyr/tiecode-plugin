package org.libsdl.app;

import android.content.Context;
import android.os.Build;
import android.os.Process;

import com.rapid.util.android.AssetUtils;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * 供插件页面使用的 SDL 入口单例。
 *
 * <p>放在 {@code org.libsdl.app} 包内：SDLActivity 的 mSingleton/mSurface 是 protected static，
 * 只有同包或子类可写；原生 libSDL3.so 的 JNI_OnLoad 固定把回调注册到
 * {@code org/libsdl/app/SDLActivity}，{@code getNativeSurface()} 也固定读取
 * {@code SDLActivity.mSurface}。
 *
 * <p><b>与结绳Linux工程插件版本唯一的区别</b>：{@link #getMainFunction()} 返回
 * <b>{@code "SDL_main"}</b> 而不是 {@code "main"}。
 * <p>原因：那边的 libmain.so 是 C++（tiec + aarch64-linux-android-g++）编的，
 * 用户写的 {@code main} 被 {@code SDL_main} 宏改名为 SDL_main，而 SDL 自己的
 * SDL_main_impl 又补了一个真正的 {@code main} 符号，所以 dlsym("main") 能取到入口。
 * Go 这里没有 C 的宏机制，而 cgo 的 {@code //export} <b>无法导出名为 main 的符号</b>
 * （package main 的 {@code func main()} 签名与 {@code int(int,char**)} 冲突，
 * 且 Go 运行时本身占用 main 符号）。因此 Go 侧统一导出 {@code SDL_main}，
 * 这个类把入口名对上即可 —— dlsym 的名字完全由本类决定，SDL 并不关心里面叫什么。
 */
public class SDLPluginEntry extends SDLActivity {

    private static final String SDL3_SO = "libSDL3.so";
    private static final String MAIN_SO = "libmain.so";

    private static volatile String sMainLibrary = null;
    private static volatile boolean sTornDown = true;

    @Override
    protected String getMainFunction() {
        // Go 的 cgo //export SDL_main 导出的符号名
        return "SDL_main";
    }

    @Override
    protected String getMainSharedObject() {
        return sMainLibrary;
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "main" };
    }

    /**
     * argv[1] 传给 Go 侧当日志文件路径。
     *
     * <p>插件页面里看不到 native 的 stderr，Go 的 log 默认输出等于石沉大海；
     * 给它一个私有目录下的文件，出问题时可以直接打开看。
     * 对应工程模板 main.go 里的 {@code setupLog(args)}。
     */
    @Override
    protected String[] getArguments() {
        try {
            File log = new File(
                    com.rapid.util.android.EnvUtils.getEnvironmentDir(
                            com.rapid.api.Platform.getApplicationContext(), "linux"),
                    "gosdl3.log");
            return new String[] { log.getAbsolutePath() };
        } catch (Throwable t) {
            return new String[0];
        }
    }

    /**
     * 屏蔽对「未 attach Activity」的操作。
     *
     * <p>原生 Android_CreateWindow() 的调用顺序是：先 Android_JNI_SetOrientation，
     * 再 Android_JNI_GetNativeWindow。前者经 Java setOrientation() ->
     * mSingleton.setOrientationBis() -> setRequestedOrientation()。本类的 mSingleton
     * 是 new 出来、未 attach 的 Activity，token 为空，setRequestedOrientation() 会抛异常；
     * 该异常会成为 pending JNI 异常，导致紧随其后的 Android_JNI_GetNativeWindow()
     * 取不到 Surface -> data->native_window=NULL -> window->internal 保持 NULL ->
     * GLES2 失败回退 Vulkan -> Android_Vulkan_CreateSurface 里空指针崩溃(SIGSEGV)。
     * 故此处做成 no-op。
     */
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        // no-op
    }

    @Override
    public void setRequestedOrientation(int requestedOrientation) {
        // no-op
    }

    @Override
    protected void main() {
        SDLActivity self = SDLActivity.mSingleton;
        try {
            if (self == null) {
                return;
            }
            String library = self.getMainSharedObject();
            String function = self.getMainFunction();
            SDLActivity.nativeRunMain(library, function, self.getArguments());
        } catch (Throwable t) {
        } finally {
            SDLActivity.mSingleton = null;
        }
    }

    public static void install(String mainLibAbsolutePath) {
        sMainLibrary = mainLibAbsolutePath;
        sTornDown = false;
        SDLActivity.mSingleton = new SDLPluginEntry();
    }

    /**
     * 终止 SDL 线程并复位原生生命周期状态。
     *
     * <p>对齐官方 {@code SDLActivity.onDestroy()} 的收尾动作。插件页面不是真实 Activity，
     * 宿主不会执行 SDLActivity.onDestroy()，因此必须在这里手动完成：
     * 向 SDLThread 发送 QUIT -> 等待其结束 -> 释放原生互斥量/信号量。
     *
     * <p>关键：native 侧入口循环只有收到 SDL QUIT 才会退出，退出后进程级单例
     * {@code GetMainLooper()} 才会把 running 复位；否则下次进入时
     * {@code PreScheduleMessage} 会因 looper 仍在运行而抛异常(SIGABRT)。
     */
    public static synchronized void teardown() {
        if (sTornDown) {
            return;
        }
        sTornDown = true;
        try {
            if (SDLActivity.mSDLThread != null) {
                SDLActivity.nativeSendQuit();
                try {
                    SDLActivity.mSDLThread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                SDLActivity.mSDLThread = null;
            }
            SDLActivity.nativeQuit();
        } catch (Throwable t) {
        }
    }

    public static void uninstall() {
        SDLActivity.mSingleton = null;
        SDLActivity.mSurface = null;
        SDLActivity.mIsResumedCalled = false;
        sMainLibrary = null;
        sTornDown = true;
    }

    /** 从 assets 释放 libSDL3.so / libmain.so 到 libDir 后依次加载。 */
    public static boolean loadNative(Context context, String libDir) {
        File dir = new File(libDir);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }

        extractAssetIfNeeded(context, dir, SDL3_SO);
        extractAssetIfNeeded(context, dir, MAIN_SO);

        File sdl3 = new File(dir, SDL3_SO);
        File main = new File(dir, MAIN_SO);

        boolean okSdl3 = trySystemLoad(sdl3);
        if (!okSdl3) {
            try {
                SDL.loadLibrary("SDL3", context);
                okSdl3 = true;
            } catch (Throwable t) {
            }
        }

        boolean okMain = trySystemLoad(main);
        if (!okMain) {
            try {
                SDL.loadLibrary("main", context);
                okMain = true;
            } catch (Throwable t) {
            }
        }

        return okSdl3 && okMain;
    }

    private static void extractAssetIfNeeded(Context context, File dir, String name) {
        File target = new File(dir, name);
        if (target.exists() && target.length() > 0) {
            return;
        }
        try {
            AssetUtils.copyAsset(context, name, target);
        } catch (Throwable t) {
        }
        long len = target.exists() ? target.length() : -1;
        if (len <= 0 && target.exists()) {
            // 0 字节说明这个 context 的 assets 里没有该文件（常见于用错 context：
            // 宿主 IDE 的 assets 里没有插件的 libmain.so）。
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }
    }

    private static boolean trySystemLoad(File lib) {
        if (lib == null || !lib.exists()) {
            return false;
        }
        try {
            System.load(lib.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读取 ELF 头，返回 "name: ELFxx machine"。 */
    public static String describeElf(File f) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            byte[] h = new byte[20];
            int n = raf.read(h);
            if (n < 20) {
                return f.getName() + ": header too small(" + n + ")";
            }
            if ((h[0] & 0xFF) != 0x7F || h[1] != 'E' || h[2] != 'L' || h[3] != 'F') {
                return f.getName() + ": not ELF";
            }
            int eiClass = h[4] & 0xFF;
            String cls = eiClass == 1 ? "ELF32" : (eiClass == 2 ? "ELF64" : "ELF?(" + eiClass + ")");
            int eMachine = (h[18] & 0xFF) | ((h[19] & 0xFF) << 8);
            String mach;
            switch (eMachine) {
                case 0x28: mach = "ARM(armeabi-v7a)"; break;
                case 0xB7: mach = "AArch64(arm64-v8a)"; break;
                case 0x03: mach = "x86"; break;
                case 0x3E: mach = "x86_64"; break;
                default: mach = "machine=0x" + Integer.toHexString(eMachine);
            }
            return f.getName() + ": " + cls + " " + mach;
        } catch (Throwable t) {
            return f.getName() + ": read failed " + t;
        } finally {
            try { if (raf != null) raf.close(); } catch (Throwable ignored) {}
        }
    }

    public static String processAbi() {
        StringBuilder sb = new StringBuilder();
        sb.append("os.arch=").append(System.getProperty("os.arch"));
        if (Build.VERSION.SDK_INT >= 23) {
            sb.append(", is64BitProcess=").append(Process.is64Bit());
        }
        sb.append(", SUPPORTED_ABIS=").append(Arrays.toString(Build.SUPPORTED_ABIS));
        return sb.toString();
    }
}
