package com.scala.android.androidproject;

import com.rapid.api.Platform;
import com.rapid.api.logging.Logger;
import com.rapid.api.project.CodeProject;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.scala.android.dependency.SbtDependencyResolver;
import com.scala.android.dependency.SbtResolutionResult;
import com.scala.android.run.CleanRunConfiguration;
import com.scala.android.run.DebugPluginConfiguration;
import com.scala.android.run.BuildPluginConfiguration;
import com.scala.android.run.PackAndRunConfiguration;
import com.scala.android.run.SbtResolveRunConfiguration;
import com.scala.android.sbt.SbtAndroidConfig;
import com.scala.android.sbt.SbtBuild;
import com.scala.android.sbt.SbtBuildScriptLoader;
import com.scala.android.sbt.TiecodePluginKeys;
import com.scala.android.setting.ScalaAndroidSettings;
import com.scala.android.task.Aapt2CompileResTask;
import com.scala.android.task.BuildApkTask;
import com.scala.android.task.BuildPluginTask;
import com.scala.android.task.CompileJavaSourcesTask;
import com.scala.android.task.CompileRJavaTask;
import com.scala.android.task.InstallApkTask;
import com.scala.android.task.InstallPluginTask;
import com.scala.android.task.CompileScalaTask;
import com.scala.android.task.DexTask;
import com.scala.android.task.LinkResTask;
import com.scala.android.task.MergeManifestTask;
import com.scala.android.task.PackageApkTask;
import com.scala.android.task.SbtResolveTask;
import com.scala.android.task.SignApkTask;
import com.scala.android.task.ZipAlignApkTask;
import com.scala.android.tool.ScalaToolchain;
import com.scala.android.util.HostPaths;

import java.io.File;
import java.util.Collections;

/**
 * Scala 安卓工程。
 *
 * <h3>工程结构（与 {@code scala-android-app-modern} 一致）</h3>
 * <pre>
 * build.sbt                       构建脚本（本插件自研解析器解析）
 * project/build.properties        sbt.version
 * project/plugins.sbt             addSbtPlugin("…sbt-modern-android"…)
 * src/main/scala/**              Scala 源码
 * src/main/res/**                资源
 * src/main/assets/**             assets
 * src/main/jniLibs/**            .so
 * src/main/AndroidManifest.xml   清单
 * lib/*.jar                      sbt 的 unmanaged 本地依赖
 * </pre>
 *
 * <h3>任务链（全部用宿主工具链，写法对齐结绳本体）</h3>
 * <pre>
 * sbtSync（解析 build.sbt）
 *   └─ sbtResolve（依赖解析下载 / AAR 解包）
 *        ├─ compileScalaSources（Scala 3 编译器）
 *        ├─ compileProjectResources（aapt2 compile）
 *        └─ mergeManifest（宿主 ManifestMerger）
 *              └─ linkAppResources（aapt2 link → res.ap_ + R.java）
 *                   └─ compileJavaSources（宿主 javac：src/main/java + R.java）
 *                        └─ dexWithD8（宿主 D8）
 *                             └─ packageApk（宿主 ApkGenerator）
 *                                  └─ signApk（宿主 ApkSigner）
 *                                       └─ zipAlignApk（宿主 zipalign）
 *                                            └─ buildApk（产物：build/outputs/apk/<variant>/<name>.apk）
 * </pre>
 */
public class ScalaAndroidProject extends CodeProject {

    public static final String BUILD_FILE = "build.sbt";
    public static final String PROJECT_DIR = "project";

    /** 解析结果（build.sbt）。 */
    private volatile SbtBuild sbtBuild;
    /** 由解析结果推导出的 Android 配置。 */
    private volatile SbtAndroidConfig androidConfig;
    /** 依赖解析结果（含 AAR 解包）。 */
    private volatile SbtResolutionResult resolution;
    /** 解析/下载是否已经在后台跑过（补全用的轻量解析）。 */
    private volatile boolean indexedForCompletion;

    // ------------------------------------------------------------ 目录

    public File buildDir() {
        return getChildFile(Project.DIR_BUILD);
    }

    public File intermediatesDir() {
        return new File(buildDir(), "intermediates");
    }

    public File outputsDir() {
        return new File(buildDir(), "outputs");
    }

    public File apkOutputDir() {
        ProjectVariant variant = getVariant();
        String name = variant == null ? "release" : variant.getName();
        return new File(new File(outputsDir(), "apk"), name);
    }

    /** 「构建结绳插件」产物目录：{@code build/outputs/plugins/<variant>/}。 */
    public File pluginOutputDir() {
        ProjectVariant variant = getVariant();
        String name = variant == null ? "release" : variant.getName();
        return new File(new File(outputsDir(), "plugins"), name);
    }

    public File buildScript() {
        return getChildFile(BUILD_FILE);
    }

    // ------------------------------------------------------------ 模型

