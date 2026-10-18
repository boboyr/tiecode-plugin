package com.linux.plugin;

import com.rapid.api.project.Project;
import com.rapid.api.project.component.BaseComponent;
import com.rapid.api.project.component.PersistenceConfig;
import com.rapid.api.project.component.StatePersistent;
import org.json.JSONObject;
import com.rapid.util.file.FileUtils;

/**
 * 工程首次打开组件。
 *
 * <p>仅对结绳 Linux 工程做初始化。工程类型通过 project.json 的 {@code type} 判断,
 * 不使用类型强转,避免宿主传入 {@code UnknownProject} 时抛出 ClassCastException。
 */
public class FirstTimeDialog extends BaseComponent implements StatePersistent<FirstTimeDialog> {

    private static final String PROJECT_TYPE = "结绳Linux工程";

    private boolean isFirstTime = true;

    public void didOpenProject(Project project) {
		
        if (project == null) {
            return;
        }
        // 非结绳 Linux 工程直接跳过,避免误伤其它工程类型。
        if (!PROJECT_TYPE.equals(getProjectType(project))) {
            return;
        }
        // 构建流水线已在 TieProject.prepare() 中装配,此处无需重复注册。
        isFirstTime = false;
    }

    /** 读取 project.json 的 type 字段,失败时返回 null 而不抛出异常。 */
    public String getProjectType(Project project) {
        try {
            return new JSONObject(FileUtils.readFile(
                    project.getChildFile("project.json").getAbsolutePath())).optString("type", null);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public FirstTimeDialog getState() {
        return this;
    }

    @Override
    public void loadState(FirstTimeDialog firstTimeDialog) {
        if (firstTimeDialog != null) {
            this.isFirstTime = firstTimeDialog.isFirstTime;
        }
    }

    @Override
    public PersistenceConfig getPersistenceConfig() {
        return new PersistenceConfig(Project.FILE_PERSISTENCE_STORAGE);
    }

}
