package com.scala.android.task;

import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.logging.Logger;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.task.Task;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.rapid.api.project.toolchain.Tool;
import com.rapid.api.project.toolchain.android.d8.D8CompileParams;
import com.rapid.api.project.toolchain.android.d8.D8Compiler;
import com.rapid.api.project.toolchain.android.sdk.AndroidSdk;
import com.scala.android.androidproject.ScalaAndroidProject;
import com.scala.android.dependency.SbtResolutionResult;
import com.scala.android.sbt.SbtAndroidConfig;
import com.scala.android.tool.ScalaToolchain;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 「编译到Dex(D8)」：把 R 类 / Scala / Java 的 class、依赖 jar 和 <b>Scala 运行库</b>
 * 一起 dex 成 {@code classes.dex}（多 dex 时自动 {@code classes2.dex} …）。
 *
 * <h3>两个关键点</h3>
 * <ol>
 *   <li><b>programFiles 必须是"一个个 .class 文件或 jar"，不能是目录。</b>
 *       宿主内置的 D8 对目录输入会直接报
 *       {@code com.android.tools.r8.internal.ax: Unsupported source file type}；
 *       结绳本体也是这么做的（{@code DexClassWithD8Task} 用 {@code getClassFiles()}
 *       递归收集 .class 再当作 program 输入，目录只作 classpath）；</li>
 *   <li><b>Scala 运行库必须进 dex</b>（scala-library / scala3-library），
 *       否则 APK 一运行就是 {@code NoClassDefFoundError: scala/...}。
 *       编译期的 {@code android-hidden-stubs.jar} 则绝不能进 dex。</li>
 * </ol>
 *
 * <p>library files 给 android.jar 与 core-lambda-stubs.jar（脱糖需要）。
 */
public class DexTask extends ScalaAndroidTask {

    public static final String ID = "dexWithD8";
    public static final String NAME = "编译到Dex(D8)";