    /** 解析 build.sbt（结果缓存）。 */
    public synchronized SbtBuild loadBuild() {
        if (sbtBuild == null) {
            sbtBuild = SbtBuildScriptLoader.load(getProjectDir());
            if (sbtBuild != null) {
                androidConfig = SbtAndroidConfig.from(sbtBuild);
            }
        }
        return sbtBuild;
    }

    public SbtBuild getSbtBuild() {
        return sbtBuild;
    }

    /** 由 {@code sbtSync} 任务写入。 */
    public void setSbtBuild(SbtBuild build) {
        this.sbtBuild = build;
    }

    /** 由 {@code sbtSync} 任务写入。 */
    public void setAndroidConfig(SbtAndroidConfig config) {
        this.androidConfig = config;
        // build.sbt 解析完才知道是不是结绳插件工程（tiecodePlugin := true）
        syncPluginRunConfigurations();
    }

    /**
     * 按 {@code tiecodePlugin} 增删「构建结绳插件 / 调试结绳插件」两条运行配置。
     *
     * <p>幂等：只有插件工程才有这两条；普通 Scala 安卓工程（产物是 APK）如果被误加过，
     * 这里会删掉。配置还没解析出来（{@code androidConfig == null}）时按"不是插件工程"处理，
     * 等 {@link #setAndroidConfig} 再补。
     */
    public void syncPluginRunConfigurations() {
        try {
            com.rapid.api.project.component.running.RunManager manager =
                    getComponent(com.rapid.api.project.component.running.RunManager.class);
            if (manager == null) {
                return;
            }
            boolean plugin;
            if (androidConfig != null) {
                plugin = androidConfig.isTiecodePlugin;
            } else if (getProjectDir() == null) {
                // 连工程目录都还没有，判断不了是不是插件工程：先按插件工程给上
                // （新建的插件工程要能立刻点「构建结绳插件」），等解析出 build.sbt 再校正。
                plugin = true;
            } else {
                plugin = false;
            }
            boolean has = manager.getRunConfiguration(BuildPluginConfiguration.ID) != null;
            if (plugin) {
                if (!has) {
                    manager.addRunConfiguration(new BuildPluginConfiguration(this));
                    manager.addRunConfiguration(new DebugPluginConfiguration(this));
                }
            } else if (has) {
                manager.removeRunConfiguration(BuildPluginConfiguration.ID);
                manager.removeRunConfiguration(DebugPluginConfiguration.ID);
            }
        } catch (Throwable ignored) {
            // 运行配置增减失败不影响构建本身
        }
    }

    public SbtAndroidConfig getAndroidConfig() {
        if (androidConfig == null) {
            loadBuild();
        }
        return androidConfig;
    }

    public SbtResolutionResult getResolution() {
        return resolution;
    }

    public void setResolution(SbtResolutionResult resolution) {
        this.resolution = resolution;
    }

    public void invalidateBuild() {
        sbtBuild = null;
        androidConfig = null;
    }

    /** 供任务使用：没有 build.sbt 时给出可读错误。 */
    public SbtBuild requireBuild() {
        SbtBuild build = loadBuild();
        return build;
    }

