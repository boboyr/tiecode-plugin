package com.tiecode.aarplugin.run;

import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.component.running.TaskRunConfiguration;
import com.rapid.api.project.task.ExecutorResult;
import com.tiecode.aarplugin.compiler.OriginalNameSwitch;
import com.tiecode.aarplugin.setting.JavaVersionSetting;
import com.tiecode.aarplugin.task.BundleJarTask;

/**
 * 运行配置「构建jar」：复用宿主编译链，把编译产物打成 jar。
 *
 * <p>与「构建aar」一样：构建期间切到「原名输出」+ 应用设置页里选的 Java 版本，
 * 构建结束立刻还原。
 */
public class BuildJarRunConfiguration extends TaskRunConfiguration {

    public static final String ID = "jar-build-original";
    public static final String NAME = "构建jar";

    private final Project project;

    public BuildJarRunConfiguration(Project project) {
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
        return BundleJarTask.ID;
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
            getLogger().info("构建jar：本次编译按结绳原中文类名输出，Java 版本 "
                    + JavaVersionSetting.get());
        }
    }

    @Override
    public void onResult(ExecutorResult result) {
        JavaVersionSetting.restore();
        OriginalNameSwitch.setEnabled(false);
    }
}
