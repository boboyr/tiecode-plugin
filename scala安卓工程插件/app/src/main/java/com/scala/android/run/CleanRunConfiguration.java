package com.scala.android.run;

import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.component.running.TaskRunConfiguration;
import com.rapid.api.project.task.ExecutorResult;
import com.scala.android.task.CleanScalaAndroidTask;

/**
 * 运行配置「清理构建产物」。
 */
public class CleanRunConfiguration extends TaskRunConfiguration {

    public static final String ID = "scala-android-clean";

    private final Project project;

    public CleanRunConfiguration(Project project) {
        this.project = project;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "清理构建产物";
    }

    @Override
    public String getTaskId() {
        return CleanScalaAndroidTask.ID;
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
    public void onResult(ExecutorResult result) {
        if (getLogger() != null) {
            getLogger().info("清理构建产物 结束：" + result);
        }
    }
}
