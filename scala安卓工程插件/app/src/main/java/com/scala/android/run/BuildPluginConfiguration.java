package com.scala.android.run;

import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.component.running.TaskRunConfiguration;
import com.rapid.api.project.task.ExecutorResult;
import com.scala.android.androidproject.ScalaAndroidProject;
import com.scala.android.sbt.TiecodePluginKeys;
import com.scala.android.task.BuildPluginTask;

import java.io.File;

/**
 * 运行配置「构建结绳插件」。
 *
 * <p>对应结绳本体的 {@code BuildQiPluginConfig}：跑完整条 APK 链后，把签名对齐
 * 后的 {@code <工程名>.apk} 改后缀成 <b>{@code .rmx}</b>（结绳插件包），输出到
 * {@code build/outputs/plugins/<variant>/<工程名>.rmx}。
 *
 * <pre>
 * … → 签名APK → 构建APK → buildPlugin（本配置指向的任务）
 * </pre>
 *
 * <p>与 {@link PackAndRunConfiguration} 不同：
 * <ul>
 *   <li>产物是 <b>rmx</b> 而不是"安装到设备"的 APK —— 用户拿到 rmx 自己用结绳安装；</li>
 *   <li>前置校验 {@code src/main/resources/META-INF/program.json}（没有会在任务里报可读错误）；</li>
 *   <li>变体是 <b>release</b>（与本体 {@code BuildQiPluginConfig.getVariant()==RELEASE} 一致）。</li>
 * </ul>
 */
public class BuildPluginConfiguration extends TaskRunConfiguration {

    public static final String ID = "scala-tiecode-plugin-build";

    private final Project project;

    public BuildPluginConfiguration(Project project) {
        this.project = project;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "构建结绳插件";
    }

    @Override
    public String getTaskId() {
        return BuildPluginTask.ID;
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
            File rmx = new File(scalaProject.pluginOutputDir(), scalaProject.getName() + ".rmx");
            // 顺带校验 program.json 在包里有（任务里已查过，这里只是给用户再打一行）
            if (rmx.isFile()) {
                getLogger().info(getName() + " 完成 · rmx：" + rmx.getAbsolutePath()
                        + "（" + rmx.length() / 1024 + " KB）");
            } else {
                getLogger().warning(getName() + "：日志显示成功但没找到 " + rmx);
            }
            // 校验工程侧也带着 program.json（没有会让下次安装失败）
            File json = new File(scalaProject.getProjectDir(), TiecodePluginKeys.PROGRAM_JSON_RELATIVE);
            if (!json.isFile()) {
                getLogger().warning(getName() + "：工程里没有 "
                        + TiecodePluginKeys.PROGRAM_JSON_RELATIVE
                        + "，建议补上再「调试结绳插件」");
            }
        } else {
            getLogger().info(getName() + " 结束：" + result);
        }
    }
}
