package com.scala.android.run;

import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.component.running.TaskRunConfiguration;
import com.rapid.api.project.task.ExecutorResult;
import com.scala.android.androidproject.ScalaAndroidProject;
import com.scala.android.sbt.TiecodePluginKeys;
import com.scala.android.task.BuildPluginTask;
import com.scala.android.task.InstallPluginTask;

import java.io.File;

/**
 * 运行配置「调试结绳插件」。
 *
 * <p>对应结绳本体的 {@code DebugQiPluginConfig}：跑完构建链得到 rmx 后，
 * 直接把它装进结绳宿主的公共 {@code Programs} 目录，并重启宿主让装载器
 * 在新会话里加载新插件。
 *
 * <pre>
 * … → 构建APK → buildPlugin → installPlugin（本配置指向的任务）
 * </pre>
 *
 * <p>顺序与宿主一致：先广播 {@code ACTION_SHUTDOWN} 停宿主，再决定
 * 「首装（走 {@code am -d file://} 让宿主真正安装）」或
 * 「覆盖（拷进 Programs 目录再重启）」。
 *
 * <p>与 {@link BuildPluginConfiguration} 的差异：
 * <ul>
 *   <li>产物是"装好并重启宿主"而不是一个 rmx 文件；</li>
 *   <li>前置校验 {@code src/main/resources/META-INF/program.json}；</li>
 *   <li>变体是 <b>release</b>（与宿主一致：调试插件走的就是 release 构建）。</li>
 * </ul>
 */
public class DebugPluginConfiguration extends TaskRunConfiguration {

    public static final String ID = "scala-tiecode-plugin-debug";

    private final Project project;

    public DebugPluginConfiguration(Project project) {
        this.project = project;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "调试结绳插件";
    }

    @Override
    public String getTaskId() {
        return InstallPluginTask.ID;
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
            if (rmx.isFile()) {
                getLogger().info(getName() + " 完成 · 已安装 " + rmx.getName()
                        + " 到结绳，宿主重启后将加载新插件");
            } else {
                getLogger().warning(getName() + "：日志显示成功但没找到 " + rmx);
            }
            File json = new File(scalaProject.getProjectDir(), TiecodePluginKeys.PROGRAM_JSON_RELATIVE);
            if (!json.isFile()) {
                getLogger().warning(getName() + "：工程里没有 "
                        + TiecodePluginKeys.PROGRAM_JSON_RELATIVE
                        + "，装进宿主可能无法识别");
            }
        } else {
            getLogger().info(getName() + " 结束：" + result);
        }
    }
}
