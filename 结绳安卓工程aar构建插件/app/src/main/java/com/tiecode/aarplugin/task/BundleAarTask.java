package com.tiecode.aarplugin.task;

import com.rapid.api.Platform;
import com.rapid.api.project.Project;
import com.rapid.api.project.task.BaseTask;
import com.rapid.api.project.task.Task;
import com.rapid.api.project.task.TaskContainer;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.tiecode.aarplugin.compiler.OriginalNameSwitch;
import com.tiecode.aarplugin.util.AarFiles;
import com.tiecode.aarplugin.util.AarMetadata;
import com.tiecode.aarplugin.util.AarPacker;

import java.io.File;
import java.io.IOException;

/**
 * 「构建aar」的落地点：把宿主已经编译好的产物打包成标准 AAR。
 *
 * <p>依赖宿主任务（插件自己不编译、不链接资源）：
 * <pre>
 * compileWithTiecodec  结绳源码 → src / res / assets / jniLibs / AndroidManifest.xml / metadata.json
 * linkAppResources     资源链接 → R.txt
 * compileWithJavac     编译到 .class
 * </pre>
 * 具体摆放规则见 {@link AarPacker}。
 */
public class BundleAarTask extends BaseTask {

    public static final String ID = "bundleAarOriginal";
    public static final String NAME = "打包为AAR(原中文类名)";

    /** 宿主任务 id */
    public static final String TASK_COMPILE_TIECODEC = "compileWithTiecodec";
    public static final String TASK_COMPILE_JAVAC = "compileWithJavac";
    public static final String TASK_LINK_RESOURCES = "linkAppResources";
    /** 宿主的 NDK 任务：把工程 JNI 代码编成 .so 落到 jniLibs（没有 JNI 时该任务自动跳过） */
    public static final String TASK_COMPILE_NATIVE = "compileNativeObj";

    public static final String METADATA_NAME = "metadata.json";
    public static final String R_TXT_NAME = "R.txt";
    public static final String AAR_SUFFIX = ".aar";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return NAME;
    }

    /** 供运行配置/日志显示产物路径。 */
    public String getAarPath() {
        Project project = Platform.getProject();
        String fileName = project == null ? ("lib" + AAR_SUFFIX)
                : AarFiles.sanitizeFileName(project.getName()) + AAR_SUFFIX;
        return getOutputPath(fileName);
    }

    @Override
    public void execute() throws TaskInterruptError {
        // 结绳源码在上游早就编译完了，这里先关掉原名开关，
        // 免得编辑器后台的其它编译也被影响
        OriginalNameSwitch.setEnabled(false);

        Project project = Platform.getProject();
        if (project == null) {
            throw new TaskInterruptError("未打开任何工程");
        }
        TaskContainer tasks = project.getTasks();
        Task compileTiecodec = tasks == null ? null : tasks.getTask(TASK_COMPILE_TIECODEC);
        Task compileJavac = tasks == null ? null : tasks.getTask(TASK_COMPILE_JAVAC);
        Task linkResources = tasks == null ? null : tasks.getTask(TASK_LINK_RESOURCES);
        if (compileTiecodec == null || compileJavac == null) {
            throw new TaskInterruptError("宿主缺少 compileWithTiecodec / compileWithJavac 任务");
        }

        File metadataFile = new File(compileTiecodec.getIntermediatePath(METADATA_NAME));
        AarMetadata metadata = AarMetadata.read(metadataFile);
        if (metadata == null) {
            throw new TaskInterruptError("无法读取结绳编译产物：" + metadataFile);
        }
        File classesDir = new File(compileJavac.getIntermediatePath());
        if (!classesDir.isDirectory()) {
            throw new TaskInterruptError("未找到编译好的 class 目录：" + classesDir);
        }
        File rTxt = linkResources == null
                ? null : new File(linkResources.getIntermediatePath(R_TXT_NAME));

        try {
            File output = AarPacker.pack(
                    new File(getIntermediatePath("aar")),
                    metadata,
                    classesDir,
                    rTxt,
                    project.getChildFile("consumer-rules.pro"),
                    new File(getAarPath()));
            if (logger != null) {
                logger.info("AAR 已生成：" + output.getAbsolutePath());
            }
        } catch (IOException e) {
            throw new TaskInterruptError("打包 AAR 失败：" + e);
        }
    }
}
