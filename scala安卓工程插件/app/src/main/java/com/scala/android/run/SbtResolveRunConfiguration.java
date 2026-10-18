package com.scala.android.run;

import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.component.running.TaskRunConfiguration;
import com.rapid.api.project.task.ExecutorResult;
import com.scala.android.androidproject.ScalaAndroidProject;
import com.scala.android.task.SbtResolveTask;

/**
 * 运行配置「同步sbt依赖」：只跑 build.sbt 解析 + 依赖下载，不编译、不打包。
 *
 * <p>用途：刚改完 {@code libraryDependencies} 想先把依赖拉下来，
 * 顺手让代码补全能立刻用上新依赖的类（同步结束后 classpath 钩子自动更新）。
 */
public class SbtResolveRunConfiguration extends TaskRunConfiguration {

    public static final String ID = "scala-android-sbt-resolve";

    private final Project project;

    public SbtResolveRunConfiguration(Project project) {
        this.project = project;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "同步sbt依赖";
    }

    @Override
    public String getTaskId() {
        return SbtResolveTask.ID;
    }

    @Override
    public ProjectVariant getVariant() {
        return ProjectVariant.DEBUG;
    }

    @Override
    public Project getProject() {
        return project;
    }

    @Override
    public void onStart() {
        if (project instanceof ScalaAndroidProject) {
            ((ScalaAndroidProject) project).invalidateBuild();
        }
    }

    @Override
    public void onResult(ExecutorResult result) {
        if (getLogger() != null) {
            getLogger().info("同步sbt依赖 结束：" + result);
        }
    }
}
