package com.linux.plugin.page;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.rapid.android.ui.app.RapidPluginPage;
import com.rapid.api.Platform;
import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.util.android.EnvUtils;

import org.libsdl.app.SDL;
import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLPluginEntry;
import org.libsdl.app.SDLPluginSurface;

import java.io.File;
import com.linux.plugin.task.CompileWithGxxTask;

/**
 * 在插件页面内运行 SDL3 应用。
 *
 * <p>把 classes.jar 里 SDLActivity 的加载机制搬到普通页面:
 * 载入原生库 -> SDL 初始化 -> 建 Surface -> 挂载 -> 触发 SDL 线程。
 */
public class SDLPluginPage extends RapidPluginPage {

    private SDLPluginSurface mSurface;
    private boolean mInstalled;
    private boolean mClosed;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        try {
            // 工程编译产物目录(外置存储)。注意:外置存储禁止 dlopen,
            // 否则 System.load 会报 "is not accessible for the namespace clns-10"。
            File buildDir = new File(Platform.getProject().getTasks().getTask(CompileWithGxxTask.class).getIntermediatePath());
            // 加载目录必须是应用私有目录,只有私有路径才允许 System.load。
            File tmpDir = new File(EnvUtils.getEnvironmentDir(Platform.getApplicationContext(), "linux"), "tmp");
            if (!tmpDir.exists()) {
                tmpDir.mkdirs();
            }
            // 把工程编译出的最新原生库复制到私有目录(存在即覆盖),再从私有目录加载。
            // 若产品目录缺文件,则由 loadNative 回退到插件 assets 释放。
            //copyNativeLib(new File(buildDir, "libmain.so"), new File(tmpDir, "libmain.so"));
            //copyNativeLib(new File(buildDir, "libSDL3.so"), new File(tmpDir, "libSDL3.so"));
            File mainSo = new File(tmpDir, "libmain.so");

            // 1. 载入原生库
            // 注意:libmain.so / libSDL3.so 在“插件”自己的 assets 里,必须用插件 context
            // (AndroidPlugins.getContext) 才能读到;宿主 IDE 的 context 读不到。
            android.content.Context pluginCtx = null;
            try {
                pluginCtx = AndroidPlugins.getContext(this);
            } catch (Throwable t) {
            }
            if (pluginCtx == null) {
                pluginCtx = Platform.getApplicationContext();
            }

            SDLPluginEntry.loadNative(pluginCtx, tmpDir.getAbsolutePath());

            // 2. 初始化 SDL 静态状态
            try {
                SDL.setupJNI();
                SDL.initialize();
                SDL.setContext(Platform.getApplicationContext());
            } catch (Throwable t) {
            }

            // 3. 建视图。SDLPluginPage 是 ContextThemeWrapper,不是真实 Activity;
            //    SurfaceView 必须挂到真实窗口的 Activity,故用 getActivity() 作 context。
            android.app.Activity activity = getActivity();
            if (activity == null) {
                return;
            }

            FrameLayout container = new FrameLayout(activity);
            container.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            mSurface = new SDLPluginSurface(activity);
            container.addView(mSurface, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // 4. 安装静态状态 + 入口
            mSurface.install();
            SDLPluginEntry.install(mainSo.getAbsolutePath());
            mInstalled = true;

            // 5. 挂载
            initialize(container, "SDL3");
        } catch (Throwable t) {
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        resume();
    }

    @Override
    public void onPause() {
        pause();
        super.onPause();
    }

    @Override
    public void onDestroy() {
        closeSdlPage("onDestroy");
        super.onDestroy();
    }

    @Override
    public boolean onBack() {
        // 插件页面不是 Activity,返回键不会自动走 SDLActivity.onDestroy()。
        // 这里直接执行官方的 nativeSendQuit -> join(1000) -> nativeQuit 收尾。
        closeSdlPage("onBack");
        // false 交给宿主继续完成页面返回/销毁流程。
        return false;
    }

    private void closeSdlPage(String reason) {
        if (mClosed) {
            return;
        }
        mClosed = true;
        if (mInstalled) {
            SDLPluginEntry.teardown();
            SDLPluginEntry.uninstall();
            mInstalled = false;
        }
    }

    /** 把工程编译产物复制到应用私有目录;存在则覆盖,保证加载的是最新产物。 */
    private static void copyNativeLib(File src, File dst) {
        if (src == null || !src.exists() || src.length() == 0) {
            return;
        }
        java.io.InputStream in = null;
        java.io.OutputStream out = null;
        try {
            in = new java.io.FileInputStream(src);
            out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (Throwable t) {
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
        }
    }

    private void resume() {
        if (!mInstalled) {
            return;
        }
        try {
            SDLActivity.mNextNativeState = SDLActivity.NativeState.RESUMED;
            SDLActivity.mIsResumedCalled = true;
            SDLActivity.handleNativeState();
        } catch (Throwable t) {
        }
    }

    private void pause() {
        if (!mInstalled) {
            return;
        }
        try {
            SDLActivity.mNextNativeState = SDLActivity.NativeState.PAUSED;
            SDLActivity.mIsResumedCalled = false;
            SDLActivity.handleNativeState();
        } catch (Throwable t) {
        }
    }
}
