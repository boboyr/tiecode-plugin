package com.scala.android.tool;

import android.content.Context;

import com.rapid.api.Platform;
import com.rapid.api.framework.ServiceKeys;
import com.rapid.api.framework.project.toolchain.ToolchainService;
import com.rapid.api.project.toolchain.Tool;
import com.rapid.api.project.toolchain.android.sdk.AndroidSdk;
import com.rapid.util.android.AssetUtils;
import com.rapid.util.android.EnvUtils;
import com.rapid.util.file.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Scala 工具链（标准库 jar / 运行时 dex）的安装与定位。
 *
 * <h3>为什么需要"解压"而不是直接用 assets</h3>
 * Scala 编译器需要把 {@code scala-library-3.8.1.jar} 当作 <b>classpath 里的 .class 归档</b>
 * 去读取（解析 scala.Predef、scala.collection.* 等符号）。assets 里的条目不是文件系统
 * 路径，编译器无法把它们当 classpath 用；D8 也需要 jar/dex 的实体文件。
 * 所以首次使用时把 assets 里的 jar 复制到
 * {@code <app filesDir>/env/scala/lib/} 下，之后编译与 dex 都复用这一份。
 *
 * <p>目录里写了 {@code .version} 标记：换 Scala 版本（或用户手工装过旧版本）时，
 * 标记不匹配会自动整目录重来，避免新旧 jar 混在一起产生莫名其妙的符号错误。
 */
public final class ScalaToolchain {

    /** 与插件打包的编译器版本保持一致。 */
    public static final String VERSION = ScalaCompileApiKeys.VERSION;

    /** 用户代码编译/运行都需要这两个 jar（scala3-library 在 3.8 里已并入 scala-library）。 */
    public static final String[] LIB_JAR_NAMES = {
            "scala-library-3.8.1.jar",
            "scala3-library_3-3.8.1.jar"
    };

    /**
     * <b>只用于编译</b>的存根 jar。
     *
     * <p>Android 的 android.jar 是公开 SDK 存根，不含 JDK9+ 的隐藏类
     * {@code java.lang.invoke.StringConcatFactory}（字符串拼接 invokedynamic 的引导类）
     * 与 {@code LambdaMetafactory}；而设备运行时的 core-oj 里是有的。
     * Scala 3 后端在生成字符串拼接时会解析 StringConcatFactory，缺了就会报
     * <pre>
     * assertion failed: Bad superClass for class StringConcatFactory
     * Bad symbolic reference. A signature refers to StringConcatFactory/T ...
     * </pre>
     * 所以编译 classpath 里补一份同名存根。它<b>绝不能进 dex/运行时</b>：
     * 那会和设备引导类路径里的同名类打架。
     */
    public static final String[] STUB_JAR_NAMES = {
            "android-hidden-stubs.jar"
    };


    private static final String ASSET_LIB_DIR = "scala-toolchain/lib";
    private static final String VERSION_FILE = ".version";

    /**
     * env 目录的标记版本：编译器版本 + 工具链修订号。
     * 修订号变化（例如新增了 android-hidden-stubs.jar）会让老安装自动重装，
     * 不需要用户去清应用数据。
     */
    private static final String ENV_VERSION = VERSION + "-r2";
    private static final String RUNTIME_DEX_MARKER = ".runtime-dex-ok";
    private static final String RUNTIME_DEX_DIR = "runtime-dex";

    /** Android SDK 只在第一次拿不到 android.jar 时尝试安装一次。 */
    private static volatile boolean sdkChecked = false;

    /**
     * 「工程附加 classpath」钩子。
     *
     * <p>控制台插件只需要 android.jar + scala 标准库；安卓工程还要把 build.sbt 里
     * 声明的依赖（sbt 解析器下载/解包出来的 jar 与 AAR classes.jar）也放进补全用的
     * classpath，这样 {@code OkHttpClient} 之类的类名也能补全并自动 import。
     * 由 {@code ScalaAndroidProject} 在解析完 build.sbt 后设置。
     */
    public interface ExtraClasspathProvider {
        List<File> extraClasspath();
    }