    @Override
    public void prepare(Project p, ProjectVariant variant, Logger logger) {
        super.prepare(p, variant, logger);
        List<File> inputs = new ArrayList<>();
        if (project != null) {
            for (String id : new String[]{CompileRJavaTask.ID, CompileScalaTask.ID,
                    CompileJavaSourcesTask.ID}) {
                Task task = project.getTasks().getTask(id);
                if (task != null) {
                    inputs.add(new File(task.getIntermediatePath()));
                }
            }
            if (project.getResolution() != null) {
                inputs.addAll(existingOnly(project.getResolution().runtimeClasspath()));
            }
        }
        decideState(inputs, new File(getIntermediatePath("dex")), true);
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void execute() throws TaskInterruptError {
        ScalaAndroidProject scalaProject = requireProject();
        SbtAndroidConfig config = scalaProject.getAndroidConfig();
        if (config == null) {
            throw fail("build.sbt 还没解析");
        }
        D8Compiler d8 = getTool(Tool.D8);
        if (d8 == null) {
            throw fail("宿主工具链里没有 D8");
        }
        AndroidSdk sdk = getTool(Tool.ANDROID_SDK);
        if (sdk == null) {
            throw fail("宿主没有可用的 Android SDK 工具链");
        }

        // ---- program：要打进 APK 的 class / jar ----
        List<Path> programFiles = new ArrayList<>();
        int classCount = 0;
        classCount += collectClassFiles(classDir(CompileRJavaTask.ID), programFiles);
        int scalaClassCount = collectClassFiles(classDir(CompileScalaTask.ID), programFiles);
        classCount += scalaClassCount;
        classCount += collectClassFiles(classDir(CompileJavaSourcesTask.ID), programFiles);

        SbtResolutionResult resolution = scalaProject.getResolution();
        int dependencyJars = 0;
        if (resolution != null) {
            for (File jar : resolution.runtimeClasspath()) {
                if (jar != null && jar.isFile()) {
                    programFiles.add(jar.toPath());
                    dependencyJars++;
                }
            }
        }
        // Scala 运行库：必须进 dex，否则运行时 NoClassDefFoundError: scala/...
        int runtimeJars = 0;
        for (File jar : ScalaToolchain.runtimeJars(AndroidPlugins.getContext(this))) {
            if (jar != null && jar.isFile()) {
                programFiles.add(jar.toPath());
                runtimeJars++;
            }
        }
        if (programFiles.isEmpty()) {
            throw fail("没有可 dex 的 class（Scala/Java 编译都没产出？）");
        }
        // 有 Scala 类却没有 Scala 运行库 → 一定打出运行时崩溃的 APK，宁可在这里失败
        if (scalaClassCount > 0 && runtimeJars == 0) {
            throw fail("找不到 Scala 运行库（scala-library / scala3-library）。"
                    + "它们应该由「编译Scala源文件」任务复制到本地 env 目录，请检查该任务的日志");
        }

        // ---- library：只用于解析引用，不进产物 ----
        List<Path> libraryFiles = new ArrayList<>();
        libraryFiles.add(Paths.get(sdk.getAndroidJarPath()));
        if (sdk.getLambdaCorePath() != null && !sdk.getLambdaCorePath().isEmpty()) {
            libraryFiles.add(Paths.get(sdk.getLambdaCorePath()));
        }

        File outputDir = new File(getIntermediatePath("dex"));
        cleanDir(outputDir);
        mkdirs(outputDir);

        D8CompileParams params = new D8CompileParams();
        params.minApiLevel = Math.max(config.minSdk, 21);
        params.debug = variant != null && variant.isDebug();
        params.outputKind = D8CompileParams.OutputKind.DexIndexed;
        params.outputDir = outputDir.toPath();
        params.programFiles = programFiles;
        params.libraryFiles = libraryFiles;
        // 单趟 DexIndexed 不需要额外 classpath（引用解析交给 libraryFiles）
        params.classpathFiles = null;

        if (config.minSdk < 33) {
            warn("androidMinSdk = " + config.minSdk + "：Scala 3.8 运行库引用了 "
                    + "java.lang.invoke.VarHandle（Android 13 / API 33 起可用），"
                    + "API 33 以下设备运行可能失败，建议 build.sbt 改成 androidMinSdk := 33");
        }
        info("program " + programFiles.size() + " 项（class " + classCount
                + " 个 · 依赖 jar " + dependencyJars + " 个 · Scala 运行库 " + runtimeJars + " 个）"
                + " · library " + libraryFiles.size() + " 项 · minApi " + params.minApiLevel
                + (params.debug ? " · debug" : " · release"));

        if (!d8.compile(params, callback())) {
            throw fail("D8 生成 dex 失败");
        }
        File[] dexFiles = outputDir.listFiles();
        int count = 0;
        if (dexFiles != null) {
            for (File file : dexFiles) {
                if (file.isFile() && file.getName().endsWith(".dex")) {
                    count++;
                }
            }
        }
        if (count == 0) {
            throw fail("D8 没有产出任何 dex 文件：" + outputDir);
        }
        info("生成 dex " + count + " 个");
    }

    private File classDir(String taskId) {
        Task task = project.getTasks().getTask(taskId);
        return task == null ? null : new File(task.getIntermediatePath());
    }

    /** 递归收集 .class 文件（D8 不接受目录作为 program 输入）。 */
    private int collectClassFiles(File directory, List<Path> out) {
        if (directory == null || !directory.isDirectory()) {
            return 0;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return 0;
        }
        Arrays.sort(children, (left, right) -> left.getName().compareTo(right.getName()));
        int count = 0;
        for (File child : children) {
            if (child.isDirectory()) {
                count += collectClassFiles(child, out);
            } else if (child.getName().endsWith(".class")) {
                out.add(child.toPath());
                count++;
            }
        }
        return count;
    }
}
