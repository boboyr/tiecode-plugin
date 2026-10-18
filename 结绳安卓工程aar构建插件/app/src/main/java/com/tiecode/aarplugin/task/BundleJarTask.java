package com.tiecode.aarplugin.task;

import com.rapid.api.Platform;
import com.rapid.api.project.Project;
import com.rapid.api.project.task.BaseTask;
import com.rapid.api.project.task.Task;
import com.rapid.api.project.task.TaskContainer;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.tiecode.aarplugin.compiler.OriginalNameSwitch;
import com.tiecode.aarplugin.util.AarFiles;
import com.tiecode.aarplugin.util.AarPacker;

import java.io.File;
import java.io.IOException;

/**
 * 「构建jar」的落地点：把宿主 {@code compileWithJavac} 编译出来的类原样打成一个 jar。
 *
 * <p>只依赖宿主的 javac 任务（它自己会带起 编译结绳源码 → 资源 → 清单 整条链）；
 * 插件不编译、不额外加工，产出的 class 名就是结绳原中文名。
 *
 * <p>产物：{@code build/outputs/bundleJarOriginal/release/<工程名>.jar}
 */
public class BundleJarTask extends BaseTask {

    public static final String ID = "bundleJarOriginal";
    public static final String NAME = "打包为JAR(原中文类名)";

    public static final String TASK_COMPILE_JAVAC = "compileWithJavac";
    public static final String JAR_SUFFIX = ".jar";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return NAME;
    }

    /** 供运行配置/日志显示产物路径。 */
    public String getJarPath() {
        Project project = Platform.getProject();
        String fileName = project == null ? ("classes" + JAR_SUFFIX)
                : AarFiles.sanitizeFileName(project.getName()) + JAR_SUFFIX;
        return getOutputPath(fileName);
    }

    @Override
    public void execute() throws TaskInterruptError {
        // 结绳源码在上游已经编译完，先关掉原名开关
        OriginalNameSwitch.setEnabled(false);

        Project project = Platform.getProject();
        if (project == null) {
            throw new TaskInterruptError("未打开任何工程");
        }
        TaskContainer tasks = project.getTasks();
        Task compileJavac = tasks == null ? null : tasks.getTask(TASK_COMPILE_JAVAC);
        if (compileJavac == null) {
            throw new TaskInterruptError("宿主缺少 compileWithJavac 任务");
        }
        File classesDir = new File(compileJavac.getIntermediatePath());
        if (!classesDir.isDirectory()) {
            throw new TaskInterruptError("未找到编译好的 class 目录：" + classesDir);
        }

        try {
            File output = new File(getJarPath());
            AarPacker.buildJar(classesDir, output);
            if (logger != null) {
                logger.info("JAR 已生成：" + output.getAbsolutePath());
            }
        } catch (IOException e) {
            throw new TaskInterruptError("打包 JAR 失败：" + e);
        }
    }
}
