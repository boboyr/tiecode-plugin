package com.scala.android.task;

import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.logging.Logger;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.task.TaskState;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.rapid.api.project.toolchain.Tool;
import com.rapid.api.project.toolchain.android.sdk.AndroidSdk;
import com.rapid.api.project.toolchain.java.JavaCompiler;
import com.rapid.api.project.toolchain.java.JavacParams;
import com.scala.android.androidproject.ScalaAndroidProject;
import com.scala.android.dependency.SbtResolutionResult;
import com.scala.android.sbt.SbtAndroidConfig;
import com.scala.android.tool.ScalaToolchain;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 「编译 Java 源（含 R）」：用宿主的 {@code Tool.JAVA_COMPILER} 编译
 * <ul>
 *   <li>{@code src/main/java} 下的 .java（很多 Android 依赖/事件处理器只提供 Java 写法）；</li>
 *   <li>aapt2 link 生成的 {@code R.java}；</li>
 *   <li>工程里的 {@code gen/} 目录（如果有）。</li>
 * </ul>
 * 输出与 Scala 编译产物同一个 classpath 体系，最后一起进 D8。
 */
public class CompileJavaSourcesTask extends ScalaAndroidTask {

    public static final String ID = "compileJavaSources";
    public static final String NAME = "编译Java源文件";

    @Override
    public void prepare(Project p, ProjectVariant variant, Logger logger) {
        super.prepare(p, variant, logger);
        ScalaAndroidProject scalaProject = project;
        SbtAndroidConfig config = scalaProject == null ? null : scalaProject.getAndroidConfig();
        List<File> javaFiles = config == null || config.javaSourceDir == null
                ? new java.util.ArrayList<>() : ScalaFiles.collect(config.javaSourceDir, ".java");
        List<File> inputs = new java.util.ArrayList<>(javaFiles);
        if (scalaProject != null) {
            com.rapid.api.project.task.Task rJava = scalaProject.getTasks().getTask(CompileRJavaTask.ID);
            if (rJava != null) {
                inputs.add(new File(rJava.getIntermediatePath()));
            }
            com.rapid.api.project.task.Task scala = scalaProject.getTasks().getTask(CompileScalaTask.ID);
            if (scala != null) {
                inputs.add(new File(scala.getIntermediatePath()));
            }
        }
        decideState(inputs, new File(getIntermediatePath()), !javaFiles.isEmpty());
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

        List<File> javaFiles = new ArrayList<>();
        if (config.javaSourceDir != null && config.javaSourceDir.isDirectory()) {
            javaFiles.addAll(ScalaFiles.collect(config.javaSourceDir, ".java"));
        }
        if (javaFiles.isEmpty()) {
            info("没有 Java 源需要编译");
            // 源文件全删掉了，也把旧产物清掉，别让 dex 继续打包已经不存在的类
            cleanDir(new File(getIntermediatePath()));
            setState(TaskState.NO_SOURCE);
            return;
        }

        JavaCompiler javac = getTool(Tool.JAVA_COMPILER);
        if (javac == null) {
            throw fail("宿主工具链里没有 Java 编译器");
        }
        AndroidSdk sdk = getTool(Tool.ANDROID_SDK);
        File output = new File(getIntermediatePath());
        mkdirs(output);
        // 与 Scala 编译同理：清空后再编，避免已删除源文件的旧 class 残留在产物里。
        cleanDir(output);

        JavacParams params = new JavacParams();
        params.sourceVersion = javac.getCompilerVersion();
        params.targetVersion = javac.getCompilerVersion();
        params.outputPath = output.getAbsolutePath();
        params.inputJavaFiles = new ArrayList<>();
        for (File file : javaFiles) {
            params.inputJavaFiles.add(file.getAbsolutePath());
        }
        List<String> bootClassPaths = new ArrayList<>();
        if (sdk != null) {
            bootClassPaths.add(sdk.getAndroidJarPath());
        }
        params.bootClassPaths = bootClassPaths;
        List<String> classPaths = new ArrayList<>();
        classPaths.add(project.getTasks().getTask(CompileScalaTask.ID).getIntermediatePath());
        // R 类已经由 compileRJava 编好，这里放进 classpath（而不是再编一次）
        classPaths.add(project.getTasks().getTask(CompileRJavaTask.ID).getIntermediatePath());
        for (File jar : scalaClasspath(scalaProject)) {
            classPaths.add(jar.getAbsolutePath());
        }
        params.classPaths = classPaths;
        params.options = new String[]{"-encoding", "UTF-8", "-nowarn"};

        info("java 源 " + javaFiles.size() + " 个 · classpath " + classPaths.size() + " 项");
        boolean ok = javac.compile(params, callback());
        if (!ok) {
            throw fail("Java 编译失败");
        }
    }

    private List<File> scalaClasspath(ScalaAndroidProject scalaProject) {
        List<File> classpath = new ArrayList<>();
        classpath.addAll(ScalaToolchain.compileClasspath(AndroidPlugins.getContext(this)));
        SbtResolutionResult resolution = scalaProject.getResolution();
        if (resolution != null) {
            classpath.addAll(resolution.classpath());
        }
        return classpath;
    }
}
