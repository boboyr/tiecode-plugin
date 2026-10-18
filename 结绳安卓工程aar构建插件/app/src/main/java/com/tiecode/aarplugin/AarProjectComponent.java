package com.tiecode.aarplugin;

import android.util.Log;

import com.rapid.api.Platform;
import com.rapid.api.framework.project.ProjectFramework;
import com.rapid.api.project.CodeProject;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectManager;
import com.rapid.api.project.component.BaseComponent;
import com.rapid.api.project.task.Task;
import com.rapid.api.project.task.TaskContainer;
import com.rapid.util.file.FileUtils;
import com.tiecode.aarplugin.run.BuildAarRunConfiguration;
import com.tiecode.aarplugin.run.BuildJarRunConfiguration;
import com.tiecode.aarplugin.task.BundleAarTask;
import com.tiecode.aarplugin.task.BundleJarTask;

import org.json.JSONObject;

import java.io.File;

/**
 * 工程组件：工程打开后，给「结绳安卓工程」({@code cn.tiecode.android}) 挂上
 * 打包任务与运行配置：
 *
 * <ul>
 *   <li>{@code 构建aar} → {@link BundleAarTask}（复用宿主 compileWithJavac 整条链）</li>
 *   <li>{@code 构建jar} → {@link BundleJarTask}（同样复用宿主编译链，只出 jar）</li>
 * </ul>
 *
 * 插件不自己编译结绳源码；名字是否用原名、用哪个 Java 版本，都由运行配置控制
 * （见 {@code com.tiecode.aarplugin.compiler} 与 {@code ...setting}）。
 */
public class AarProjectComponent extends BaseComponent {

    public static final String TIECODE_ANDROID_TYPE = "cn.tiecode.android";
    public static final String PROJECT_FILE = "project.json";

    @Override
    public void didOpenProject(Project project) {
        try {
            if (project == null || !(project instanceof CodeProject)) {
                return;
            }
            if (!isTiecodeAndroidProject(project)) {
                return;
            }
            TaskContainer tasks = project.getTasks();
            if (tasks == null) {
                return;
            }
            Task compileWithTiecodec = tasks.getTask(BundleAarTask.TASK_COMPILE_TIECODEC);
            Task compileWithJavac = tasks.getTask(BundleAarTask.TASK_COMPILE_JAVAC);
            if (compileWithTiecodec == null || compileWithJavac == null) {
                Log.w(AarBuildPlugin.TAG, "宿主缺少 compileWithTiecodec/compileWithJavac 任务，AAR/JAR 构建不可用");
                return;
            }

            // 构建aar
            if (tasks.getTask(BundleAarTask.ID) == null) {
                Task compileNative = tasks.getTask(BundleAarTask.TASK_COMPILE_NATIVE);
                Task bundleAar = new BundleAarTask();
                tasks.addTask(bundleAar);
                // 依赖宿主的 javac 任务即可把 编译结绳源码 → 资源 → 清单 → javac 整条链带起来
                bundleAar.dependsOn(compileWithJavac);
                Task linkAppResources = tasks.getTask(BundleAarTask.TASK_LINK_RESOURCES);
                if (linkAppResources != null) {
                    bundleAar.dependsOn(linkAppResources);
                }
                // 工程自己写了 JNI 时，.so 由宿主的「构建动态链接库」任务产出（没有 JNI 时它会跳过）
                if (compileNative != null) {
                    bundleAar.dependsOn(compileNative);
                }
                Log.i(AarBuildPlugin.TAG, "已挂上 AAR 打包任务(复用宿主 compileWithTiecodec 输出)");
            }

            // 构建jar
            if (tasks.getTask(BundleJarTask.ID) == null) {
                Task bundleJar = new BundleJarTask();
                tasks.addTask(bundleJar);
                bundleJar.dependsOn(compileWithJavac);
                Log.i(AarBuildPlugin.TAG, "已挂上 JAR 打包任务(复用宿主 compileWithJavac 输出)");
            }

            CodeProject codeProject = (CodeProject) project;
            // RunManager 按 id 去重，重复打开工程不会重复添加
            codeProject.addRunConfiguration(new BuildAarRunConfiguration(project));
            codeProject.addRunConfiguration(new BuildJarRunConfiguration(project));
            Log.i(AarBuildPlugin.TAG, "已注册运行配置：构建aar / 构建jar");
        } catch (Throwable t) {
            Log.w(AarBuildPlugin.TAG, "为工程注册 AAR/JAR 构建能力失败", t);
        }
    }

    /** 判断当前工程是否为「结绳安卓工程」。 */
    private boolean isTiecodeAndroidProject(Project project) {
        try {
            ProjectFramework framework = Platform.getProjectFramework();
            if (framework != null) {
                ProjectManager<?> manager = framework.getManager(project);
                if (manager != null && manager.getIdentifier() != null) {
                    return TIECODE_ANDROID_TYPE.equals(manager.getIdentifier());
                }
            }
        } catch (Throwable ignore) {
        }
        try {
            File file = project.getChildFile(PROJECT_FILE);
            if (file != null && file.exists()) {
                JSONObject root = new JSONObject(FileUtils.readFile(file));
                return TIECODE_ANDROID_TYPE.equals(root.optString("typeId", ""));
            }
        } catch (Throwable ignore) {
        }
        return false;
    }
}
