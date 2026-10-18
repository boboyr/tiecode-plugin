package com.goproject.plugin.project.template;

import android.graphics.drawable.Drawable;

import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.project.BaseTemplate;
import com.rapid.api.project.Project;
import com.rapid.framework.data.form.Form;
import com.rapid.framework.data.form.FormInput;
import com.rapid.util.android.AssetUtils;
import com.rapid.util.file.FileUtils;

import java.io.File;
import java.io.IOException;
import com.goproject.plugin.project.GoProject;


/**
 * 「控制台工程」模板：创建标准的 Go 命令行工程。
 *
 * <p>写出：
 * <pre>
 *   src/main.go   入口（package main + func main）
 *   src/go.mod    module main
 * </pre>
 * 运行走控制台流水线（go build → 运行 ELF / go mod 管理菜单）。
 */
public class GoConsoleTemplate extends BaseTemplate<GoProject> {
    public static final String TEMPLATE_NAME = "控制台工程";
    public static final String DEFAULT_PROJECT_NAME = "我的go控制台工程";

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
        Form form = new Form();
        return form;
    }

    @Override
    public void create(GoProject project, Form form, Project.OnProjectCreateListener onProjectCreateListener) {
        File file = project.getChildFile("src/main.go");
        try {
            String html = AssetUtils.readAsset(AndroidPlugins.getContext(this), "sample/main.go");
            html = html.replace("${project_name}", project.getName());
            FileUtils.writeFile(html, file);
            FileUtils.writeFile("module main\n\ngo 1.26.0", project.getChildFile("src/go.mod"));
            onProjectCreateListener.onSuccess();
        } catch (IOException e) {
            e.printStackTrace();
            onProjectCreateListener.onFail("写出默认文件失败");
        }
    }

}
