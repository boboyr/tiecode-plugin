package com.scala.android.run;

import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.component.running.TaskRunConfiguration;
import com.rapid.api.project.task.ExecutorResult;
import com.scala.android.androidproject.ScalaAndroidProject;
import com.scala.android.task.InstallApkTask;

import java.io.File;

/**
 * 运行配置「调试打包运行」/「正式打包运行」。
 *
 * <p>命名与行为都对齐结绳本体的 {@code DebugRunConfig} / {@code ReleaseRunConfig}
 * （本体的名字就是这两个，任务链跑完后 {@code ContentUtils.installApk(...)} 装上设备）。
 * 本插件把这件事拆成两个任务：
 * <pre>
 * … → 构建APK（把签名后的 APK 拷到 build/outputs/apk/&lt;variant&gt;/）
 *      → 安装APK（交给系统安装器）
 * </pre>
 * 所以日志里能分别看到「构建APK」「安装APK」两步，而不是只在运行配置里悄悄安装。
 */
public class PackAndRunConfiguration extends TaskRunConfiguration {

    public static final String ID_DEBUG = "scala-android-debug-build-run";
    public static final String ID_RELEASE = "scala-android-release-build-run";

    private final Project project;
    private final ProjectVariant variant;

    public PackAndRunConfiguration(Project project, ProjectVariant variant) {
        this.project = project;
        this.variant = variant;
    }

    @Override
    public String getId() {
        return variant != null && variant.isDebug() ? ID_DEBUG : ID_RELEASE;
    }

    @Override
    public String getName() {
        return variant != null && variant.isDebug() ? "调试打包运行" : "正式打包运行";
    }

    @Override
    public String getTaskId() {
        return InstallApkTask.ID;
    }

    @Override
    public ProjectVariant getVariant() {
        return variant;
    }

    @Override
    public Project getProject() {
        return project;
    }

    @Override
    public void onStart() {
        // build.sbt 可能刚被改过，重新解析一遍
        if (project instanceof ScalaAndroidProject) {
            ((ScalaAndroidProject) project).invalidateBuild();
        }
    }

    @Override
    public void onResult(ExecutorResult result) {
        if (getLogger() == null) {
            return;
        }
        if (result == ExecutorResult.SUCCESS && project instanceof ScalaAndroidProject) {
            ScalaAndroidProject scalaProject = (ScalaAndroidProject) project;
            File apk = new File(scalaProject.apkOutputDir(), scalaProject.getName() + ".apk");
            getLogger().info(getName() + " 完成 · APK：" + apk.getAbsolutePath());
        } else {
            getLogger().info(getName() + " 结束：" + result);
        }
    }
}
