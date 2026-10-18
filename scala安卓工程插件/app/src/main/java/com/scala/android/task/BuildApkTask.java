package com.scala.android.task;

import com.rapid.api.logging.Logger;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.task.exception.TaskInterruptError;
import com.rapid.util.file.FileUtils;
import com.scala.android.androidproject.ScalaAndroidProject;
import com.scala.android.sbt.SbtAndroidConfig;

import java.io.File;
import java.util.List;

/**
 * 「构建APK」：整条链的收口任务。
 *
 * <pre>
 * sbtSync → sbtResolve → { compileScalaSources, compileProjectResources, mergeManifest }
 *         → linkAppResources → compileJavaSources → dexWithD8
 *         → packageApk → signApkScala → zipAlignScala → buildApk（本任务）
 * </pre>
 *
 * <p>本任务只做两件事：把对齐后的 APK 拷到
 * {@code build/outputs/apk/<variant>/<工程名>.apk}，并打一行"APK 已生成"的日志
 * （含相对工程根的路径，方便在日志里点开）。
 */
public class BuildApkTask extends ScalaAndroidTask {

    public static final String ID = "buildApk";
    public static final String NAME = "构建APK";

    @Override
    public void prepare(Project p, ProjectVariant variant, Logger logger) {
        super.prepare(p, variant, logger);
        List<File> inputs = new java.util.ArrayList<>();
        if (project != null) {
            com.rapid.api.project.task.Task sign = project.getTasks().getTask(SignApkTask.ID);
            if (sign != null) {
                inputs.add(new File(sign.getIntermediatePath(project.getName() + "-signed.apk")));
            }
        }
        File target = project == null ? null
                : new File(project.apkOutputDir(), project.getName() + ".apk");
        decideState(inputs, target, true);
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
        File aligned = new File(project.getTasks().getTask(SignApkTask.ID)
                .getIntermediatePath(project.getName() + "-signed.apk"));
        if (!aligned.isFile()) {
            throw fail("没有签名后的 APK：" + aligned);
        }
        File target = new File(scalaProject.apkOutputDir(), scalaProject.getName() + ".apk");
        mkdirs(target.getParentFile());
        try {
            FileUtils.copyFile(aligned, target);
        } catch (Throwable error) {
            throw fail("拷贝 APK 到输出目录失败：" + error);
        }
        SbtAndroidConfig config = scalaProject.getAndroidConfig();
        info("APK 已生成 → " + target.getAbsolutePath()
                + "（" + target.length() + " bytes）");
        info("  应用包名 " + (config == null ? "?" : config.applicationId)
                + " · 版本 " + (config == null ? "?" : config.versionName
                + " (" + config.versionCode + ")")
                + " · minSdk " + (config == null ? "?" : config.minSdk)
                + " · 变体 " + (variant == null ? "release" : variant.getName()));
    }
}
