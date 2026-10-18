package com.scala.android.task;

import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.logging.Logger;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.task.TaskState;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.scala.android.androidproject.ScalaAndroidProject;
import com.scala.android.dependency.SbtResolutionResult;
import com.scala.android.sbt.SbtAndroidConfig;
import com.scala.android.tool.ScalaCompile;
import com.scala.android.tool.ScalaCompileApiKeys;
import com.scala.android.tool.ScalaCompileParams;
import com.scala.android.tool.ScalaToolchain;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 「编译 Scala 源码」：用随插件打包的 Scala 3 编译器（dotty）编译
 * {@code src/main/scala} 下的全部 {@code .scala}。
 *
 * <p>classpath = android.jar + scala 标准库/存根 + sbt 依赖（含 AAR 解包出的 classes.jar）
 * + 工程 {@code lib/*.jar}。编译器本身通过工具链服务拿
 * （{@link ScalaCompileApiKeys#SCALA_COMPILE}），与结绳本体的
 * {@code Tool.JAVA_COMPILER}/{@code Tool.D8} 用法一致。
 */
public class CompileScalaTask extends ScalaAndroidTask {

    public static final String ID = "compileScalaSources";
    public static final String NAME = "编译Scala源文件";

    @Override
    public void prepare(Project p, ProjectVariant variant, Logger logger) {
        super.prepare(p, variant, logger);
        ScalaAndroidProject scalaProject = project;
        SbtAndroidConfig config = scalaProject == null ? null : scalaProject.getAndroidConfig();
        List<File> sources = config == null
                ? new java.util.ArrayList<>()
                : ScalaFiles.collect(config.scalaSourceDir, ".scala");
        List<File> inputs = new java.util.ArrayList<>(sources);
        // R 类（上游产物）变了，Scala 也要重编
        if (scalaProject != null) {
            com.rapid.api.project.task.Task rJava = scalaProject.getTasks().getTask(CompileRJavaTask.ID);
            if (rJava != null) {
                inputs.add(new File(rJava.getIntermediatePath()));
            }
            if (scalaProject.getResolution() != null) {
                inputs.addAll(existingOnly(scalaProject.getResolution().classpath()));
            }
        }
        decideState(inputs, new File(getIntermediatePath()), !sources.isEmpty());
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
        List<File> sources = ScalaFiles.collect(config.scalaSourceDir, ".scala");
        if (sources.isEmpty()) {
            info("没有 Scala 源文件（" + config.scalaSourceDir + "）");
            // 源文件全删掉了，也把旧产物清掉，别让 dex 继续打包已经不存在的类
            cleanDir(new File(getIntermediatePath()));
            setState(TaskState.NO_SOURCE);
            return;
        }

        File outputDir = new File(getIntermediatePath());
        mkdirs(outputDir);
        // 先清空输出目录：增量编译只覆盖"这次编译到的"类，删掉的源文件留下的旧 .class
        // 会一直留在目录里，被 dex/APK 原封不动打进包（删了 PluginEntry 照样能装出旧插件）。
        cleanDir(outputDir);

        ScalaCompile compiler = getTool(ScalaCompileApiKeys.SCALA_COMPILE);
        if (compiler == null) {
            throw fail("宿主工具链里没有 Scala 编译器（插件未正确加载？）");
        }
        try {
            ScalaToolchain.install(AndroidPlugins.getContext(this));
        } catch (Throwable error) {
            warn("安装 Scala 标准库失败：" + error);
        }

        ScalaCompileParams params = new ScalaCompileParams();
        params.sourceFiles = sources;
        params.outputDirectory = outputDir;
        params.classpath = classpath(scalaProject, config);

        String options = config.scalacOptions.isEmpty() ? "" : " · 选项 " + config.scalacOptions;
        info("源文件 " + sources.size() + " 个 · classpath " + params.classpath.size() + " 项"
                + options + " · 输出 " + outputDir.getName());
        int code = compiler.compile(params, callback());
        if (code != 0) {
            throw fail("Scala 编译失败（退出码 " + code + "）");
        }
    }

    /** 编译 classpath：android.jar + 标准库 + 依赖 + 附加选项。 */
    protected List<File> classpath(ScalaAndroidProject scalaProject, SbtAndroidConfig config) {
        List<File> classpath = new ArrayList<>();
        classpath.addAll(ScalaToolchain.compileClasspath(AndroidPlugins.getContext(this)));
        SbtResolutionResult resolution = scalaProject.getResolution();
        if (resolution != null) {
            classpath.addAll(resolution.classpath());
        } else if (!config.dependencies.isEmpty()) {
            warn("依赖还没解析（缺少 sbtResolve 任务？），编译可能因为缺 classpath 失败");
        }
        // R 类（aapt2 link 生成 → 先被 compileRJava 编成 class）也要在 classpath 上，
        // 否则源码里的 R.layout.xxx / R.id.xxx 会报 "Not found: R"。
        File rClasses = new File(project.getTasks().getTask(CompileRJavaTask.ID).getIntermediatePath());
        if (rClasses.isDirectory()) {
            classpath.add(rClasses);
        }
        return classpath;
    }
}
