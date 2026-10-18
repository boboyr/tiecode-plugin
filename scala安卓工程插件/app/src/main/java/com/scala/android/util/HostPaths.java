package com.scala.android.util;

import android.content.Context;

import com.rapid.api.Platform;
import com.rapid.framework.data.SystemPath;

import java.io.File;

/**
 * 结绳宿主提供的公共路径。
 *
 * <p>宿主自己的 Maven 依赖放在 <b>公共目录</b>而不是 {@code ~/.m2}：
 * <pre>
 *   /sdcard/Tiecode/Maven      （结绳）
 *   /sdcard/QiStudio/Maven     （Studio）
 *   /sdcard/Tiecode/Cache      （AAR 解包等缓存）
 * </pre>
 * 本插件优先复用这份已有仓库（当只读缓存），把自己下载的构件写到同一个目录，
 * 这样"宿主 Java 插件下载过的依赖"和"Scala 工程声明的依赖"共享一份缓存，
 * 用户不会为了同一个 jar 下载两次。
 *
 * <p>public 路径拿不到时（例如宿主裁剪过）回退到插件私有目录。
 */
public final class HostPaths {

    /** 宿主公共 Maven 仓库目录名，与 {@code studio.java.api.PublicPathExt.MAVEN} 一致。 */
    public static final String MAVEN = "Maven";
    /** 宿主 AAR 解包缓存目录名，与 {@code com.rapid.api.ext.file.PublicPath.CACHE} 一致。 */
    public static final String CACHE = "Cache";

    private HostPaths() {
    }

    private static SystemPath systemPath() {
        try {
            return Platform.getSystemPath();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 宿主公共根目录（{@code /sdcard/Tiecode} 之类），拿不到返回 null。 */
    public static File publicRoot() {
        SystemPath path = systemPath();
        if (path == null) {
            return null;
        }
        try {
            String root = path.getPublicRootPath();
            return root == null ? null : new File(root);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 宿主 Maven 仓库目录；不存在时返回可创建的目标路径。 */
    public static File mavenRepository() {
        SystemPath path = systemPath();
        if (path != null) {
            try {
                String value = path.getPublicPath(MAVEN);
                if (value != null && !value.isEmpty()) {
                    return new File(value);
                }
            } catch (Throwable ignored) {
                // 落到下面的兜底
            }
        }
        File root = publicRoot();
        return root == null ? null : new File(root, MAVEN);
    }

    /** 宿主缓存目录（AAR 解包的默认位置）。 */
    public static File cacheDir(Context context) {
        SystemPath path = systemPath();
        if (path != null) {
            try {
                String value = path.getCachePath();
                if (value != null && !value.isEmpty()) {
                    return new File(value);
                }
            } catch (Throwable ignored) {
                // 落到下面的兜底
            }
        }
        return new File(context.getFilesDir(), "cache");
    }

    /** 插件私有工作目录：{@code <filesDir>/env/scala-android}。 */
    public static File pluginWorkDir(Context context) {
        return new File(new File(context.getFilesDir(), "env"), "scala-android");
    }

    /**
     * sbt 依赖下载的本地仓库：
     * 优先用宿主公共 Maven 目录（与 Java 插件共用），拿不到时用插件私有目录。
     */
    public static File resolverCacheDir(Context context) {
        File maven = mavenRepository();
        if (maven != null) {
            return maven;
        }
        return new File(pluginWorkDir(context), "m2");
    }

    /** AAR 解包目录。 */
    public static File aarUnpackDir(Context context) {
        return new File(pluginWorkDir(context), "aar");
    }
}
