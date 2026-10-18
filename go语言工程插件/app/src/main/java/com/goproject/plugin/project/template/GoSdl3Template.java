package com.goproject.plugin.project.template;

import android.graphics.drawable.Drawable;

import com.goproject.plugin.project.GoProject;
import com.rapid.api.Platform;
import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.project.BaseTemplate;
import com.rapid.api.project.Project;
import com.rapid.framework.data.form.Form;
import com.rapid.util.android.AssetUtils;
import com.rapid.util.file.FileUtils;

import java.io.File;
import java.io.IOException;

/**
 * 「SDL3工程」模板：把 assets 里的 {@code gosdl3_template.zip} 解到工程目录。
 *
 * <p>zip 内容（自包含，编译时不需要联网）：
 * <pre>
 *   main.go              入口：//export SDL_main
 *   entry.go / logging.go
 *   go.mod               module + replace purego => ./third_party/purego
 *   vendor/              内置 go-sdl3 + purego 源码
 * </pre>
 *
 * <p>解压完成后额外写出标记文件 {@code .gosdl3}，打开工程时
 * {@link com.goproject.plugin.project.GoProjectKind} 据此装配 SDL3 构建流水线。
 */
public class GoSdl3Template extends BaseTemplate<GoProject> {

    public static final String DEFAULT_PROJECT_NAME = "我的go-SDL3工程";
    public static final String TEMPLATE_NAME = "SDL3工程";

    /** 模板 zip 在 assets 里的名字。 */
    public static final String TEMPLATE_ZIP = "gosdl3_template.zip";

    /** 模板写出的工程类别标记文件（打开工程时判定用）。 */
    public static final String KIND_MARKER = ".gosdl3";

    @Override
    public String getName() {
        return TEMPLATE_NAME;
    }

    @Override
    public Drawable getPicture() {
        return null;
    }

    @Override
    public String getDefaultProjectName() {
        return DEFAULT_PROJECT_NAME;
    }

    @Override
    public Form getForm() {
        return new Form();
    }

    @Override
    public void create(GoProject project, Form form, Project.OnProjectCreateListener onProjectCreateListener) {
        try {
            AssetUtils.unZipAssetsFolder(
                    AndroidPlugins.getContext(this),
                    TEMPLATE_ZIP,
                    project.getProjectDir().getAbsolutePath());
            FileUtils.writeFile("", new File(project.getProjectDir(), KIND_MARKER));
            onProjectCreateListener.onSuccess();
        } catch (IOException e) {
            e.printStackTrace();
            onProjectCreateListener.onFail("工程创建失败: " + e.getMessage());
        }
    }
}
