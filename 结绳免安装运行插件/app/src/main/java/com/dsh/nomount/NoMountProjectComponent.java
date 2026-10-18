package com.dsh.nomount;

import android.util.Log;

import com.rapid.api.Platform;
import com.rapid.api.framework.project.ProjectFramework;
import com.rapid.api.project.CodeProject;
import com.rapid.api.project.Project;
import com.rapid.api.project.ProjectManager;
import com.rapid.api.project.component.BaseComponent;
import com.rapid.util.file.FileUtils;

import org.json.JSONObject;

import java.io.File;

/**
 * 工程组件：打开「结绳安卓工程」({@code cn.tiecode.android}) 时，挂上运行配置「免安装运行」。
 *
 * <p>刻意不新增工程类型、不替换宿主任何任务：宿主已有的编译/打包任务原样复用，
 * 只在最后一步用宿主进程内的加载器替代「安装」。
 */
public class NoMountProjectComponent extends BaseComponent {

    public static final String TIECODE_ANDROID_TYPE = "cn.tiecode.android";
    private static final String PROJECT_FILE = "project.json";

    @Override
    public void didOpenProject(Project project) {
        try {
            if (!(project instanceof CodeProject)) {
                return;
            }
            if (!isTiecodeAndroidProject(project)) {
                return;
            }
            CodeProject codeProject = (CodeProject) project;
            // RunManager 按 id 去重，重复打开工程不会重复添加
            codeProject.addRunConfiguration(new NoMountRunConfiguration(project));
            Log.i(NoMountPlugin.TAG, "已为工程注册运行配置：" + NoMountRunConfiguration.NAME);
        } catch (Throwable t) {
            Log.w(NoMountPlugin.TAG, "注册「免安装运行」运行配置失败", t);
        }
    }

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
                return TIECODE_ANDROID_TYPE.equals(root.optString(Project.TYPE_ID, ""));
            }
        } catch (Throwable ignore) {
        }
        return false;
    }
}
