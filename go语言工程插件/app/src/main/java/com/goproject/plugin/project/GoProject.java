package com.goproject.plugin.project;

import com.goproject.plugin.CompilerGoTask;
import com.goproject.plugin.GoModTask;
import com.goproject.plugin.RunGoTask;
import com.goproject.plugin.project.running.ElfRunConfig;
import com.goproject.plugin.project.running.GoSdl3RunConfiguration;
import com.goproject.plugin.project.running.GoStartRunConfig;
import com.goproject.plugin.task.BuildLibmainTask;
import com.goproject.plugin.task.PublishLibTask;
import com.rapid.api.project.CodeProject;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.task.Task;

import java.io.File;

/**
 * Go 语言工程（合并插件唯一工程类型）。
 *
 * <p>按工程目录内容自动装配两套流水线（打开工程时 prepare，模板文件已写出）：
 * <pre>
 *   SDL3 工程   → buildLibmain (go build -buildmode=c-shared -o libmain.so)
 *                    └─> publishLib (产物拷到任务输出目录)，运行用 GoSdl3RunConfiguration
 *                    （对照结绳Linux工程插件的 tiec -> gxx -> copySoFile 三步）
 *   控制台工程 → 编译 go → 运行 ELF，运行配置 调试运行 / 运行ELF
 * </pre>
 *
 * <p>打开工程时还会在工程根目录补写 {@code External Libraries} 标记文件
 * （文件树里展开为 GO SDK 源码与模块缓存，见
 * {@link com.goproject.plugin.structure.ExternalLibrariesExpansionCase}），
 * 旧工程打开一次就有了，不用重建。
 */
public class GoProject extends CodeProject {

    /** 文件树里「外部库」节点对应的标记文件名（无扩展名，展开方式按整个名字注册）。 */
    public static final String EXTERNAL_LIBRARIES_MARKER = "External Libraries";

    @Override
    public void prepare() {
        ensureExternalLibrariesMarker();

        if (GoProjectKind.isSdl3(getProjectDir())) {
            // ---- SDL3 工程 ----
            Task buildLibmain = new BuildLibmainTask();
            Task publishLib = new PublishLibTask();

            addTask(buildLibmain);
            addTask(publishLib);

            publishLib.dependsOn(buildLibmain);

            addRunConfiguration(new GoSdl3RunConfiguration());
        } else {
            // ---- 控制台工程 ----
            addTask(new CompilerGoTask());
            addTask(new RunGoTask());
            addTask(new GoModTask());

            addRunConfiguration(new GoStartRunConfig());
            addRunConfiguration(new ElfRunConfig());
        }
    }

    @Override
    public ProjectVariant getVariant() {
        // 原 Go-SDL3 工程固定 DEBUG；控制台工程保持默认(UNDEFINED)。
        return GoProjectKind.isSdl3(getProjectDir())
                ? ProjectVariant.DEBUG
                : ProjectVariant.UNDEFINED;
    }

    /**
     * 补写 {@code External Libraries} 标记文件（已存在则跳过）。
     *
     * <p>文件树以它为「外部库」节点：展开后是 GO SDK 源码与模块缓存里的依赖目录。
     * 放在 prepare 里做是为了覆盖<b>已存在的旧工程</b>——打开一次就有了。
     */
    private void ensureExternalLibrariesMarker() {
        try {
            File marker = new File(getProjectDir(), EXTERNAL_LIBRARIES_MARKER);
            if (!marker.exists()) {
                marker.createNewFile();
            }
        } catch (Throwable ignored) {
        }
    }
}
