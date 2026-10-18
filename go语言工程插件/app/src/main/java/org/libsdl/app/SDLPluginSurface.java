package org.libsdl.app;

import android.content.Context;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.SurfaceHolder;

/**
 * 供插件页面(非 Activity)使用的 SDL 渲染表面。
 *
 * <p>严格对齐官方 {@link SDLSurface} 的启动时机:SDL 线程在 {@code surfaceChanged()}
 * 里启动(此时已 nativeSetScreenResolution,窗口尺寸正确;且 mIsSurfaceReady=true)。
 *
 * <p>启动 SDL 线程的条件(见 SDLActivity.handleNativeState() 的 RESUMED 分支):
 * <pre>
 *   mSurface != null &amp;&amp; mSurface.mIsSurfaceReady &amp;&amp; (mHasFocus || mHasMultiWindow)
 *     &amp;&amp; mIsResumedCalled &amp;&amp; mSDLThread == null
 * </pre>
 * 全部满足才会 new SDLMain() 并 start()。因此必须在调用 handleNativeState() 之前
 * 把 mIsSurfaceReady 置 true;调用后以 mSDLThread 是否非空判断是否真的启动成功,
 * 未成功则不置 mSdlStarted,留给后续回调重试。
 */
public class SDLPluginSurface extends SDLSurface {

    public interface OnSurfaceReadyListener {
        void onSurfaceReady();
    }

    private OnSurfaceReadyListener mListener;
    private boolean mReadyNotified;
    /** SDL 线程是否真的已启动(以 mSDLThread != null 为准)。 */
    private boolean mSdlStarted;

    public SDLPluginSurface(Context context) {
        super(context);
    }

    public void setOnSurfaceReadyListener(OnSurfaceReadyListener listener) {
        mListener = listener;
    }

    /** 安装到 SDL 静态状态,使原生 getNativeSurface() 能取到,并满足 mHasFocus/mIsResumedCalled。 */
    public void install() {
        SDLActivity.mSurface = this;
        SDLActivity.mIsResumedCalled = true;
        SDLActivity.mHasFocus = true;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        super.surfaceCreated(holder);
        // 与官方一致:此处只通知原生 surface created,不启动 SDL 线程。
        // 线程在 surfaceChanged() 里启动(那时窗口尺寸已上报)。
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mSdlStarted = false;
        mIsSurfaceReady = false;
        super.surfaceDestroyed(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mWidth = width;
        mHeight = height;
        int nDeviceWidth = width;
        int nDeviceHeight = height;
        float density = 1.0f;
        try {
            if (Build.VERSION.SDK_INT >= 17) {
                DisplayMetrics realMetrics = new DisplayMetrics();
                mDisplay.getRealMetrics(realMetrics);
                nDeviceWidth = realMetrics.widthPixels;
                nDeviceHeight = realMetrics.heightPixels;
                density = (float) realMetrics.densityDpi / 160.0f;
            }
        } catch (Throwable ignored) {
        }

        try {
            Context ctx = SDLActivity.getContext();
            if (ctx != null) {
                synchronized (ctx) {
                    ctx.notifyAll();
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            SDLActivity.nativeSetScreenResolution(width, height, nDeviceWidth, nDeviceHeight, density,
                    mDisplay.getRefreshRate());
            SDLActivity.onNativeResize();
            SDLActivity.onNativeSurfaceChanged();
        } catch (Throwable t) {
        }

        // 与官方一致:先置 mIsSurfaceReady,再启动 SDL 线程。
        mIsSurfaceReady = true;

        tryStartSdl(holder);
    }

    /**
     * 在 surface 有效时启动 SDL 线程。
     *
     * <p>注意:handleNativeState() 的 RESUMED 分支要求 mSurface.mIsSurfaceReady==true,
     * 因此这里先把 mIsSurfaceReady 置 true;调用后以 mSDLThread 是否非空判断是否成功,
     * 未成功则不置 mSdlStarted,留给下一次回调重试。
     */
    private void tryStartSdl(SurfaceHolder holder) {
        if (mSdlStarted) {
            return;
        }

        Surface surface = holder == null ? null : holder.getSurface();
        boolean valid = surface != null && surface.isValid();
        if (!valid) {
            return;
        }

        // 满足 handleNativeState() 启动线程的前置条件。
        mIsSurfaceReady = true;
        SDLActivity.mIsResumedCalled = true;
        SDLActivity.mHasFocus = true;

        try {
            // onResume 可能已把 mCurrentNativeState 推到 RESUMED(surface 未就绪、未启动),
            // 若不重置,handleNativeState() 会因 mNext==mCurrent 直接 return。
            SDLActivity.mCurrentNativeState = SDLActivity.NativeState.INIT;
            SDLActivity.mNextNativeState = SDLActivity.NativeState.RESUMED;
            SDLActivity.handleNativeState();

            if (SDLActivity.mSDLThread != null) {
                mSdlStarted = true;
            }
        } catch (Throwable t) {
        }

        if (mSdlStarted && !mReadyNotified) {
            mReadyNotified = true;
            if (mListener != null) {
                mListener.onSurfaceReady();
            }
        }
    }
}