    public SbtAndroidConfig requireAndroidConfig() {
        return getAndroidConfig();
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    public void prepare() {
        // 1) 任务链（注册顺序 = 执行顺序，日志读起来就是构建流程）
        //    「解析 build.sbt」不单独占一条任务：它并进「解析并下载依赖」里，
        //    免得构建日志里多出一行与打包无关的任务。
        SbtResolveTask sbtResolve = new SbtResolveTask();
        Aapt2CompileResTask compileRes = new Aapt2CompileResTask();
        MergeManifestTask mergeManifest = new MergeManifestTask();
        LinkResTask linkRes = new LinkResTask();
        CompileRJavaTask compileRJava = new CompileRJavaTask();
        CompileScalaTask compileScala = new CompileScalaTask();
        CompileJavaSourcesTask compileJava = new CompileJavaSourcesTask();
        DexTask dex = new DexTask();
        PackageApkTask packageApk = new PackageApkTask();
        SignApkTask signApk = new SignApkTask();
        ZipAlignApkTask zipAlign = new ZipAlignApkTask();
        BuildApkTask buildApk = new BuildApkTask();
        InstallApkTask installApk = new InstallApkTask();
        // 结绳插件工程额外两条任务：把 .apk 改后缀成 .rmx、装进结绳宿主 Programs 目录并重启宿主
        BuildPluginTask buildPlugin = new BuildPluginTask();
        InstallPluginTask installPlugin = new InstallPluginTask();

        compileRes.dependsOn(sbtResolve);
        mergeManifest.dependsOn(sbtResolve);
        linkRes.dependsOn(compileRes);
        linkRes.dependsOn(mergeManifest);
        // R 类必须在 Scala/Java 编译之前就位（源码里到处是 R.layout/R.id）
        compileRJava.dependsOn(linkRes);
        compileScala.dependsOn(sbtResolve);
        compileScala.dependsOn(compileRJava);
        compileJava.dependsOn(compileScala);
        compileJava.dependsOn(compileRJava);
        dex.dependsOn(compileJava);
        packageApk.dependsOn(dex);
        // 顺序与结绳本体一致：生成APK → 对齐 → 签名（zipalign 必须在 apksigner 之前）
        zipAlign.dependsOn(packageApk);
        signApk.dependsOn(zipAlign);
        buildApk.dependsOn(signApk);
        installApk.dependsOn(buildApk);
        // 结绳插件：buildPlugin 收口 rmx，installPlugin 装进宿主并重启
        buildPlugin.dependsOn(buildApk);
        installPlugin.dependsOn(buildPlugin);

        addTask(sbtResolve);
        addTask(compileRes);
        addTask(mergeManifest);
        addTask(linkRes);
        addTask(compileRJava);
        addTask(compileScala);
        addTask(compileJava);
        addTask(dex);
        addTask(packageApk);
        addTask(zipAlign);
        addTask(signApk);
        addTask(buildApk);
        addTask(installApk);
        addTask(buildPlugin);
        addTask(installPlugin);

        // 2) 运行配置：名字与行为对齐结绳本体的 DebugRunConfig / ReleaseRunConfig
        addRunConfiguration(new PackAndRunConfiguration(this, ProjectVariant.DEBUG));
        addRunConfiguration(new PackAndRunConfiguration(this, ProjectVariant.RELEASE));
        addRunConfiguration(new SbtResolveRunConfiguration(this));
        addRunConfiguration(new CleanRunConfiguration(this));
        // 「构建结绳插件 / 调试结绳插件」只有结绳插件工程才该有：普通 Scala 安卓工程
        // 产物是 APK，挂这两条只会让人点错。这里先不看配置（prepare 时 build.sbt 未必读到），
        // 先解析一遍 build.sbt（小文件、不联网），这样下面就能立刻按 tiecodePlugin 决定
        // 加还是不加「构建结绳插件 / 调试结绳插件」；工程目录还没就绪时 sync 内部会先给上。
        try {
            getAndroidConfig();
        } catch (Throwable ignored) {
            // 解析失败就当普通工程，等 SbtResolveTask 起来再校正
        }
        syncPluginRunConfigurations();

        // 3) 代码补全：让 Scala 补全能用上 sbt 依赖里的类
        installCompletionClasspath();
    }

    /**
     * 把本工程的 classpath 接给 Scala 补全（自动 import 用），
     * 并在后台做一次"离线"解析：只用本地已有缓存，不联网、不阻塞打开工程。
     *
     * <p>幂等：可以从 {@code prepare()} 与工程加载完成后再各调一次
     * （{@code prepare()} 时 projectDir 可能还没设置，这一轮会被跳过）。
     */
    public synchronized void installCompletionClasspath() {
        ScalaToolchain.setExtraClasspathProvider(() -> {
            SbtResolutionResult result = resolution;
            return result == null ? Collections.<File>emptyList() : result.classpath();
        });
        if (indexedForCompletion || getProjectDir() == null) {
            return;
        }
        indexedForCompletion = true;
        Thread thread = new Thread(() -> {
            try {
                SbtBuild build = loadBuild();
                if (build == null) {
                    return;
                }
                SbtAndroidConfig config = SbtAndroidConfig.from(build);
                if (config.dependencies.isEmpty() && config.unmanagedLibDir == null) {
                    return;
                }
                SbtDependencyResolver.Options options = completionOptions();
                SbtDependencyResolver resolver = new SbtDependencyResolver(options, null);
                resolution = resolver.resolve(config);
            } catch (Throwable ignored) {
                // 补全用的 classpath 拿不到不影响其它功能。
            }
        }, "scala-android-completion-index");
        thread.setDaemon(true);
        thread.start();
    }

    private SbtDependencyResolver.Options completionOptions() {
        SbtDependencyResolver.Options options = new SbtDependencyResolver.Options();
        options.offline = true;               // 打开工程时不联网
        options.includeTest = false;
        options.cacheDir = HostPaths.resolverCacheDir(context());
        options.unpackDir = HostPaths.aarUnpackDir(context());
        options.extraRepositories.addAll(ScalaAndroidSettings.extraRepositories());
        return options;
    }

    private android.content.Context context() {
        try {
            return com.rapid.api.ext.plugin.AndroidPlugins.getContext(this);
        } catch (Throwable ignored) {
            return Platform.getApplicationContext();
        }
    }

    @Override
    public void recycle() {
        ScalaToolchain.setExtraClasspathProvider(null);
        super.recycle();
    }

    /** 打日志用的一行工程摘要。 */
    public String describe() {
        SbtAndroidConfig config = getAndroidConfig();
        if (config == null) {
            return getName() + "（没有 build.sbt）";
        }
        return config.summary();
    }

    /** 供任务在日志里显示 sbt 版本。 */
    public String sbtVersion() {
        SbtBuild build = getSbtBuild();
        return build == null ? null : build.sbtVersion;
    }

    /** 让日志器可用（任务里用到）。 */
    public Logger logger() {
        return getLogger();
    }
}
