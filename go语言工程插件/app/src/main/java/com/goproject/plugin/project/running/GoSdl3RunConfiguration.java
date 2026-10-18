package com.goproject.plugin.project.running;

import com.goproject.plugin.page.SDLPluginPage;
import com.goproject.plugin.task.BuildLibmainTask;
import com.rapid.api.Platform;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.component.running.TaskRunConfiguration;
import com.rapid.api.project.task.ExecutorResult;

/**
 * 「构建并运行」：跑完构建任务链后，在插件页面里打开 SDL3 图形界面。
 *
 * <p>与结绳Linux工程插件的 {@code TieRunConfiguration} 一一对应，
 * 唯一区别是等待的任务换成了 {@link BuildLibmainTask#getId()}。
 */
public class GoSdl3RunConfiguration extends TaskRunConfiguration {

    @Override
    public Project getProject() {
        return Platform.getProject();
    }

    @Override
    public String getTaskId() {
        return BuildLibmainTask.ID;
    }

    @Override
    public ProjectVariant getVariant() {
        return ProjectVariant.DEBUG;
    }

    @Override
    public void onResult(ExecutorResult executorResult) {
        if (executorResult == ExecutorResult.SUCCESS) {
            // 必须回 UI 线程才能创建 / 挂载 View
            runUI(new Runnable() {
                @Override
                public void run() {
                    startPluginPage(SDLPluginPage.class);
                }
            });
        }
    }

    @Override
    public String getId() {
        return "gosdl3-run";
    }

    @Override
    public String getName() {
        return "构建并运行";
    }
}
