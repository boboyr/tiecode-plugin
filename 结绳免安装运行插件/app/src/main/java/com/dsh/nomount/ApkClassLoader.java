package com.dsh.nomount;

import dalvik.system.DexClassLoader;

/**
 * 目标 APK 的类加载器：**子优先**。
 *
 * <p>这一点是免安装/插件化的关键：宿主体积很大，里面通常也打包了
 * androidx / material / kotlin 等同名库。若按默认的父优先（DexClassLoader 的行为），
 * 目标 APK 里的 {@code com.google.android.material.button.MaterialButton} 会解析到
 * 宿主那一份，而宿主的 {@code R.attr} 资源 id 与目标 APK 的 resources 对不上，
 * 于是 Material 的主题校验失败、界面直接画不出来（全黑）。
 *
 * <p>因此除系统/语言基础包外，一律先到目标 APK 自己的 dex 里找，找不到再回退宿主。
 */
public class ApkClassLoader extends DexClassLoader {

    public ApkClassLoader(String dexPath, String optimizedDirectory, String librarySearchPath,
                          ClassLoader parent) {
        super(dexPath, optimizedDirectory, librarySearchPath, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // 注意：Android 的 ClassLoader 没有 JDK 的 getClassLoadingLock()，这里只能用 this 当锁。
        synchronized (this) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (mustUseHost(name)) {
                    loaded = super.loadClass(name, resolve);
                } else {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException notInApk) {
                        loaded = super.loadClass(name, resolve);
                    }
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    /** 只能由系统/宿主提供的包，必须交给父加载器，避免自己造出一份影子实现。 */
    private static boolean mustUseHost(String name) {
        return name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("android.")
                || name.startsWith("dalvik.")
                || name.startsWith("libcore.")
                || name.startsWith("sun.")
                || name.startsWith("com.android.")
                || name.startsWith("org.apache.http.")
                || name.startsWith("org.w3c.dom.")
                || name.startsWith("org.xml.sax.")
                || name.startsWith("org.json.");
    }
}