    private static volatile ExtraClasspathProvider extraClasspathProvider;

    /** 设置/清除工程附加 classpath（传 null 表示清除）。 */
    public static void setExtraClasspathProvider(ExtraClasspathProvider provider) {
        extraClasspathProvider = provider;
    }

    private ScalaToolchain() {
    }

    /** {@code <app filesDir>/env/scala} */
    public static File envDir(Context context) {
        return EnvUtils.getEnvironmentDir(context, "scala");
    }

    /** 标准库 jar 所在目录。 */
    public static File libDir(Context context) {
        return new File(envDir(context), "lib");
    }

    public static File libJar(Context context, String name) {
        return new File(libDir(context), name);
    }

    /** 标准库 jar 是否已经就位且版本匹配。 */
    public static boolean isInstalled(Context context) {
        try {
            File versionFile = new File(envDir(context), VERSION_FILE);
            if (!versionFile.isFile()) {
                return false;
            }
            String installed = FileUtils.readFile(versionFile);
            if (installed == null || !ENV_VERSION.equals(installed.trim())) {
                return false;
            }
            for (String name : allJarNames()) {
                File jar = libJar(context, name);
                if (!jar.isFile() || jar.length() <= 0) {
                    return false;
                }
            }
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** 把 assets 里的标准库 jar 复制到 env 目录；已装且版本一致时直接返回。 */
    public static synchronized void install(Context context) throws IOException {
        if (isInstalled(context)) {
            return;
        }
        File dir = libDir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建 Scala 库目录: " + dir);
        }
        for (String name : allJarNames()) {
            File target = new File(dir, name);
            // AssetUtils.copyAsset 会吞异常，这里靠"复制后体积是否为 0"兜底。
            AssetUtils.copyAsset(context, ASSET_LIB_DIR + "/" + name, target);
            if (!target.isFile() || target.length() <= 0) {
                throw new IOException("复制 Scala 库失败: " + name);
            }
        }
        FileUtils.writeFile(ENV_VERSION, new File(envDir(context), VERSION_FILE));
    }

    /** 清空 env 目录重新复制（供「重装 Scala 工具链」菜单使用）。 */
    public static synchronized void reinstall(Context context) throws IOException {
        FileUtils.delete(envDir(context));
        install(context);
    }

    /**
     * 编译用户代码时的 classpath：
     * android.jar（如果宿主装了 Android SDK）+ scala 标准库 jar。
     */
    public static List<File> compileClasspath(Context context) {
        List<File> classpath = new ArrayList<>();
        File androidJar = ensureAndroidJar();
        if (androidJar != null) {
            classpath.add(androidJar);
        }
        for (String name : STUB_JAR_NAMES) {
            File jar = libJar(context, name);
            if (jar.isFile()) {
                classpath.add(jar);
            }
        }
        for (String name : LIB_JAR_NAMES) {
            File jar = libJar(context, name);
            if (jar.isFile()) {
                classpath.add(jar);
            }
        }
        ExtraClasspathProvider provider = extraClasspathProvider;
        if (provider != null) {
            try {
                List<File> extra = provider.extraClasspath();
                if (extra != null) {
                    for (File file : extra) {
                        if (file != null && file.exists() && !classpath.contains(file)) {
                            classpath.add(file);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 补全用的 classpath 拿不到不影响编译。
            }
        }
        return classpath;
    }

    /** 需要从 assets 复制到 env 目录的全部 jar。 */
    private static List<String> allJarNames() {
        List<String> names = new ArrayList<>(LIB_JAR_NAMES.length + STUB_JAR_NAMES.length);
        for (String name : LIB_JAR_NAMES) {
            names.add(name);
        }
        for (String name : STUB_JAR_NAMES) {
            names.add(name);
        }
        return names;
    }

    /** 运行时需要 dex 进控制台程序的 jar。 */
    public static List<File> runtimeJars(Context context) {
        List<File> jars = new ArrayList<>();
        for (String name : LIB_JAR_NAMES) {
            File jar = libJar(context, name);
            if (jar.isFile()) {
                jars.add(jar);
            }
        }
        return jars;
    }

    /** 标准库 dex 缓存目录（按版本区分，换版本自动重编）。 */
    public static File runtimeDexDir(Context context) {
        return new File(envDir(context), RUNTIME_DEX_DIR + "-" + VERSION);
    }

    public static boolean isRuntimeDexReady(Context context) {
        File dir = runtimeDexDir(context);
        if (!new File(dir, RUNTIME_DEX_MARKER).isFile()) {
            return false;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            if (child.getName().endsWith(".dex") && child.length() > 0) {
                return true;
            }
        }
        return false;
    }

    public static void markRuntimeDexReady(Context context) {
        try {
            FileUtils.writeFile("ok", new File(runtimeDexDir(context), RUNTIME_DEX_MARKER));
        } catch (IOException ignored) {
            // 标记写不出来只会导致下次重新 dex 一遍，不影响正确性。
        }
    }

    /** 宿主的 Android SDK 里的 android.jar；没装则返回 null。 */
    public static File androidJar() {
        String path = sdkPath(true);
        if (path == null) {
            return null;
        }
        File jar = new File(path);
        return jar.isFile() ? jar : null;
    }

    /**
     * 拿 android.jar；没装就尝试让宿主装一次 Android SDK 工具链（只尝试一次）。
     *
     * <p>不装 SDK 也能编译纯 Scala 程序，只是 classpath 里少了 Android API，
     * 而且 D8 脱糖需要 core-lambda-stubs。所以这里"尽力而为"：
     * 装了用户就能在控制台程序里直接调 Android API，装不上也不阻断构建。
     */
    public static File ensureAndroidJar() {
        File jar = androidJar();
        if (jar != null) {
            return jar;
        }
        synchronized (ScalaToolchain.class) {
            jar = androidJar();
            if (jar != null) {
                return jar;
            }
            if (sdkChecked) {
                return null;
            }
            sdkChecked = true;
            try {
                ToolchainService service = Platform.getService(ServiceKeys.TOOLCHAIN);
                if (service == null) {
                    return null;
                }
                AndroidSdk sdk = service.getTool(Tool.ANDROID_SDK);
                if (sdk == null) {
                    return null;
                }
                service.checkToolAndInstall(sdk, new Runnable() {
                    @Override
                    public void run() {
                        ScalaBuildLog.append(Platform.getApplicationContext(),
                                "首次构建：正在安装 Android SDK 工具链（android.jar / core-lambda-stubs）...");
                    }
                });
                jar = androidJar();
            } catch (Throwable ignored) {
                // 装不上就算了，纯 Scala 程序照样能编译运行。
            }
            return jar;
        }
    }

    /** D8 脱糖需要的 core-lambda-stubs.jar；取不到则返回 null。 */
    public static File lambdaCoreJar() {
        String path = sdkPath(false);
        if (path == null) {
            return null;
        }
        File jar = new File(path);
        return jar.isFile() ? jar : null;
    }

    private static String sdkPath(boolean androidJar) {
        try {
            ToolchainService service = Platform.getService(ServiceKeys.TOOLCHAIN);
            if (service == null) {
                return null;
            }
            AndroidSdk sdk = service.getTool(Tool.ANDROID_SDK);
            if (sdk == null) {
                return null;
            }
            return androidJar ? sdk.getAndroidJarPath() : sdk.getLambdaCorePath();
        } catch (Throwable ignore) {
            return null;
        }
    }

    public static List<File> asList(File... files) {
        return new ArrayList<>(Arrays.asList(files));
    }
}
