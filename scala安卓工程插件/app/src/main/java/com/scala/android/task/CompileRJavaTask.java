package com.scala.android.task;

import com.rapid.api.project.task.Task;
import com.rapid.api.project.task.TaskState;
import com.rapid.api.logging.Logger;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.rapid.api.project.toolchain.Tool;
import com.rapid.api.project.toolchain.android.sdk.AndroidSdk;
import com.rapid.api.project.toolchain.java.JavaCompiler;
import com.rapid.api.project.toolchain.java.JavacParams;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 「编译R类」：把 aapt2 link 生成的 {@code R.java} 先编成 class。
 *
 * <h3>为什么单独一个任务、而且要排在 Scala 之前</h3>
 * 安卓工程的源码里到处用 {@code R.layout.xxx / R.id.xxx}，而 R 类只有在
 * <b>资源链接之后</b>才存在。结绳本体的顺序是
 * {@code 编译项目资源 → 合并清单 → 链接资源 → 编译Java源}，
 * 所以这里把"生成 R 类并编译"插在 Scala/Java 编译之前：
 *
 * <pre>
 * 链接资源文件 → 编译R类 → 编译Scala源文件 → 编译Java源文件 → D8 → …
 * </pre>
 *
 * <p>产物同时进 Scala 与 Java 编译的 classpath，以及 D8 的 program files。
 */
public class CompileRJavaTask extends ScalaAndroidTask {

    public static final String ID = "compileRJava";
    public static final String NAME = "编译R类";

    @Override
    public void prepare(Project p, ProjectVariant variant, Logger logger) {
        super.prepare(p, variant, logger);
        com.rapid.api.project.task.Task linkRes = project == null ? null
                : project.getTasks().getTask(LinkResTask.ID);
        File generated = linkRes == null ? null : linkRes.getGeneratedDir();
        boolean has = generated != null && generated.isDirectory()
                && !ScalaFiles.collect(generated, ".java").isEmpty();
        List<File> inputs = new java.util.ArrayList<>();
        if (generated != null) {
            inputs.add(generated);
        }
        decideState(inputs, new File(getIntermediatePath()), has);
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
        requireProject();
        Task linkTask = project.getTasks().getTask(LinkResTask.ID);
        File generated = linkTask == null ? null : linkTask.getGeneratedDir();
        List<File> javaFiles = ScalaFiles.collect(generated, ".java");
        File output = new File(getIntermediatePath());
        if (javaFiles.isEmpty()) {
            info("没有生成的 Java 源（aapt2 link 没产出 R.java？）");
            setState(TaskState.NO_SOURCE);
            return;
        }

        JavaCompiler javac = getTool(Tool.JAVA_COMPILER);
        if (javac == null) {
            throw fail("宿主工具链里没有 Java 编译器");
        }
        AndroidSdk sdk = getTool(Tool.ANDROID_SDK);
        mkdirs(output);

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
        params.classPaths = bootClassPaths;
        params.options = new String[]{"-encoding", "UTF-8", "-nowarn"};

        info("R 类源文件 " + javaFiles.size() + " 个");
        if (!javac.compile(params, callback())) {
            throw fail("编译 R 类失败");
        }
    }
}
