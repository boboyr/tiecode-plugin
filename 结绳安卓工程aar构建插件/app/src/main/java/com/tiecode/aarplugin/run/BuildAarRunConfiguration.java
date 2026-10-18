package com.tiecode.aarplugin.run;

import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.component.running.TaskRunConfiguration;
import com.rapid.api.project.task.ExecutorResult;
import com.tiecode.aarplugin.compiler.OriginalNameSwitch;
import com.tiecode.aarplugin.setting.JavaVersionSetting;
import com.tiecode.aarplugin.task.BundleAarTask;

/**
 * 运行配置「构建aar」。
 *
 * <p>执行期间把编译器切到「原名输出」（{@link OriginalNameSwitch}），
 * 于是宿主自己的编译任务产出的就是结绳原中文类名；构建结束立刻关掉，
 * 保证平时的调试/构建仍旧是宿主的随机命名。
 */
public class BuildAarRunConfiguration extends TaskRunConfiguration {

    public static final String ID = "aar-build-original";
    public static final String NAME = "构建aar";

    private final Project project;

    public BuildAarRunConfiguration(Project project) {
        this.project = project;
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
    public String getTaskId() {
        return BundleAarTask.ID;
    }

    @Override
    public ProjectVariant getVariant() {
        return ProjectVariant.RELEASE;
    }

    @Override
    public Project getProject() {
        return project;
    }

    @Override
    public void onStart() {
        OriginalNameSwitch.setEnabled(true);
        JavaVersionSetting.apply();
        if (getLogger() != null) {
            getLogger().info("构建aar：本次编译按结绳原中文类名输出（不再随机生成 rn_），Java 版本 "
                    + JavaVersionSetting.get());
        }
    }

    @Override
    public void onResult(ExecutorResult result) {
        // 构建结束立刻还原编译器与原名开关
        //（产物路径由 BundleAarTask 自己打日志，这里不再重复输出）
        JavaVersionSetting.restore();
        OriginalNameSwitch.setEnabled(false);
    }
}
