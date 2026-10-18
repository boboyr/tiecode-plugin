package com.dsh.nomount;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;

/**
 * 给未安装 APK 用的 Context：资源/Assets/ClassLoader 全部指向目标 APK，
 * 其它系统服务仍走宿主（结绳）进程，保证不越权、也不需要新进程。
 */
public class TargetContext extends ContextWrapper {

    private final Resources resources;
    private final ClassLoader classLoader;
    private final String packageName;
    private Resources.Theme theme;

    public TargetContext(Context host, Resources resources, ClassLoader classLoader, String packageName) {
        super(host);
        this.resources = resources;
        this.classLoader = classLoader;
        this.packageName = packageName;
    }

    @Override
    public Resources getResources() {
        return resources;
    }

    @Override
    public AssetManager getAssets() {
        return resources.getAssets();
    }

    @Override
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    /**
     * 必须返回**目标 APK 资源**的主题。
     *
     * <p>Activity 的 {@code setTheme()} 会先取 {@code getBaseContext().getTheme()} 再
     * {@code Theme.setTo()}，而 {@code setTo()} 连 AssetManager 一起复制。若这里给的是宿主主题，
     * 目标 APK 的 style id 就会被拿去宿主的 AssetManager 里查，直接
     * {@code Resources$NotFoundException}，目标主题失效、窗口退回宿主样式（表现就是黑屏/空窗口）。
     */
    @Override
    public Resources.Theme getTheme() {
        if (theme == null) {
            theme = resources.newTheme();
        }
        return theme;
    }

    // 注意：这里**故意不覆写 getPackageName()**。
    // 目标 APK 的包并没有真正安装，真实 UID 仍是宿主（结绳）的；一旦把包名伪装成目标包，
    // 系统窗口服务会校验「包名是否属于该 UID」，直接抛
    //   SecurityException: Package xxx not in UID yyy
    // 于是目标应用里任何 Toast/弹窗都会把主线程打崩、界面变黑。
    // 所以对外身份一律保持宿主，只有资源与类加载器用目标 APK 的。

    @Override
    public Context getApplicationContext() {
        return this;
    }
}
